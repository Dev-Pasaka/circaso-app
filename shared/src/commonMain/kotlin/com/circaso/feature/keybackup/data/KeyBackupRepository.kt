package com.circaso.feature.keybackup.data

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.AccountPasskey
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
    /**
     * This phone holds the root, but the server's copy is sealed under another passkey than the one the account's
     * PIN Vault uses now (an older app made a second passkey, which replaced the first): one answer from the passkey
     * in use seals it again ([KeyBackupRepository.turnOn]), so other phones can restore.
     */
    val needsRepair: Boolean = false,
)

/** Why turning on or restoring didn't happen. */
sealed interface KeyBackupProblem {
    data object Cancelled : KeyBackupProblem
    data object Unsupported : KeyBackupProblem
    data object WrongPasskey : KeyBackupProblem
    /**
     * This phone can't reach the passkey the backup is sealed under: its password manager doesn't have it (it is on
     * another phone or in another Google or Apple account, or was replaced), or the passkey it does have is another
     * one and this phone holds no root to move under it.
     */
    data object PasskeyMissing : KeyBackupProblem
    data class Passkey(val reason: String) : KeyBackupProblem
    data class Server(val error: AppError) : KeyBackupProblem
    /** Backup was turned off (on another phone) before this one restored. */
    data object TurnedOff : KeyBackupProblem
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
 *
 * One passkey per account ([AccountPasskey], 2026-11-22): nothing here makes a passkey while the vault or the root
 * names one. An account whose root was left under a replaced passkey heals from any phone that still holds the root
 * ([reseal]); without such a phone the person can deliberately [startNew].
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
    private val refreshChats: suspend () -> Unit = {},
    /** Which passkey to use, or whether to make one: the one rule the PIN Vault shares. */
    private val account: AccountPasskey = AccountPasskey(passkeys),
) {
    private val lock = Mutex()
    private val state = MutableStateFlow(KeyBackupState())
    val status: StateFlow<KeyBackupState> = state
    private var root: ByteArray? = null
    @kotlin.concurrent.Volatile private var restoring = false

    /**
     * Runs (outside the lock, never throwing to the caller) once this phone holds the root: after [restore], [turnOn]
     * and [onPrfSecret]. Other sealed backups that ride on the root (saved names) pull and merge here.
     */
    internal var onRootReady: suspend () -> Unit = {}

    /** A copy of the root while this phone holds it (backup on here), for the other backups sealed with it; else null. */
    internal suspend fun rootOnPhone(): ByteArray? = lock.withLock {
        if (root == null) userId()?.let { root = loadLocal(it) }
        root?.copyOf()
    }

    private suspend fun rootReady() {
        try { onRootReady() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* tried again next start */ }
    }

    suspend fun supported(): Boolean = runCatching { passkeys.isSupported() }.getOrDefault(false)

    /** Reads this phone's root and asks the server whether backup is on; cheap, call on start and sign-in. */
    suspend fun refresh(): AppResult<KeyBackupState> = lock.withLock {
        val person = userId() ?: return@withLock AppResult.Failure(AppError.Unexpected(401))
        if (root == null) root = loadLocal(person)
        when (val r = remote.root()) {
            is AppResult.Success -> {
                // The server says it's off (turned off on another phone): this phone stops backing up too.
                if (!r.value.exists && root != null) { root = null; secure.remove(LocalRoot) }
                state.value = KeyBackupState(onServer = r.value.exists, onPhone = root != null, needsRepair = root != null && underAnotherPasskey(r.value))
                AppResult.Success(state.value)
            }
            is AppResult.Failure -> { state.value = state.value.copy(onPhone = root != null); r }
        }
    }

    /** The server's root names another passkey than the account's vault does (no prompt; false when that isn't known). */
    private suspend fun underAnotherPasskey(current: BackupRootDto): Boolean {
        if (!current.exists) return false
        val inUse = runCatching { account.vaultPasskey() }.getOrNull() ?: return false
        return current.credentialId != encodeId(inUse)
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
     * prompt of its own. When backup is on under another passkey and this phone holds the root, the root is sealed
     * again under this one ([reseal]). [secret] is only read here, never kept.
     */
    suspend fun onPrfSecret(credentialId: ByteArray, secret: ByteArray) {
        val person = userId() ?: return
        val current = (remote.root() as? AppResult.Success)?.value ?: return
        lock.withLock {
            when {
                !current.exists -> createRoot(person, credentialId, secret)
                // Already on under this passkey: a phone that lacks the root takes it now.
                current.credentialId == encodeId(credentialId) -> if (root == null) openRoot(secret, person, current)?.let { keepLocal(person, it) }
                else -> reseal(person, credentialId, secret, current)
            }
        }
        backfill()
        if (root != null) rootReady()
    }

    /**
     * Turns backup on from Security; one passkey answer. With a root already on the server, the passkey that sealed it
     * opens it, except on a phone that already holds the root: there the passkey the account uses now answers, and a
     * root left under another passkey is sealed again under it (the Security row's Repair).
     */
    suspend fun turnOn(handle: String, displayName: String): KeyBackupProblem? {
        val person = userId() ?: return KeyBackupProblem.Server(AppError.Unexpected(401))
        val current = when (val r = remote.root()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
        }
        val held = rootOnPhone()?.also { it.fill(0) } != null
        val own = if (current.exists && !held) credentialOf(current) ?: return KeyBackupProblem.WrongPasskey else null
        val (id, secret) = when (val a = account.secret(own, person, handle, displayName, VaultRepository.PrfSalt)) {
            is AccountPasskey.Answer.Ready -> a.credentialId to a.secret
            is AccountPasskey.Answer.NoAnswer -> return a.result.problem()
            is AccountPasskey.Answer.Unknown -> return KeyBackupProblem.Server(a.error)
        }
        try {
            lock.withLock {
                when {
                    !current.exists -> createRoot(person, id, secret)?.let { return KeyBackupProblem.Server(it) }
                    current.credentialId == encodeId(id) -> keepLocal(person, openRoot(secret, person, current) ?: return KeyBackupProblem.WrongPasskey)
                    !reseal(person, id, secret, current) -> return KeyBackupProblem.PasskeyMissing
                    else -> Unit
                }
            }
        } finally { secret.fill(0) }
        backfill()
        rootReady()
        return null
    }

    /** After signing in again: the passkey answers once, and every backed-up group key comes back to this phone. */
    suspend fun restore(): KeyBackupProblem? {
        val person = userId() ?: return KeyBackupProblem.Server(AppError.Unexpected(401))
        val current = when (val r = remote.root()) {
            is AppResult.Success -> r.value.takeIf { it.exists } ?: return KeyBackupProblem.TurnedOff
            is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
        }
        val credential = credentialOf(current) ?: return KeyBackupProblem.WrongPasskey
        val salt = current.salt?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return KeyBackupProblem.WrongPasskey
        val secret = when (val r = passkeys.secret(credential, salt)) {
            is PrfResult.Success -> r.secret ?: return KeyBackupProblem.Unsupported
            else -> return r.problem()
        }
        val rootKey = try { openRoot(secret, person, current) } finally { secret.fill(0) } ?: return KeyBackupProblem.WrongPasskey
        return pull(person, rootKey)
    }

    /** Brings every group key the server holds back with [rootKey], then keeps the root on this phone. */
    private suspend fun pull(person: String, rootKey: ByteArray): KeyBackupProblem? {
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
        // Only once every key is back does this phone count as backed up (a failed page leaves the restore on offer).
        lock.withLock { keepLocal(person, rootKey) }
        runCatching { refreshChats() }
        rootReady()
        return null
    }

    /**
     * The person's own choice when the passkey that sealed the backup is gone: a new backup under the passkey this
     * phone can use. The old root, every key sealed with it and the saved names are deleted from the server (KB3), so
     * what was only in the old backup is lost; then a new root is made and this phone's keys go up again.
     *
     * Nothing is deleted before a passkey has answered, and nothing at all when it turns out not to be needed: the
     * answering passkey opens the old root after all (then this is a restore), or this phone still holds the root
     * (then the root is sealed again under the answering passkey and the backup is kept).
     * A passkey is made only when no vault names one ([AccountPasskey]).
     */
    suspend fun startNew(handle: String, displayName: String): KeyBackupProblem? {
        val person = userId() ?: return KeyBackupProblem.Server(AppError.Unexpected(401))
        val current = when (val r = remote.root()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
        }
        if (!current.exists) return turnOn(handle, displayName)
        val (id, secret) = when (val a = account.secret(null, person, handle, displayName, VaultRepository.PrfSalt, withoutBackupRoot = true)) {
            is AccountPasskey.Answer.Ready -> a.credentialId to a.secret
            is AccountPasskey.Answer.NoAnswer -> return a.result.problem()
            is AccountPasskey.Answer.Unknown -> return KeyBackupProblem.Server(a.error)
        }
        try {
            // The passkey that sealed the old backup answered after all: nothing is lost, bring it back.
            if (current.credentialId == encodeId(id)) openRoot(secret, person, current)?.let { return pull(person, it) }
            val kept = lock.withLock {
                // This phone still holds the root: keep the backup, under the passkey that answered.
                if (reseal(person, id, secret, current)) return@withLock true
                when (val r = remote.remove()) {
                    is AppResult.Failure -> return KeyBackupProblem.Server(r.error)
                    is AppResult.Success -> { root = null; secure.remove(LocalRoot); state.value = KeyBackupState(onServer = false, onPhone = false) }
                }
                createRoot(person, id, secret)?.let { return KeyBackupProblem.Server(it) }
                false
            }
            // Kept: the keys other phones backed up come to this one too.
            if (kept) rootOnPhone()?.let { return pull(person, it) }
        } finally { secret.fill(0) }
        backfill()
        rootReady()
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

    private suspend fun createRoot(person: String, credentialId: ByteArray, secret: ByteArray): AppError? =
        saveRoot(person, credentialId, secret, crypto.randomBytes(32), expectedVersion = null)

    /** Seals [key] under [secret]'s wrap key and saves it as the account's root; this phone keeps [key] once saved. */
    private suspend fun saveRoot(person: String, credentialId: ByteArray, secret: ByteArray, key: ByteArray, expectedVersion: Int?): AppError? {
        val wrap = wrapKey(secret, person)
        val sealed = try { crypto.aesGcmSeal(wrap, key, rootAad(person)) } finally { wrap.fill(0) }
        val request = SaveRootRequest(encodeId(credentialId), Base64.encode(VaultRepository.PrfSalt), Base64.encode(sealed), Algorithm, expectedVersion)
        return when (val r = remote.saveRoot(request)) {
            is AppResult.Success -> { keepLocal(person, key); null }
            is AppResult.Failure -> r.error
        }
    }

    /**
     * The server's root is sealed under another passkey than [credentialId], whose [secret] just answered. When this
     * phone holds that very root, seals it again under [secret] and replaces the server's copy (KB2 with the version
     * read, so a root another phone saved meanwhile is refused). The root key itself doesn't change, so every backed-up
     * group key and the saved names stay readable.
     *
     * Never without the root on this phone, and never with one that isn't the server's: a root this phone kept from
     * before backup was turned off and on elsewhere would lock every phone out of the keys sealed since. The proof is
     * a backed-up group key that this phone's root opens. False (and nothing changed) otherwise.
     */
    private suspend fun reseal(person: String, credentialId: ByteArray, secret: ByteArray, current: BackupRootDto): Boolean {
        if (!current.exists) return false
        if (root == null) root = loadLocal(person)
        val held = root ?: return false
        val page = (remote.groupKeys(null, PageSize) as? AppResult.Success)?.value ?: return false
        val sameRoot = page.items.any { item ->
            val sealed = runCatching { Base64.decode(item.ciphertext) }.getOrNull() ?: return@any false
            runCatching { crypto.aesGcmOpen(held, sealed, keyAad(person, item.chatId, item.metaVersion)) }.getOrNull()?.also { it.fill(0) } != null
        }
        if (!sameRoot) return false
        return saveRoot(person, credentialId, secret, held, expectedVersion = current.version) == null
    }

    private fun credentialOf(dto: BackupRootDto): ByteArray? = dto.credentialId?.let { runCatching { decodeId(it) }.getOrNull() }

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
        PrfResult.NoPasskey -> KeyBackupProblem.PasskeyMissing
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
