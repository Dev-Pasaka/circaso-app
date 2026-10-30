package com.circaso.feature.messaging

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.AddOneTimeKeysRequest
import com.circaso.feature.messaging.data.remote.KeyBundleResponse
import com.circaso.feature.messaging.data.remote.KeyStatusResponse
import com.circaso.feature.messaging.data.remote.KeysRemote
import com.circaso.feature.messaging.data.remote.KyberPreKeyDto
import com.circaso.feature.messaging.data.remote.OneTimePreKeyDto
import com.circaso.feature.messaging.data.remote.OneTimePreKeysLeftResponse
import com.circaso.feature.messaging.data.remote.PublishKeysRequest
import com.circaso.feature.messaging.data.remote.SignedPreKeyDto
import com.circaso.testing.FakeKeys
import com.circaso.testing.FakeSealer
import com.circaso.testing.FakeSignalCrypto
import com.circaso.testing.FakeSignalDao
import com.circaso.testing.runSuspending
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeysRepositoryTest {
    private val json = circasoJson()
    private val dao = FakeSignalDao()
    private val store = SealedRecordStore(dao, FakeSealer())
    private val crypto = FakeSignalCrypto()
    private var now = 1_700_000_000_000L
    private val remote = FakeKeysRemote()
    private val repo = KeysRepositoryImpl(store, remote, crypto, SignedKeyUpload(FakeKeys(), crypto), json) { now }

    private fun api(code: String, status: Int = 400) = AppResult.Failure(AppError.Api(code, status, "r", null, emptyMap()))
    private fun iso(millis: Long) = kotlin.time.Instant.fromEpochMilliseconds(millis).toString()

    private class FakeKeysRemote : KeysRemote {
        var status: AppResult<KeyStatusResponse> = AppResult.Failure(AppError.Api("messaging.keys_not_found", 404, "r", null, emptyMap()))
        var publishResult: (PublishKeysRequest) -> AppResult<OneTimePreKeysLeftResponse> = { AppResult.Success(OneTimePreKeysLeftResponse(it.oneTimePreKeys!!.size)) }
        var topUpResult: (AddOneTimeKeysRequest) -> AppResult<OneTimePreKeysLeftResponse> = { AppResult.Success(OneTimePreKeysLeftResponse(100)) }
        var bundle: AppResult<KeyBundleResponse> = AppResult.Failure(AppError.Network)
        val published = mutableListOf<PublishKeysRequest>()
        val toppedUp = mutableListOf<AddOneTimeKeysRequest>()
        val proofs = mutableListOf<Pair<ByteArray, SignedKeyUpload.Proof>>()
        private val json = circasoJson()

        override suspend fun publish(body: ByteArray, proof: SignedKeyUpload.Proof): AppResult<OneTimePreKeysLeftResponse> {
            proofs += body to proof
            val request = json.decodeFromString(PublishKeysRequest.serializer(), body.decodeToString())
            published += request
            return publishResult(request)
        }
        override suspend fun addOneTime(body: ByteArray, proof: SignedKeyUpload.Proof): AppResult<OneTimePreKeysLeftResponse> {
            proofs += body to proof
            val request = json.decodeFromString(AddOneTimeKeysRequest.serializer(), body.decodeToString())
            toppedUp += request
            return topUpResult(request)
        }
        override suspend fun status() = status
        override suspend fun bundle(userId: String) = bundle
    }

    @Test fun firstRunMakesAnIdentityAndPublishesAFullSet() = runSuspending {
        val health = assertIs<AppResult.Success<*>>(repo.maintain())
        val request = remote.published.single()
        assertEquals(100, request.oneTimePreKeys!!.size)
        assertEquals((1..100).toList(), request.oneTimePreKeys!!.map { it.keyId })
        assertEquals(1, request.signedPreKey.keyId)
        assertEquals(2, request.kyberPreKey.keyId)
        assertEquals(Base64.encode("idpub:1".encodeToByteArray()), request.identityKey)
        assertNotNull(dao.identityRow?.publishedAt)
        assertEquals(100, dao.preKeys.size)
        assertEquals(2, dao.signed.size)
        // the body that was signed is the body that was sent, with both proof headers
        val (body, proof) = remote.proofs.single()
        assertEquals(request, json.decodeFromString(PublishKeysRequest.serializer(), body.decodeToString()))
        assertEquals(setOf("X-Device-Timestamp", "X-Device-Signature"), proof.headers.keys)
        assertTrue(proof.signature.isNotEmpty())
    }

    @Test fun recordsAreSealedInRoomAndOpenAgain() = runSuspending {
        repo.maintain()
        val row = dao.preKeys[1]!!
        assertFalse(row.record.contentEquals("opk:1".encodeToByteArray()), "the private record is not stored in clear")
        assertEquals(mapOf(1 to "opk:1").mapValues { it.value.encodeToByteArray().toList() }, store.ownKeys().preKeys.filterKeys { it == 1 }.mapValues { it.value.toList() })
        assertEquals("idpriv:1", store.identity()!!.keyPair.decodeToString())
    }

    @Test fun nothingHappensWhenTheServerIsHealthy() = runSuspending {
        remote.status = AppResult.Success(KeyStatusResponse(80, iso(now - 1000)))
        repo.maintain() // makes the identity, but the server says keys are there and fresh
        // ... except that this phone has no signed prekey of its own yet, so it must publish once
        assertEquals(1, remote.published.size)
        remote.published.clear()
        repo.maintain()
        assertTrue(remote.published.isEmpty())
        assertTrue(remote.toppedUp.isEmpty())
    }

    @Test fun belowTwentyLeftTopsUpToAHundredWithFreshIds() = runSuspending {
        repo.maintain() // ids 1..100 published
        remote.status = AppResult.Success(KeyStatusResponse(12, iso(now)))
        val health = assertIs<AppResult.Success<*>>(repo.maintain())
        val topUp = remote.toppedUp.single()
        assertEquals(88, topUp.oneTimePreKeys.size)
        assertEquals((101..188).toList(), topUp.oneTimePreKeys.map { it.keyId })
        assertEquals(188, dao.preKeys.size)
    }

    @Test fun aRefusedTopUpDeletesThatBatchAtOnce() = runSuspending {
        repo.maintain()
        remote.status = AppResult.Success(KeyStatusResponse(5, iso(now)))
        remote.topUpResult = { api("messaging.prekey_id_exists", 409) }
        assertIs<AppResult.Failure>(repo.maintain())
        assertEquals(100, dao.preKeys.size, "the refused batch's private keys are gone; the published ones stay")
        // the next top-up never reuses those ids
        remote.topUpResult = { AppResult.Success(OneTimePreKeysLeftResponse(100)) }
        repo.maintain()
        assertEquals((196..290).toList(), remote.toppedUp.last().oneTimePreKeys.map { it.keyId })
    }

    @Test fun aNetworkFailureKeepsTheNewKeysForTheRetry() = runSuspending {
        remote.publishResult = { AppResult.Failure(AppError.Network) }
        assertIs<AppResult.Failure>(repo.maintain())
        assertEquals(100, dao.preKeys.size, "the upload may have landed: the private halves stay")
        assertNull(dao.identityRow?.publishedAt)
        remote.publishResult = { AppResult.Success(OneTimePreKeysLeftResponse(100)) }
        repo.maintain()
        assertNotNull(dao.identityRow?.publishedAt)
    }

    @Test fun sevenDaysOnRotatesTheSignedKeysAndRetiresTheOldOnes() = runSuspending {
        repo.maintain()
        now += 8L * 24 * 60 * 60 * 1000
        remote.status = AppResult.Success(KeyStatusResponse(90, iso(now - 8L * 24 * 60 * 60 * 1000)))
        repo.maintain()
        assertEquals(2, remote.published.size)
        assertEquals(3, remote.published.last().signedPreKey.keyId)
        assertEquals(4, remote.published.last().kyberPreKey.keyId)
        val retired = dao.signed.values.filter { it.retiredAt != null }.map { it.keyId }.sorted()
        assertEquals(listOf(1, 2), retired, "the old signed and Kyber prekeys are kept, retired")
        assertEquals(4, store.ownKeys().signedPreKeys.size + store.ownKeys().kyberPreKeys.size, "all four still open messages in flight")
        assertEquals((101..200).toList(), dao.preKeys.keys.sorted(), "the old one-time set is gone with the server's")
        // 30 days later the retired ones are purged
        now += 31L * 24 * 60 * 60 * 1000
        remote.status = AppResult.Success(KeyStatusResponse(90, iso(now)))
        repo.maintain()
        assertEquals(listOf(3, 4), dao.signed.values.map { it.keyId }.sorted())
    }

    @Test fun aServerResetIsNoticedAndEverythingIsPublishedAgain() = runSuspending {
        repo.maintain()
        remote.status = api("messaging.keys_not_found", 404)
        repo.maintain()
        assertEquals(2, remote.published.size)
    }

    @Test fun aWrongClockIsReportedAndTheKeysAreKept() = runSuspending {
        remote.publishResult = { api("messaging.keys_proof_invalid", 403) }
        val failure = assertIs<AppResult.Failure>(repo.maintain())
        assertEquals("messaging.keys_proof_invalid", (failure.error as AppError.Api).code)
        assertEquals(100, dao.preKeys.size, "not a refusal of the keys themselves: they stay for the retry")
    }

    @Test fun aPeerBundleIsDecodedAndItsIdentityRememberedThenAChangeIsNoticed() = runSuspending {
        fun response(identity: String) = AppResult.Success(KeyBundleResponse("u2", Base64.encode(identity.encodeToByteArray()), SignedPreKeyDto(7, Base64.encode(byteArrayOf(1)), Base64.encode(byteArrayOf(2))), KyberPreKeyDto(3, Base64.encode(byteArrayOf(3)), Base64.encode(byteArrayOf(4))), OneTimePreKeyDto(101, Base64.encode(byteArrayOf(5)))))
        remote.bundle = response("idpub:2")
        val first = assertIs<AppResult.Success<*>>(repo.peerBundle("u2")).value as com.circaso.feature.messaging.domain.FetchedPeer
        assertFalse(first.identityChanged)
        assertEquals(101, first.bundle.oneTimePreKeyId)
        assertEquals(3, first.bundle.kyberPreKeyId)
        assertFalse(assertIs<AppResult.Success<*>>(repo.peerBundle("u2")).value.let { (it as com.circaso.feature.messaging.domain.FetchedPeer).identityChanged })
        remote.bundle = response("idpub:9")
        assertTrue((assertIs<AppResult.Success<*>>(repo.peerBundle("u2")).value as com.circaso.feature.messaging.domain.FetchedPeer).identityChanged)
        assertNotNull(dao.peers["u2"]?.changedAt)
    }
}
