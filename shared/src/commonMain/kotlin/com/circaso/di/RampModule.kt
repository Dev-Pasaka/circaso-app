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
import com.circaso.feature.ramp.domain.GetKycUseCase
import com.circaso.feature.ramp.domain.GetOwnMpesaNumberUseCase
import com.circaso.feature.ramp.domain.GetRampOrderUseCase
import com.circaso.feature.ramp.domain.GetRampStatusUseCase
import com.circaso.feature.ramp.domain.IdentityVerifierBridge
import com.circaso.feature.ramp.domain.NoIdentityVerifier
import com.circaso.feature.ramp.domain.ObserveIdentityUseCase
import com.circaso.feature.ramp.domain.ObserveKycUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveRampUpdatesUseCase
import com.circaso.feature.ramp.domain.ObserveTopUpsUseCase
import com.circaso.feature.ramp.domain.QuoteTopUpUseCase
import com.circaso.feature.ramp.domain.RampPhoneStore
import com.circaso.feature.ramp.domain.RampRepository
import com.circaso.feature.ramp.domain.RunIdentityCheckUseCase
import com.circaso.feature.ramp.domain.StartKycUseCase
import com.circaso.feature.ramp.presentation.AddMoneyViewModel
import com.circaso.feature.ramp.presentation.CashOutViewModel
import com.circaso.feature.ramp.domain.ObserveSalesUseCase
import com.circaso.feature.ramp.domain.QuoteSaleUseCase
import com.circaso.feature.ramp.domain.SaleWallet
import com.circaso.feature.ramp.domain.SellForMpesaUseCase
import com.circaso.feature.ramp.presentation.IdentityViewModel
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.flow.map
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The ramp: buying USDC with M-Pesa (guide R0–R6, design E2/E3). Money: online-only, never queued. Its calls carry
 * `X-Wallet-Network` from the wallet's own gate. Identity verification (R0) runs in Didit's SDK, which each platform
 * installs behind [IdentityVerifierBridge] at launch; a build without it opens Didit's hosted page in the system's
 * in-app browser instead.
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
            // The last known identity check per network, for the profile offline; under `text.wallet.` too, so it is
            // one account's and goes at sign-out.
            savedIdentity = { network -> prefs.text(RampRepositoryImpl.identityKey(network)) },
            saveIdentity = { network, text -> prefs.setText(RampRepositoryImpl.identityKey(network), text) },
            // The sales paid from this phone, per network, for the wallet's "Cashed out to M-Pesa"; under `text.wallet.` too.
            savedSales = { network -> prefs.text(RampRepositoryImpl.salesKey(network)) },
            saveSales = { network, text -> prefs.setText(RampRepositoryImpl.salesKey(network), text) },
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
    factory { QuoteSaleUseCase(get()) }
    factory { SellForMpesaUseCase(get()) }
    factory { ObserveSalesUseCase(get()) }
    // The wallet as a sale needs it: the pinned network's balance, W17 and W4 with the person's approval.
    factory<SaleWallet> {
        val wallet = get<WalletRepository>()
        object : SaleWallet {
            override fun wallet(network: WalletNetwork) = wallet.walletOn(network)
            override suspend fun fee(request: com.circaso.feature.wallet.domain.PaymentRequest) = wallet.quote(request)
            override suspend fun send(request: com.circaso.feature.wallet.domain.PaymentRequest, idempotencyKey: String) = wallet.pay(request, idempotencyKey)
        }
    }
    factory { StartKycUseCase(get()) }
    factory { GetKycUseCase(get()) }
    factory { ObserveKycUpdatesUseCase(get()) }
    factory { ObserveIdentityUseCase(get()) }
    factory { RunIdentityCheckUseCase(verifier = { IdentityVerifierBridge.verifier ?: NoIdentityVerifier }, openPage = { url -> com.circaso.core.platform.openInSystemBrowserAndWait(url) }) }
    viewModel { (orderId: String?, network: String?) ->
        AddMoneyViewModel(
            orderId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            maskedPhone = get<AccountRepository>().overview.map { it?.maskedPhone },
            online = get<ConnectionState>().status.map { it == ConnectionStatus.Online },
            networks = get(),
            network = WalletNetwork.of(network),
        )
    }
    // Cash out (E6): selling USDC for M-Pesa. [orderId] opens on a sale (a push, or the open order).
    viewModel { (orderId: String?, network: String?) ->
        CashOutViewModel(
            orderId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            saleWallet = get(), ramp = get(),
            maskedPhone = get<AccountRepository>().overview.map { it?.maskedPhone },
            online = get<ConnectionState>().status.map { it == ConnectionStatus.Online },
            networks = get(),
            network = WalletNetwork.of(network),
        )
    }
    // You › Verification: the same check, from the profile. [network] is the one a `ramp.kyc` push was about.
    viewModel { (network: String?) ->
        IdentityViewModel(
            get(), get(), get(), get(), get(), get(),
            online = get<ConnectionState>().status.map { it == ConnectionStatus.Online },
            networks = get(),
            network = WalletNetwork.of(network),
        )
    }
}
