package com.circaso.feature.messaging.data.keys

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.crypto.OwnIdentity
import com.circaso.core.crypto.PeerBundle
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.DeviceKeyFormats
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.AddOneTimeKeysRequest
import com.circaso.feature.messaging.data.remote.KeyBundleResponse
import com.circaso.feature.messaging.data.remote.KeysRemote
import com.circaso.feature.messaging.data.remote.KyberPreKeyDto
import com.circaso.feature.messaging.data.remote.OneTimePreKeyDto
import com.circaso.feature.messaging.data.remote.PublishKeysRequest
import com.circaso.feature.messaging.data.remote.SignedPreKeyDto
import com.circaso.feature.messaging.domain.FetchedPeer
import com.circaso.feature.messaging.domain.KeyHealth
import com.circaso.feature.messaging.domain.KeysRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.time.Instant

/**
 * The key lifecycle (guide, M1–M3 and section 5). New keys are saved on the phone **before** they are sent: if the
 * upload reaches the server but the answer is lost, the private halves exist for the messages that will use them.
 * A refused upload (`request.malformed`, `messaging.too_many_prekeys`, `messaging.prekey_id_exists`) stored nothing
 * on the server, so that batch's private keys are deleted at once. Key ids come from monotonic counters.
 */
class KeysRepositoryImpl(
    private val store: SealedRecordStore,
    private val remote: KeysRemote,
    private val crypto: SignalCrypto,
    private val upload: SignedKeyUpload,
    private val json: Json,
    private val now: () -> Long = ::nowMillis,
) : KeysRepository {
    private val lock = Mutex()

    override suspend fun maintain(): AppResult<KeyHealth> = lock.withLock {
        val identity = store.identity() ?: crypto.generateIdentity().also { store.saveIdentity(it, now()) }
        store.purgeRetired(now())
        val status = remote.status()
        when {
            status is AppResult.Success -> {
                val signedAt = parseMillis(status.value.signedPreKeyAt)
                val rotate = store.currentSignedPreKey() == null || store.currentKyberPreKey() == null ||
                    signedAt == null || now() - signedAt >= RotateAfterMillis
                when {
                    rotate -> publish(identity)
                    status.value.oneTimePreKeysLeft < TopUpBelow -> topUp(identity, TargetPreKeys - status.value.oneTimePreKeysLeft)
                    else -> AppResult.Success(KeyHealth(status.value.oneTimePreKeysLeft, published = true))
                }
            }
            status is AppResult.Failure && status.error.code() == KeysNotFound -> publish(identity)
            else -> status as AppResult.Failure
        }
    }

    /** `PUT /v1/keys`: a fresh signed and Kyber prekey and a full set of one-time prekeys; the server drops the old set. */
    private suspend fun publish(identity: OwnIdentity): AppResult<KeyHealth> {
        val at = now()
        val (ecId, kyberId) = store.nextIds(SealedRecordStore.SignedCounter, 2).toList()
        val signed = crypto.generateSignedPreKey(identity, ecId, at)
        val kyber = crypto.generateKyberPreKey(identity, kyberId, at)
        val preKeys = crypto.generatePreKeys(store.nextIds(SealedRecordStore.PreKeyCounter, TargetPreKeys).first, TargetPreKeys)
        store.saveSignedPreKey(signed, at)
        store.saveKyberPreKey(kyber, at)
        store.savePreKeys(preKeys, at)
        val request = PublishKeysRequest(
            identityKey = b64(identity.publicKey),
            signedPreKey = SignedPreKeyDto(signed.id, b64(signed.publicKey), b64(signed.signature)),
            kyberPreKey = KyberPreKeyDto(kyber.id, b64(kyber.publicKey), b64(kyber.signature)),
            oneTimePreKeys = preKeys.map { OneTimePreKeyDto(it.id, b64(it.publicKey)) },
        )
        val body = json.encodeToString(PublishKeysRequest.serializer(), request).encodeToByteArray()
        return when (val result = remote.publish(body, upload.prove(body, DeviceKeyFormats.timestamp()))) {
            is AppResult.Success -> {
                // The server replaced the whole set: earlier one-time prekeys are gone there, so their private halves go here;
                // the previous signed keys stay retired for 30 days for messages already on their way.
                store.retireOtherSignedPreKeys(signed.id, kyber.id, at)
                store.deletePreKeysCreatedBefore(at)
                store.markPublished(at)
                Diagnostics.note("keys: published")
                AppResult.Success(KeyHealth(result.value.oneTimePreKeysLeft, published = true))
            }
            is AppResult.Failure -> {
                if (result.error.isRefusal()) {
                    store.deleteSignedPreKey(signed); store.deleteKyberPreKey(kyber); store.deletePreKeys(preKeys.map { it.id })
                }
                Diagnostics.note("keys: publish failed (${result.error.code() ?: "network"})")
                result
            }
        }
    }

    /** `POST /v1/keys/one-time`: [count] new one-time prekeys with ids never used before. */
    private suspend fun topUp(identity: OwnIdentity, count: Int): AppResult<KeyHealth> {
        val at = now()
        val preKeys = crypto.generatePreKeys(store.nextIds(SealedRecordStore.PreKeyCounter, count).first, count)
        store.savePreKeys(preKeys, at)
        val body = json.encodeToString(AddOneTimeKeysRequest.serializer(), AddOneTimeKeysRequest(preKeys.map { OneTimePreKeyDto(it.id, b64(it.publicKey)) })).encodeToByteArray()
        return when (val result = remote.addOneTime(body, upload.prove(body, DeviceKeyFormats.timestamp()))) {
            is AppResult.Success -> AppResult.Success(KeyHealth(result.value.oneTimePreKeysLeft, published = true))
            is AppResult.Failure -> {
                // Refused whole (guide, section 5): nothing of it was stored or handed out, so the private keys can go now.
                if (result.error.isRefusal()) store.deletePreKeys(preKeys.map { it.id })
                if (result.error.code() == KeysNotFound) publish(identity) else result
            }
        }
    }

    override suspend fun peerBundle(userId: String): AppResult<FetchedPeer> = when (val result = remote.bundle(userId)) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val bundle = result.value.toPeerBundle() ?: return AppResult.Failure(AppError.Unexpected(200))
            AppResult.Success(FetchedPeer(bundle, store.rememberPeerIdentity(userId, bundle.identityKey, now())))
        }
    }

    private fun KeyBundleResponse.toPeerBundle(): PeerBundle? = runCatching {
        PeerBundle(
            // libsignal checks the registration id only against a session's own record; the server does not publish it.
            registrationId = 0,
            identityKey = Base64.decode(identityKey),
            signedPreKeyId = signedPreKey.keyId, signedPreKey = Base64.decode(signedPreKey.publicKey), signedPreKeySignature = Base64.decode(signedPreKey.signature),
            kyberPreKeyId = kyberPreKey.keyId, kyberPreKey = Base64.decode(kyberPreKey.publicKey), kyberPreKeySignature = Base64.decode(kyberPreKey.signature),
            oneTimePreKeyId = oneTimePreKey?.keyId, oneTimePreKey = oneTimePreKey?.let { Base64.decode(it.publicKey) },
        )
    }.getOrNull()

    private fun b64(bytes: ByteArray) = Base64.encode(bytes)
    private fun AppError.code() = (this as? AppError.Api)?.code
    private fun AppError.isRefusal() = (this as? AppError.Api)?.let { it.httpStatus in 400..499 && it.code != KeysProofInvalid } == true
    private fun parseMillis(iso: String) = runCatching { Instant.parse(iso).toEpochMilliseconds() }.getOrNull()

    companion object {
        const val TargetPreKeys = 100
        const val TopUpBelow = 20
        const val RotateAfterMillis = 7L * 24 * 60 * 60 * 1000
        const val KeysNotFound = "messaging.keys_not_found"
        const val KeysProofInvalid = "messaging.keys_proof_invalid"
    }
}
