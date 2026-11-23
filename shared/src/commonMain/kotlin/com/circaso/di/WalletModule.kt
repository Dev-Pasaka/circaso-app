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
    // Wallet networks (2026-11-18): one source of truth for the selected network, kept in two small settings that are
    // wiped at sign-out with the rest of `text.wallet.*`.
    single<com.circaso.feature.wallet.domain.WalletNetworks> {
        val prefs = get<com.circaso.core.datastore.AppPreferences>()
        com.circaso.feature.wallet.data.repository.WalletNetworksStore(
            load = { get<WalletRemote>().networks() },
            saved = kotlinx.coroutines.flow.combine(
                prefs.text(com.circaso.core.datastore.AppPreferences.WalletNetworkText), prefs.text(com.circaso.core.datastore.AppPreferences.WalletNetworksText),
            ) { selected, list -> com.circaso.feature.wallet.data.repository.SavedNetworks(selected, list) },
            save = { texts -> prefs.setWalletNetworks(texts.selected, texts.list) },
            scope = get(),
        )
    }
    // What every wallet-scoped remote (wallet, vault, chama, Asha's turn) asks for `X-Wallet-Network`.
    single<com.circaso.core.network.WalletNetworkGate> {
        val networks = get<com.circaso.feature.wallet.domain.WalletNetworks>()
        object : com.circaso.core.network.WalletNetworkGate {
            override suspend fun header(): String? = networks.header()
            override suspend fun refused(sent: String?) = networks.onRefused(sent)
        }
    }
    // The money sounds (the coins, a top-up's chime): one setting, You → Money sound; each top-up's chime once.
    single {
        val prefs = get<com.circaso.core.datastore.AppPreferences>()
        com.circaso.core.platform.MoneySounds(
            chime = getOrNull<com.circaso.core.platform.PaymentChime>() ?: com.circaso.core.platform.NoPaymentChime,
            coins = { com.circaso.core.platform.playMoneySound() },
            soundOn = { !prefs.flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first() },
            rung = { prefs.text(com.circaso.core.datastore.AppPreferences.RampChimedText).first() },
            saveRung = { prefs.setText(com.circaso.core.datastore.AppPreferences.RampChimedText, it) },
        )
    }
    single<WalletRemote> { KtorWalletRemote(get(), get(), get()) }
    single { WalletRepositoryImpl(get(), get(), signer = { WalletSignerBridge.signer ?: NoWalletSigner }, currency = { get<com.circaso.core.datastore.AppPreferences>().currency.first() ?: localCurrencyCode() }, json = get(),
        scope = get(),
        // The coins, unless a top-up's chime has this money (MoneySounds).
        onMoneyReceived = { get<com.circaso.core.platform.MoneySounds>().moneyReceived() },
        onBiometricsLostOn = { walletId, network ->
            val prefs = get<com.circaso.core.datastore.AppPreferences>()
            prefs.setWalletBiometrics(walletId, false, network.wire)
            // Only that wallet (on that network) falls back to its PIN; its row says why (GW8b).
            prefs.setWalletBiometricsLost(walletId, true, network.wire)
        },
        networks = get(),
        // Money is never hidden by decryption: a confirmed chat payment always has its card in that chat.
        onChatPayment = { seen -> get<com.circaso.feature.messaging.data.repository.ChatPaymentCards>().fromWallet(seen) },
        names = get(),
    ) }
    single<WalletRepository> { get<WalletRepositoryImpl>() }
    // Group wallets and networks (2026-11-19): which network each group's money is on, from the per-network W16 copies.
    single {
        com.circaso.feature.wallet.domain.GroupNetworks(
            networks = get(), listingsOn = { network -> get<WalletRepository>().walletsOn(network) },
            refreshListings = { if (get<com.circaso.core.flags.GroupWalletsFlag>().isOn) get<WalletRepository>().refreshWallets() else com.circaso.core.common.AppResult.Success(Unit) },
            scope = get(),
        )
    }
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
    factory { com.circaso.feature.wallet.domain.LoadActivityPageUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.GetGroupWalletUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.StartGroupWalletUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.AwaitGroupWalletUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase(get()) }
    factory { com.circaso.feature.wallet.domain.AddWalletNetworksUseCase(get()) }
    viewModel { com.circaso.feature.account.presentation.BiometricApprovalViewModel(get(), get(), get(), get(), networks = get()) }
    viewModel { WalletViewModel(get(), get(), get(), get(), get(), get(), getChains = get(), addMissingChains = get(), groupWallets = get(), sources = get(), groupReader = get(), privacy = get(),
        choices = get<com.circaso.core.datastore.AppPreferences>().let { p -> com.circaso.feature.wallet.presentation.WalletChoiceStore.of(p::text, p::setText) },
        networks = get(), groupNetworks = get()) }
    single { com.circaso.feature.wallet.presentation.BalancePrivacy() }
    viewModel { com.circaso.feature.wallet.presentation.CurrencyViewModel(get(), get()) }
    viewModel { (blockchain: String?, symbol: String?, walletId: String?) -> ActivityViewModel(get(), get(), blockchain, symbol, walletId, get(), get(), get(), loadPage = get(), connection = get(),
        // A group wallet is on one network: its payments are that network's, whatever is selected.
        network = walletId?.let { get<com.circaso.feature.wallet.domain.GroupNetworks>().ofWalletNow(it) }) }
    viewModel { (blockchain: String, walletId: String?) -> com.circaso.feature.wallet.presentation.NetworkAssetsViewModel(get(), blockchain, walletId, get(),
        network = walletId?.let { get<com.circaso.feature.wallet.domain.GroupNetworks>().ofWalletNow(it) }) }
    viewModel { (transferId: String) -> TransferViewModel(transferId, get(), get(), get(), get()) }
    viewModel { NetworksViewModel(get(), get(), get()) }
    viewModel { (intent: PayIntent) ->
        val profiles = get<com.circaso.feature.profile.domain.ProfileRepository>()
        val refreshWallet = get<RefreshWalletUseCase>()
        val refreshWalletOf = get<com.circaso.feature.wallet.domain.RefreshWalletOfUseCase>()
        PayViewModel(intent, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), scanner = get(), findHandle = { profiles.findByHandle(it) }, groupWallets = get(), wallets = get(), observeTransfer = get(), quotePayment = get(),
            refreshPrices = { id -> if (id == null) refreshWallet() else refreshWalletOf(id) },
            observeMe = profiles.profile.map { p -> p?.let { com.circaso.feature.contacts.domain.Person(it.userId, it.displayName, it.handle, it.avatarUrl, it.about) } },
            networks = get(), groupNetworks = get())
    }
    factory {
        com.circaso.feature.wallet.presentation.GroupWalletSources(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
        )
    }
    viewModel { (fromWalletId: String?) -> SendToWalletViewModel(get(), get(), get(), get(), scanner = get(), fromWalletId = fromWalletId, wallets = get(), quotePayment = get(), sendCard = get(), networks = get(), groupNetworks = get()) }
}
