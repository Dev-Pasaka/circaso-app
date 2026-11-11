package com.circaso.di

import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.session.AuthManager
import com.circaso.feature.vault.data.KtorVaultRemote
import com.circaso.feature.vault.data.VaultRemote
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.presentation.VaultViewModel
import org.koin.core.module.dsl.viewModel
import kotlinx.coroutines.flow.first
import org.koin.dsl.module

/** Where the last vault status is kept (`text.vault.status`), cleared on sign-out with the account's other data. */
const val VaultStatusText = "vault.status"

/** The PIN Vault (2026-11-11): the Circle PIN sealed with a passkey; the platform module provides `PasskeyPrf`. */
val vaultModule = module {
    single<VaultRemote> { KtorVaultRemote(get()) }
    single {
        val prefs = get<com.circaso.core.datastore.AppPreferences>()
        VaultRepository(
            get(), get(), get(), userId = { get<AuthManager>().userId }, newIdempotencyKey = ::newIdempotencyKey,
            readCache = { prefs.text(VaultStatusText).first() }, writeCache = { prefs.setText(VaultStatusText, it) },
        )
    }
    viewModel { VaultViewModel(get(), get(), get(), getOrNull(), wallet = getOrNull()) }
}
