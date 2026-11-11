package com.circaso.feature.vault.data

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.feature.vault.domain.VaultContents
import com.circaso.feature.vault.domain.VaultStatus
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
    data class Problem(val problem: VaultProblem) : VaultOutcome<Nothing>
}

/**
 * The PIN Vault (2026-11-11): the Circle wallet PIN (and optional security answers) sealed on this phone with a key
 * from the person's circaso.com passkey ([PasskeyPrf]); the server keeps only the sealed bytes. Saving makes a passkey
 * the first time and reuses it after; opening asks the server for the sealed bytes (which it logs, tells the person
 * about and follows with a pause on sending) and the passkey for its secret.
 */
@OptIn(ExperimentalEncodingApi::class)
class VaultRepository(
    private val remote: VaultRemote,
    private val passkeys: PasskeyPrf,
    private val crypto: SignalCrypto,
    private val userId: () -> String?,
    private val newIdempotencyKey: () -> String,
) {
    private val sealer = VaultCrypto(crypto)

    suspend fun supported(): Boolean = runCatching { passkeys.isSupported() }.getOrDefault(false)

    suspend fun status(): AppResult<VaultStatus> = when (val r = remote.status()) {
        is AppResult.Success -> AppResult.Success(VaultStatus(r.value.exists, r.value.version, r.value.updatedAt))
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
        val salt = crypto.randomBytes(32)
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
            is AppResult.Success -> VaultOutcome.Done(Unit)
            is AppResult.Failure -> VaultOutcome.Problem(VaultProblem.Server(r.error))
        }
    }

    /** Opens the vault: the server's sealed copy, then the passkey. Sending pauses for a while after (server side). */
    suspend fun open(): VaultOutcome<VaultContents> {
        val person = userId() ?: return VaultOutcome.Problem(VaultProblem.Server(AppError.Unexpected(401)))
        val dto = when (val r = remote.open()) {
            is AppResult.Success -> r.value
            is AppResult.Failure -> return VaultOutcome.Problem(
                if ((r.error as? AppError.Api)?.code == "vault.not_found") VaultProblem.NoVault else VaultProblem.Server(r.error),
            )
        }
        val credential = runCatching { decodeUrl(dto.credentialId) }.getOrNull() ?: return VaultOutcome.Problem(VaultProblem.WrongPasskey)
        val salt = runCatching { Base64.decode(dto.salt) }.getOrNull() ?: return VaultOutcome.Problem(VaultProblem.WrongPasskey)
        val sealed = runCatching { Base64.decode(dto.ciphertext) }.getOrNull() ?: return VaultOutcome.Problem(VaultProblem.WrongPasskey)
        val secret = when (val r = passkeys.secret(credential, salt)) {
            is PrfResult.Success -> r.secret ?: return VaultOutcome.Problem(VaultProblem.Unsupported)
            else -> return VaultOutcome.Problem(r.problem())
        }
        val contents = try { sealer.open(secret, person, sealed) } finally { secret.fill(0) }
        return contents?.let { VaultOutcome.Done(it) } ?: VaultOutcome.Problem(VaultProblem.WrongPasskey)
    }

    suspend fun remove(): AppResult<Unit> = when (val r = remote.remove()) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> r
    }

    private fun decodeUrl(value: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value)

    private fun PrfResult.problem(): VaultProblem = when (this) {
        PrfResult.Cancelled -> VaultProblem.Cancelled
        PrfResult.Unsupported -> VaultProblem.Unsupported
        is PrfResult.Failed -> VaultProblem.Passkey(reason)
        is PrfResult.Success -> VaultProblem.Unsupported
    }
}
