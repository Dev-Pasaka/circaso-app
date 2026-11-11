package com.circaso.feature.keybackup.data

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.core.platform.SecureStorage
import com.circaso.feature.vault.data.Hkdf
import com.circaso.feature.vault.data.VaultRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** What the group key backup looks like from this phone. */
data class KeyBackupState(
    /** The server has a root (backup is on for this account); null until asked. */
    val onServer: Boolean? = null,
    /** This phone holds the root, so new group keys are backed up as they arrive. */
    val onPhone: Boolean = false,
)

/** Why turning on or restoring didn't happen. */
sealed interface KeyBackupProblem {
    data object Cancelled : KeyBackupProblem
    data object Unsupported : KeyBackupProblem
    data object WrongPasskey : KeyBackupProblem
    data class Passkey(val reason: String) : KeyBackupProblem
    data class Server(val error: AppError) : KeyBackupProblem
}

/**
 * The group key backup (2026-11-11). A group's name and photo are sealed with its metadata key, which only members'
 * phones hold, so signing out or a new phone used to lose every group name until another member's phone answered.
 *
 * - A random 32-byte **root** key is made once per account. The server keeps it sealed under the person's circaso.com
 *   passkey: HKDF-SHA256 of the passkey's PRF secret ([VaultRepository.PrfSalt], the same passkey as the PIN Vault) with
 *   its own info, so the vault's key and this one are unrelated. This phone keeps the root in secure storage (wiped on
 *   sign-out) and never asks for the passkey again while signed in.
 * - Every group key this phone keeps is sealed with the root (AES-256-GCM, bound to person, chat and version) and
 *   uploaded at once; [backfill] sends any the phone has that the server may lack.
 * - After signing in again, [restore] asks the passkey once, unseals the root and brings every group key back.
 *
 * Turning it on rides on a PRF secret the vault already has ([onPrfSecret]), so no extra prompt there; [turnOn] asks
 * the passkey itself for people without a vault. The server never sees a key.
 */
@OptIn(ExperimentalEncodingApi::class)
class KeyBackupRepository(
    private val remote: KeyBackupRemote,
    private val passkeys: PasskeyPrf,
    private val crypto: SignalCrypto,
    private val secure: SecureStorage,
    private val userId: () -> String?,
    /** Every real group key on this phone: (chatId, metaVersion, key). */
    private val localKeys: suspend () -> List<Triple<String, Int, ByteArray>>,
    /** Keeps a restored key as the group's (and re-reads the group's name). */
    private val keepKey: suspend (chatId: String, metaVersion: Int, key: ByteArray) -> Unit,
    /** The vault's passkey, when there is one: reused, so the person has one Circaso passkey. */
    private val vaultCredential: suspend () -> ByteArray? = { null },
    private val refreshChats: suspend () -> Unit = {},
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(KeyBackupState())
    val status: StateFlow<KeyBackupState> = state
    private var root: ByteArray? = null
    @kotlin.concurrent.Volatile private var restoring = false

    suspend fun supported(): Boolean = runCatching { passkeys.isSupported() }.getOrDefault(false)

    /** Reads this phone's root and asks the server whether backup is on; cheap, call on start and sign-in. */
    suspend fun refresh(): AppResult<KeyBackupState> = lock.withLock {
        val person = userId() ?: return@withLock AppResult.Failure(AppError.Unexpected(401))
        if (root == null) root = loadLocal(person)
        when (val r = remote.root()) {
            is AppResult.Success -> {
                // The server says it's off (turned off on another phone): this phone stops backing up too.
                if (!r.value.exists && root != null) { root = null; secure.remove(LocalRoot) }
                state.value = KeyBackupState(onServer = r.value.exists, onPhone = root != null)
                AppResult.Success(state.value)
            }
            is AppResult.Failure -> { state.value = state.value.copy(onPhone = root != null); r }
        }
    }

    /** A group key this phone just kept: sealed and uploaded when backup is on here. Never throws. */
    suspend fun onKeyKept(chatId: String, metaVersion: Int, key: ByteArray) {
        if (restoring) return
        val person = userId() ?: return
        val r = root ?: return
        runCatching { remote.putGroupKey(chatId, metaVersion, Base64.encode(sealKey(r, person, chatId, metaVersion, key))) }
    }

    /** Sends every group key on this phone (idempotent); after turning on, and once per start while it's on. */
    suspend fun backfill() {
        val person = userId() ?: return
        val r = root ?: return
        for ((chatId, version, key) in runCatching { localKeys() }.getOrDefault(emptyList())) {
            try { remote.putGroupKey(chatId, version, Base64.encode(sealKey(r, person, chatId, version, key))) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        }
    }

    /**
     * The vault just had its passkey's PRF secret (saving or opening): turns backup on with it when it's off, with no
     * prompt of its own. [secret] is only read here, never kept.
     */
    suspend fun onPrfSecret(credentialId: ByteArray, secret: ByteArray) {
        val person = userId() ?: return
        val current = (remote.root() as? AppResult.Success)?.value ?: return
        lock.withLock {
            if (current.exists) {
                // Already on: if this phone lacks the root and the vault's passkey sealed it, take it now.
                if (root == null && current.credentialId == encodeId(credentialId)) {
                    openRoot(secret, person, current)?.let { keepLocal(person, it) }
                }
                return@withLock
            }
            createRoot(person, credentialId, secret)
        }
        backfill()
    }

    /** Turns backup on from Security: the vault's passkey (or a new one) answers once. */
    suspend fun turnOn(handle: String, displayName: String): KeyBackupProblem? {
        val person = userId() ?: return KeyBackupProblem.Server(AppError.Unexpected(401))
        val credential = vaultCredential()
        val (id, secret) = when (val r = if (credential != null) passkeys.secret(credential, VaultRepository.PrfSalt) else passkeys.create(person, handle, displayName, VaultRepository.PrfSalt)) {
            is PrfResult.Success -> {
                val s = r.secret ?: when (val again = passkeys.secret(r.credentialId, VaultRepository.PrfSalt)) {
                    is PrfResult.Success -> again.secret ?: return KeyBackupProblem.Unsupported
                    else -> return again.problem()
                }
                r.credentialId to s
            }
            else -> return r.problem()
        }
        try {
            val current = when (val r = remote.root()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
            }
            lock.withLock {
                if (current.exists) openRoot(secret, person, current)?.let { keepLocal(person, it) } ?: return KeyBackupProblem.WrongPasskey
                else createRoot(person, id, secret)?.let { return KeyBackupProblem.Server(it) }
            }
        } finally { secret.fill(0) }
        backfill()
        return null
    }

    /** After signing in again: the passkey answers once, and every backed-up group key comes back to this phone. */
    suspend fun restore(): KeyBackupProblem? {
        val person = userId() ?: return KeyBackupProblem.Server(AppError.Unexpected(401))
        val current = when (val r = remote.root()) {
            is AppResult.Success -> r.value.takeIf { it.exists } ?: return KeyBackupProblem.Server(AppError.Unexpected(404))
            is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
        }
        val credential = current.credentialId?.let { runCatching { decodeId(it) }.getOrNull() } ?: return KeyBackupProblem.WrongPasskey
        val salt = current.salt?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return KeyBackupProblem.WrongPasskey
        val secret = when (val r = passkeys.secret(credential, salt)) {
            is PrfResult.Success -> r.secret ?: return KeyBackupProblem.Unsupported
            else -> return r.problem()
        }
        val rootKey = try { openRoot(secret, person, current) } finally { secret.fill(0) } ?: return KeyBackupProblem.WrongPasskey
        lock.withLock { keepLocal(person, rootKey) }
        restoring = true
        try {
            var after: String? = null
            do {
                val page = when (val r = remote.groupKeys(after, PageSize)) {
                    is AppResult.Success -> r.value
                    is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
                }
                for (item in page.items) {
                    val sealed = runCatching { Base64.decode(item.ciphertext) }.getOrNull() ?: continue
                    val key = runCatching { crypto.aesGcmOpen(rootKey, sealed, keyAad(person, item.chatId, item.metaVersion)) }.getOrNull() ?: continue
                    keepKey(item.chatId, item.metaVersion, key)
                }
                after = page.nextCursor
            } while (after != null)
        } finally { restoring = false }
        runCatching { refreshChats() }
        return null
    }

    /** Turns backup off for the account: the server forgets the root and every sealed key; this phone forgets the root. */
    suspend fun turnOff(): AppResult<Unit> = when (val r = remote.remove()) {
        is AppResult.Success -> lock.withLock {
            root = null; secure.remove(LocalRoot)
            state.value = KeyBackupState(onServer = false, onPhone = false)
            AppResult.Success(Unit)
        }
        is AppResult.Failure -> r
    }

    // ---- sealing

    private suspend fun createRoot(person: String, credentialId: ByteArray, secret: ByteArray): AppError? {
        val newRoot = crypto.randomBytes(32)
        val wrap = wrapKey(secret, person)
        val sealed = try { crypto.aesGcmSeal(wrap, newRoot, rootAad(person)) } finally { wrap.fill(0) }
        val request = SaveRootRequest(encodeId(credentialId), Base64.encode(VaultRepository.PrfSalt), Base64.encode(sealed), Algorithm)
        return when (val r = remote.saveRoot(request)) {
            is AppResult.Success -> { keepLocal(person, newRoot); null }
            is AppResult.Failure -> r.error
        }
    }

    private fun openRoot(secret: ByteArray, person: String, dto: BackupRootDto): ByteArray? {
        val sealed = dto.sealedRoot?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
        val wrap = wrapKey(secret, person)
        return try { runCatching { crypto.aesGcmOpen(wrap, sealed, rootAad(person)) }.getOrNull() } finally { wrap.fill(0) }
    }

    private fun wrapKey(secret: ByteArray, person: String) = Hkdf.sha256(secret, WrapSalt, rootAad(person), 32, crypto::sha256)
    private fun sealKey(root: ByteArray, person: String, chatId: String, version: Int, key: ByteArray) = crypto.aesGcmSeal(root, key, keyAad(person, chatId, version))
    private fun rootAad(person: String) = "keybackup/root/v1|$person".encodeToByteArray()
    private fun keyAad(person: String, chatId: String, version: Int) = "keybackup/gk/v1|$person|$chatId|$version".encodeToByteArray()

    private suspend fun keepLocal(person: String, key: ByteArray) {
        root = key
        secure.put(LocalRoot, "$person|${Base64.encode(key)}")
        state.value = KeyBackupState(onServer = true, onPhone = true)
    }

    private suspend fun loadLocal(person: String): ByteArray? {
        val saved = secure.get(LocalRoot) ?: return null
        if (saved.substringBefore('|') != person) { secure.remove(LocalRoot); return null }
        return runCatching { Base64.decode(saved.substringAfter('|')) }.getOrNull()
    }

    private fun encodeId(id: ByteArray) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(id)
    private fun decodeId(id: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(id)

    private fun PrfResult.problem(): KeyBackupProblem = when (this) {
        PrfResult.Cancelled -> KeyBackupProblem.Cancelled
        PrfResult.Unsupported -> KeyBackupProblem.Unsupported
        is PrfResult.Failed -> KeyBackupProblem.Passkey(reason)
        is PrfResult.Success -> KeyBackupProblem.Unsupported
    }

    companion object {
        /** Secure-storage key for this phone's copy of the root ("userId|base64"); wiped on sign-out. */
        const val LocalRoot = "keybackup.root"
        const val Algorithm = "prf-hkdf-sha256+aes-256-gcm/keybackup-v1"
        private val WrapSalt = "circaso-keybackup".encodeToByteArray()
        private const val PageSize = 500
    }
}
