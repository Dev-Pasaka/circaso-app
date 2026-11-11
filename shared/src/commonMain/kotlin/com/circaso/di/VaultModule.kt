package com.circaso.di

import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.session.AuthManager
import com.circaso.feature.vault.data.KtorVaultRemote
import com.circaso.feature.vault.data.VaultRemote
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.presentation.VaultViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The PIN Vault (2026-11-11): the Circle PIN sealed with a passkey; the platform module provides `PasskeyPrf`. */
val vaultModule = module {
    single<VaultRemote> { KtorVaultRemote(get()) }
    single { VaultRepository(get(), get(), get(), userId = { get<AuthManager>().userId }, newIdempotencyKey = ::newIdempotencyKey) }
    viewModel { VaultViewModel(get(), get(), get(), getOrNull()) }
}
