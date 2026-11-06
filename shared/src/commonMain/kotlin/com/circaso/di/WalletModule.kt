package com.circaso.di

import kotlinx.coroutines.flow.map
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.platform.localCurrencyCode
import com.circaso.feature.wallet.data.remote.KtorWalletRemote
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.AddChainUseCase
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.CreateWalletUseCase
import com.circaso.feature.wallet.domain.GetChainsUseCase
import com.circaso.feature.wallet.domain.LoadTransferUseCase
import com.circaso.feature.wallet.domain.NoWalletSigner
import com.circaso.feature.wallet.domain.ObserveTransferUseCase
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.ValidateAddressUseCase
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletSignerBridge
import com.circaso.feature.wallet.presentation.ActivityViewModel
import com.circaso.feature.wallet.presentation.NetworksViewModel
import com.circaso.feature.wallet.presentation.PayIntent
import com.circaso.feature.wallet.presentation.PayViewModel
import com.circaso.feature.wallet.presentation.SendToWalletViewModel
import com.circaso.feature.wallet.presentation.TransferViewModel
import com.circaso.feature.wallet.presentation.WalletViewModel
import kotlinx.coroutines.flow.first
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** The wallet (guide W1–W5, W9–W12): reads from Room, money moves only online and signed in the Circle SDK. */
val walletModule = module {
    single { get<CircasoDatabase>().walletDao() }
    single<WalletRemote> { KtorWalletRemote(get(), get()) }
    single { WalletRepositoryImpl(get(), get(), signer = { WalletSignerBridge.signer ?: NoWalletSigner }, currency = { get<com.circaso.core.datastore.AppPreferences>().currency.first() ?: localCurrencyCode() }, json = get(),
        scope = get(),
        onMoneyReceived = {
            if (!get<com.circaso.core.datastore.AppPreferences>().flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first()) com.circaso.core.platform.playMoneySound()
        },
        onBiometricsLost = { walletId ->
            val prefs = get<com.circaso.core.datastore.AppPreferences>()
            prefs.setWalletBiometrics(walletId, false)
            // Only that wallet falls back to its PIN; its row says why (GW8b).
            prefs.setFlag(com.circaso.feature.chama.presentation.GroupInfoMoneySource.lostFlag(walletId), true)
        },
        // Money is never hidden by decryption: a confirmed chat payment always has its card in that chat.
        onChatPayment = { seen -> get<com.circaso.feature.messaging.data.repository.ChatPaymentCards>().fromWallet(seen) },
    ) }
    single<WalletRepository> { get<WalletRepositoryImpl>() }
    factory { ObserveWalletUseCase(get()) }
    factory { RefreshWalletUseCase(get()) }
    factory { CreateWalletUseCase(get()) }
    factory { ObserveTransfersUseCase(get()) }
    factory { ObserveTransferUseCase(get()) }
    factory { LoadTransferUseCase(get()) }
    factory { GetChainsUseCase(get()) }
    factory { AddChainUseCase(get()) }
    factory { CheckRecipientUseCase(get()) }
    factory { RemindRecipientUseCase(get()) }
    factory { ValidateAddressUseCase(get()) }
    factory { PayUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.QuotePaymentUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.AddMissingChainsUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.ObserveWalletsUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.RefreshWalletsUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.ObserveWalletOfUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.RefreshWalletOfUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.ObserveWalletActivityUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.RefreshWalletActivityUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.GetGroupWalletUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.StartGroupWalletUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.AwaitGroupWalletUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.AddWalletNetworksUseCase(get()) }
    viewModel { com.circaso.feature.account.presentation.BiometricApprovalViewModel(get(), get(), get(), get()) }
    viewModel { WalletViewModel(get(), get(), get(), get(), get(), get(), get(), getChains = get(), addMissingChains = get(), groupWallets = get(), sources = get(), groupReader = get(), privacy = get(),
        choices = get<com.circaso.core.datastore.AppPreferences>().let { p -> com.circaso.feature.wallet.presentation.WalletChoiceStore.of(p::text, p::setText) }) }
    single { com.circaso.feature.wallet.presentation.BalancePrivacy() }
    viewModel { com.circaso.feature.wallet.presentation.CurrencyViewModel(get(), get()) }
    viewModel { (blockchain: String?, symbol: String?, walletId: String?) -> ActivityViewModel(get(), get(), blockchain, symbol, walletId, get(), get()) }
    viewModel { (blockchain: String, walletId: String?) -> com.circaso.feature.wallet.presentation.NetworkAssetsViewModel(get(), blockchain, walletId, get()) }
    viewModel { (transferId: String) -> TransferViewModel(transferId, get(), get(), get(), get()) }
    viewModel { NetworksViewModel(get(), get(), get()) }
    viewModel { (intent: PayIntent) ->
        val profiles = get<com.circaso.feature.profile.domain.ProfileRepository>()
        val refreshWallet = get<RefreshWalletUseCase>()
        val refreshWalletOf = get<com.circaso.feature.wallet.domain.RefreshWalletOfUseCase>()
        PayViewModel(intent, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), scanner = get(), findHandle = { profiles.findByHandle(it) }, groupWallets = get(), wallets = get(), observeTransfer = get(), quotePayment = get(),
            refreshPrices = { id -> if (id == null) refreshWallet() else refreshWalletOf(id) },
            observeMe = profiles.profile.map { p -> p?.let { com.circaso.feature.contacts.domain.Person(it.userId, it.displayName, it.handle, it.avatarUrl, it.about) } })
    }
    factory {
        com.circaso.feature.wallet.presentation.GroupWalletSources(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
    viewModel { (fromWalletId: String?) -> SendToWalletViewModel(get(), get(), get(), get(), scanner = get(), fromWalletId = fromWalletId, wallets = get(), quotePayment = get(), sendCard = get()) }
}
