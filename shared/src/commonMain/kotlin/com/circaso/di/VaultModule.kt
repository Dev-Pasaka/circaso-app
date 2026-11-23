package com.circaso.di

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.AccountPasskey
import com.circaso.core.session.AuthManager
import com.circaso.feature.vault.data.KtorVaultRemote
import com.circaso.feature.vault.data.VaultRemote
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.presentation.VaultViewModel
import org.koin.core.module.dsl.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.koin.dsl.module

/** Where the last vault status is kept (`text.vault.status`), cleared on sign-out with the account's other data. */
const val VaultStatusText = "vault.status"

private fun vaultStatusText(networks: com.circaso.feature.wallet.domain.WalletNetworks) =
    if (networks.current() == com.circaso.feature.wallet.domain.WalletNetwork.MAINNET) "$VaultStatusText.mainnet" else VaultStatusText

/** The PIN Vault (2026-11-11): the Circle PIN sealed with a passkey; the platform module provides `PasskeyPrf`. */
@OptIn(ExperimentalEncodingApi::class)
val vaultModule = module {
    single<VaultRemote> { KtorVaultRemote(get(), get()) }
    // The account's one passkey: what the vault (on every wallet network) and the group key backup root already name,
    // so neither feature makes a second passkey that would replace the first in the password manager.
    single {
        val networks = get<com.circaso.feature.wallet.domain.WalletNetworks>()
        fun id(exists: Boolean, credentialId: String?): AppResult<ByteArray?> = when {
            !exists -> AppResult.Success(null)
            else -> credentialId?.let { runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(it) }.getOrNull() }
                ?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Unexpected(null))
        }
        AccountPasskey(
            passkeys = get(),
            vaults = vaults@{
                val remote = get<VaultRemote>()
                val selected = networks.current()
                // The selected network's vault first, then each other network the person has (its own vault slot).
                val asked = listOf<com.circaso.feature.wallet.domain.WalletNetwork?>(null) + networks.state.value.options.map { it.network }.filter { it != selected }
                val found = mutableListOf<ByteArray>()
                for (network in asked) {
                    val status = if (network == null) remote.status() else withContext(com.circaso.feature.wallet.domain.PinnedNetwork(network)) { remote.status() }
                    when (status) {
                        is AppResult.Failure -> return@vaults status
                        is AppResult.Success -> when (val c = id(status.value.exists, status.value.credentialId)) {
                            is AppResult.Failure -> return@vaults c
                            is AppResult.Success -> c.value?.let(found::add)
                        }
                    }
                }
                AppResult.Success(found)
            },
            backupRoot = {
                when (val r = get<com.circaso.feature.keybackup.data.KeyBackupRemote>().root()) {
                    is AppResult.Failure -> r
                    is AppResult.Success -> id(r.value.exists, r.value.credentialId)
                }
            },
        )
    }
    single {
        val prefs = get<com.circaso.core.datastore.AppPreferences>()
        VaultRepository(
            get(), get(), get(), userId = { get<AuthManager>().userId }, newIdempotencyKey = ::newIdempotencyKey,
            // One remembered status per wallet network; Testnet keeps the name from before networks.
            readCache = { prefs.text(vaultStatusText(get())).first() }, writeCache = { prefs.setText(vaultStatusText(get()), it) },
            network = { get<com.circaso.feature.wallet.domain.WalletNetworks>().current().wire },
            // The group key backup turns on with the vault's passkey answer: no second prompt.
            afterSecret = { id, secret -> get<com.circaso.feature.keybackup.data.KeyBackupRepository>().onPrfSecret(id, secret) },
            account = get(),
        )
    }
    viewModel { VaultViewModel(get(), get(), get(), getOrNull(), wallet = getOrNull()) }
}
