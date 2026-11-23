package com.circaso.core.platform

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult

/**
 * The account's one circaso.com passkey (2026-11-22). The PIN Vault and the group key backup both seal under it.
 *
 * A password manager keeps one passkey per site and person: making a second one replaces the first, and everything
 * sealed under the first can then never be opened. So nothing makes a passkey on its own. Both features ask here, and
 * the rule lives only here:
 *
 * 1. A feature whose own server record names a passkey uses that one.
 * 2. Otherwise the first passkey another record names: a PIN Vault's ([vaults], every wallet network), then the group
 *    key backup root's ([backupRoot]).
 * 3. A passkey is made only when no record names one, and never while a record couldn't be read.
 */
class AccountPasskey(
    private val passkeys: PasskeyPrf,
    /**
     * The passkey each PIN Vault of this account names, the selected network's first. A failure when a vault's record
     * couldn't be read or understood: not knowing is never taken as "there is none".
     */
    private val vaults: suspend () -> AppResult<List<ByteArray>> = { AppResult.Success(emptyList()) },
    /** The passkey the group key backup root is sealed under, null when backup is off; a failure as for [vaults]. */
    private val backupRoot: suspend () -> AppResult<ByteArray?> = { AppResult.Success(null) },
) {
    sealed interface Answer {
        /** [created]: this passkey was made just now. The caller owns [secret] and wipes it. */
        class Ready(val credentialId: ByteArray, val secret: ByteArray, val created: Boolean) : Answer
        /** The passkey didn't answer: [result] is never a [PrfResult.Success]. */
        data class NoAnswer(val result: PrfResult) : Answer
        /** The server's records couldn't be read, so no passkey was asked or made. */
        data class Unknown(val error: AppError) : Answer
    }

    /** The passkey the account's vaults name (the first of them), without a prompt; null when none does or it isn't known. */
    suspend fun vaultPasskey(): ByteArray? = (vaults() as? AppResult.Success)?.value?.firstOrNull()

    /**
     * The PRF secret for [prfSalt] from the account's passkey: [own] (the asking feature's record) when it has one,
     * else the first the other records name, else a new passkey labelled [userName] and [displayName].
     *
     * [withoutBackupRoot]: the person is deliberately starting a new backup, so the old root's passkey doesn't count.
     * A vault's still does, even when it is the same passkey: nothing is ever made over a passkey a vault names.
     */
    suspend fun secret(own: ByteArray?, userId: String, userName: String, displayName: String, prfSalt: ByteArray, withoutBackupRoot: Boolean = false): Answer {
        val credential = own ?: when (val v = vaults()) {
            is AppResult.Failure -> return Answer.Unknown(v.error)
            is AppResult.Success -> v.value.firstOrNull() ?: if (withoutBackupRoot) null else when (val r = backupRoot()) {
                is AppResult.Failure -> return Answer.Unknown(r.error)
                is AppResult.Success -> r.value
            }
        }
        if (credential != null) return when (val used = passkeys.secret(credential, prfSalt)) {
            is PrfResult.Success -> used.secret?.let { Answer.Ready(used.credentialId, it, created = false) } ?: Answer.NoAnswer(PrfResult.Unsupported)
            else -> Answer.NoAnswer(used)
        }
        return when (val made = passkeys.create(userId, userName, displayName, prfSalt)) {
            is PrfResult.Success -> {
                // A provider that gives no secret while creating is asked once more, for the passkey just made.
                val secret = made.secret ?: when (val again = passkeys.secret(made.credentialId, prfSalt)) {
                    is PrfResult.Success -> again.secret ?: return Answer.NoAnswer(PrfResult.Unsupported)
                    else -> return Answer.NoAnswer(again)
                }
                Answer.Ready(made.credentialId, secret, created = true)
            }
            else -> Answer.NoAnswer(made)
        }
    }
}
