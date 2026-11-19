package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.feature.chama.data.remote.ChamaRemote
import com.circaso.feature.chama.data.remote.KtorChamaRemote
import com.circaso.feature.chama.data.repository.ChamaRepositoryImpl
import com.circaso.feature.chama.domain.AttachChamaPaymentUseCase
import com.circaso.feature.chama.domain.ChamaRepository
import com.circaso.feature.chama.domain.CreateChamaRequestUseCase
import com.circaso.feature.chama.domain.DecideChamaRequestUseCase
import com.circaso.feature.chama.domain.LoadLedgerUseCase
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import com.circaso.feature.chama.domain.RefreshChamaUseCase
import com.circaso.feature.chama.domain.SetGoalUseCase
import com.circaso.feature.chama.presentation.ChamaViewModel
import com.circaso.feature.chama.presentation.ThreadChama
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Chamas (guide CH1–CH6, designs D2–D6): the last server view per group is kept in Room; every write is online-only. */
val chamaModule = module {
    single { get<CircasoDatabase>().chamaDao() }
    single<ChamaRemote> { KtorChamaRemote(get(), get()) }
    single {
        com.circaso.feature.chama.data.repository.GroupMoneyLive(
            get(), get(), scope = get(), groupWallets = { get<com.circaso.core.flags.GroupWalletsFlag>().isOn },
        )
    }
    single { ChamaRepositoryImpl(get(), get(), get(), links = get<com.circaso.core.datastore.AppPreferences>(), scope = get(), groupWallets = { get<com.circaso.core.flags.GroupWalletsFlag>().isOn }, networks = get()) }
    single<ChamaRepository> { get<ChamaRepositoryImpl>() }
    single { com.circaso.feature.chama.data.repository.ContributionFeed() } // contributions landing live (a group call)
    factory { ObserveChamaUseCase(get()) }
    factory { RefreshChamaUseCase(get()) }
    factory { SetGoalUseCase(get()) }
    factory { LoadLedgerUseCase(get()) }
    factory { CreateChamaRequestUseCase(get()) }
    factory { DecideChamaRequestUseCase(get()) }
    factory { AttachChamaPaymentUseCase(get()) }
    factory { com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase(get()) }
    factory { com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase(get()) }
    factory { com.circaso.feature.chama.domain.SetApprovalPolicyUseCase(get()) }
    factory { com.circaso.feature.chama.domain.RemoveGoalUseCase(get()) }
    factory { ThreadChama(get(), get(), get(), get(), get(), get(), groupWallets = get(), observeMoney = get(), refreshMoney = get(), quotePayment = get(), observeWalletOf = get(), groupNetworks = get()) }
    // Group wallets (phase 2): group info's money rows, the wallet set-up (GW2–GW5) and the target form (GW7b).
    factory {
        val bio = get<com.circaso.core.platform.Biometrics>()
        com.circaso.feature.chama.presentation.GroupInfoMoneySource(get(), get(), get(), get(), get(), get(), get(), get(), biometricLabel = bio.name.takeIf { bio.available() }, networks = get(), groupNetworks = get())
    }
    viewModel { (chatId: String, plan: com.circaso.feature.chama.presentation.GroupWalletPlan, network: com.circaso.feature.wallet.domain.WalletNetwork?) ->
        val bio = get<com.circaso.core.platform.Biometrics>()
        val places = get<com.circaso.feature.wallet.domain.GroupNetworks>()
        com.circaso.feature.chama.presentation.GroupWalletSetupViewModel(
            chatId, plan, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), biometricLabel = bio.name.takeIf { bio.available() }, networks = get(),
            // Never picked on screen: the route's network, else the rule's (Mainnet whenever the person may use it).
            network = network ?: places.now(chatId), namesNetwork = places.state.value.both, refreshGroupNetworks = { places.refresh() },
        )
    }
    factory { com.circaso.feature.chama.presentation.GroupWalletReader(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    // Every group money screen works for the group's own network (the navigation entry passes it and keys on it).
    viewModel { (chatId: String, network: com.circaso.feature.wallet.domain.WalletNetwork?) -> com.circaso.feature.chama.presentation.GroupWalletViewModel(chatId, get(), get(), get(), network) }
    viewModel { (chatId: String, walletReady: Boolean, network: com.circaso.feature.wallet.domain.WalletNetwork?) -> com.circaso.feature.chama.presentation.TargetViewModel(chatId, walletReady, get(), get(), get(), get(), get(), get(), get(), network) }
    viewModel { (chatId: String, network: com.circaso.feature.wallet.domain.WalletNetwork?) -> ChamaViewModel(chatId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), network) }
}
