package com.circaso.di

import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.SecureStorage
import com.circaso.core.session.AuthManager
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.account.domain.AccountRepository
import com.circaso.feature.ramp.data.remote.KtorRampRemote
import com.circaso.feature.ramp.data.remote.RampRemote
import com.circaso.feature.ramp.data.repository.RampRepositoryImpl
import com.circaso.feature.ramp.data.repository.SecureRampPhoneStore
import com.circaso.feature.ramp.domain.BuyWithMpesaUseCase
import com.circaso.feature.ramp.domain.CancelRampOrderUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveTopUpsUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampPhoneStore
import com.circaso.feature.ramp.domain.RampRepository
import com.circaso.feature.ramp.presentation.AddMoneyViewModel
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The ramp: buying USDC with M-Pesa (guide R1–R6, design E2/E3). Money: online-only, never queued. Its calls carry
 * `X-Wallet-Network` from the wallet's own gate.
 */
val rampModule = module {
    single<RampRemote> { KtorRampRemote(get(), get()) }
    single<RampPhoneStore> { val auth = get<AuthManager>(); SecureRampPhoneStore(get<SecureStorage>()) { auth.userId } }
    single<RampRepository> {
        val prefs = get<AppPreferences>()
        RampRepositoryImpl(
            remote = get(), networks = get(), phones = get(), json = get(), scope = get(),
            // Under `text.wallet.`: wiped at sign-out with the rest of the wallet's small settings.
            savedTopUps = { network -> prefs.text(RampRepositoryImpl.topUpsKey(network)) },
            saveTopUps = { network, text -> prefs.setText(RampRepositoryImpl.topUpsKey(network), text) },
            // The USDC arrived: the balance and the history are read again for that network.
            onArrived = { get<WalletRepository>().refresh() },
        )
    }
    factory { GetRampStatusUseCase(get()) }
    factory { QuoteTopUpUseCase(get()) }
    factory { BuyWithMpesaUseCase(get()) }
    factory { GetRampOrderUseCase(get()) }
    factory { CancelRampOrderUseCase(get()) }
    factory { ObserveRampUpdatesUseCase(get()) }
    factory { ObserveTopUpsUseCase(get()) }
    factory { GetOwnMpesaNumberUseCase(get()) }
    viewModel { (orderId: String?) ->
        AddMoneyViewModel(
            orderId, get(), get(), get(), get(), get(), get(), get(),
            maskedPhone = get<AccountRepository>().overview.map { it?.maskedPhone },
            online = get<ConnectionState>().status.map { it == ConnectionStatus.Online },
            networks = get(),
        )
    }
}
