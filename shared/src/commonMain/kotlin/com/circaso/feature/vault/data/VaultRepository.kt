package com.circaso.feature.vault.data

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.feature.vault.domain.VaultContents
import com.circaso.feature.vault.domain.VaultStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Why the vault couldn't be saved or opened, in the words the screens need. */
sealed interface VaultProblem {
    data object Cancelled : VaultProblem
    data object Unsupported : VaultProblem
    data object NoVault : VaultProblem
    /** The passkey's secret didn't open it: another passkey was picked, or the vault is damaged. */
    data object WrongPasskey : VaultProblem
    data class Server(val error: AppError) : VaultProblem
    data class Passkey(val reason: String) : VaultProblem
}

sealed interface VaultOutcome<out T> {
    data class Done<T>(val value: T) : VaultOutcome<T>
    /** [serverOpened]: the server already counted it as an opening (sending is paused) before this went wrong. */
    data class Problem(val problem: VaultProblem, val serverOpened: Boolean = false) : VaultOutcome<Nothing>
}

/**
 * The PIN Vault (2026-11-11): the Circle wallet PIN (and optional security answers) sealed on this phone with a key
 * from the person's circaso.com passkey ([PasskeyPrf]); the server keeps only the sealed bytes. Saving makes a passkey
 * the first time and reuses it after.
 *
 * Opening asks the passkey first and the server second: the server counts `GET /v1/vault` as an opening (it logs it,
 * emails the person and pauses sending for 24 hours), so backing out of Face ID must never reach it. That works because
 * the PRF input is a fixed [PrfSalt] (the passkey's PRF secret is already unique to it), and the credential id comes from
 * the status. The vault stores the salt it used, so a later version can change it.
 *
 * [known] is the last status seen (also kept in [readCache]/[writeCache]), so screens paint at once and agree.
 */
@OptIn(ExperimentalEncodingApi::class)
class VaultRepository(
    private val remote: VaultRemote,
    private val passkeys: PasskeyPrf,
    private val crypto: SignalCrypto,
    private val userId: () -> String?,
    private val newIdempotencyKey: () -> String,
    private val readCache: suspend () -> String? = { null },
    private val writeCache: suspend (String?) -> Unit = {},
) {
    private val sealer = VaultCrypto(crypto)
    private val knownStatus = MutableStateFlow<VaultStatus?>(null)
    val known: StateFlow<VaultStatus?> = knownStatus
    private var cacheRead = false

    /** The cached status from the last visit, once per app run. */
    suspend fun warm() {
        if (cacheRead) return
        cacheRead = true
        if (knownStatus.value != null) return
        val cached = readCache() ?: return
        knownStatus.value = VaultStatus(exists = cached.startsWith("1"), version = null, updatedAt = cached.substringAfter('|', "").ifEmpty { null })
    }

    private suspend fun remember(status: VaultStatus) {
        knownStatus.value = status
        writeCache((if (status.exists) "1" else "0") + "|" + (status.updatedAt ?: ""))
    }

    suspend fun supported(): Boolean = runCatching { passkeys.isSupported() }.getOrDefault(false)

    suspend fun status(): AppResult<VaultStatus> = when (val r = remote.status()) {
        is AppResult.Success -> VaultStatus(r.value.exists, r.value.version, r.value.updatedAt).let { remember(it); AppResult.Success(it) }
        is AppResult.Failure -> r
    }

    /**
     * Seals [contents] and saves it. With no vault yet a passkey is made ([handle] and [displayName] label it in the
     * person's password manager); with one, its passkey is used again and the vault replaced.
     */
    suspend fun save(contents: VaultContents, handle: String, displayName: String): VaultOutcome<Unit> {
        val person = userId() ?: return VaultOutcome.Problem(VaultProblem.Server(AppError.Unexpected(401)))
        val existing = when (val s = remote.status()) {
            is AppResult.Success -> s.value
            is AppResult.Failure -> return VaultOutcome.Problem(VaultProblem.Server(s.error))
        }
        val salt = PrfSalt
        val (credentialId, secret) = when (val credential = existing.credentialId?.let { decodeUrl(it) }) {
            null -> when (val made = passkeys.create(person, handle, displayName, salt)) {
                is PrfResult.Success -> made.credentialId to (made.secret ?: when (val again = passkeys.secret(made.credentialId, salt)) {
                    is PrfResult.Success -> again.secret ?: return VaultOutcome.Problem(VaultProblem.Unsupported)
                    else -> return VaultOutcome.Problem(again.problem())
                })
                else -> return VaultOutcome.Problem(made.problem())
            }
            else -> when (val used = passkeys.secret(credential, salt)) {
                is PrfResult.Success -> credential to (used.secret ?: return VaultOutcome.Problem(VaultProblem.Unsupported))
                else -> return VaultOutcome.Problem(used.problem())
            }
        }
        val sealed = try { sealer.seal(secret, person, contents) } finally { secret.fill(0) }
        val request = SaveVaultRequest(
            credentialId = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(credentialId),
            salt = Base64.encode(salt),
            ciphertext = Base64.encode(sealed),
            algorithm = VaultCrypto.Algorithm,
            expectedVersion = existing.version,
        )
        return when (val r = remote.save(request, newIdempotencyKey())) {
            is AppResult.Success -> { remember(VaultStatus(r.value.exists, r.value.version, r.value.updatedAt)); VaultOutcome.Done(Unit) }
            is AppResult.Failure -> VaultOutcome.Problem(VaultProblem.Server(r.error))
        }
    }

    /** Opens the vault: the passkey first, then the server's sealed copy (an opening: sending pauses for 24 hours). */
    suspend fun open(): VaultOutcome<VaultContents> {
        val person = userId() ?: return VaultOutcome.Problem(VaultProblem.Server(AppError.Unexpected(401)))
        val status = when (val r = remote.status()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return VaultOutcome.Problem(VaultProblem.Server(r.error))
        }
        val credential = status.credentialId?.takeIf { status.exists }?.let { runCatching { decodeUrl(it) }.getOrNull() }
            ?: return VaultOutcome.Problem(VaultProblem.NoVault).also { remember(VaultStatus(false, null, null)) }
        var secret = when (val r = passkeys.secret(credential, PrfSalt)) {
            is PrfResult.Success -> r.secret ?: return VaultOutcome.Problem(VaultProblem.Unsupported)
            else -> return VaultOutcome.Problem(r.problem())
        }
        val dto = when (val r = remote.open()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> {
                secret.fill(0)
                return VaultOutcome.Problem(if ((r.error as? AppError.Api)?.code == "vault.not_found") VaultProblem.NoVault else VaultProblem.Server(r.error))
            }
        }
        val sealed = runCatching { Base64.decode(dto.ciphertext) }.getOrNull()
        val salt = runCatching { Base64.decode(dto.salt) }.getOrNull()
        if (sealed == null || salt == null) { secret.fill(0); return VaultOutcome.Problem(VaultProblem.WrongPasskey, serverOpened = true) }
        if (!salt.contentEquals(PrfSalt)) {
            // Saved under another salt (a later version): one more passkey answer for that salt.
            secret.fill(0)
            secret = when (val r = passkeys.secret(credential, salt)) {
                is PrfResult.Success -> r.secret ?: return VaultOutcome.Problem(VaultProblem.Unsupported, serverOpened = true)
                else -> return VaultOutcome.Problem(r.problem(), serverOpened = true)
            }
        }
        val contents = try { sealer.open(secret, person, sealed) } finally { secret.fill(0) }
        return contents?.let { VaultOutcome.Done(it) } ?: VaultOutcome.Problem(VaultProblem.WrongPasskey, serverOpened = true)
    }

    suspend fun remove(): AppResult<Unit> = when (val r = remote.remove()) {
        is AppResult.Success -> { remember(VaultStatus(false, null, null)); AppResult.Success(Unit) }
        is AppResult.Failure -> r
    }

    companion object {
        /** The PRF input every vault is sealed under (v1). Not secret: the passkey's own PRF key makes the output unique. */
        val PrfSalt: ByteArray = "circaso-vault/prf/v1".encodeToByteArray().copyOf(32)
    }

    private fun decodeUrl(value: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value)

    private fun PrfResult.problem(): VaultProblem = when (this) {
        PrfResult.Cancelled -> VaultProblem.Cancelled
        PrfResult.Unsupported -> VaultProblem.Unsupported
        is PrfResult.Failed -> VaultProblem.Passkey(reason)
        is PrfResult.Success -> VaultProblem.Unsupported
    }
}
