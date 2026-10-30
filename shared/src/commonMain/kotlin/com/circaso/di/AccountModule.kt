package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.account.data.remote.AccountRemote
import com.circaso.feature.account.data.remote.KtorAccountRemote
import com.circaso.feature.account.data.repository.AccountRepositoryImpl
import com.circaso.feature.account.data.repository.PushTokenHandler
import com.circaso.feature.account.domain.AccountRepository
import com.circaso.feature.account.domain.DeleteAccountUseCase
import com.circaso.feature.account.domain.LinkProviderUseCase
import com.circaso.feature.account.domain.ObserveSecurityLogUseCase
import com.circaso.feature.account.domain.ObserveSecurityOverviewUseCase
import com.circaso.feature.account.domain.RecoveryStatusUseCase
import com.circaso.feature.account.domain.RefreshSecurityLogUseCase
import com.circaso.feature.account.domain.RefreshSecurityOverviewUseCase
import com.circaso.feature.account.domain.RequestRecoveryUseCase
import com.circaso.feature.account.domain.UnlinkProviderUseCase
import com.circaso.feature.account.domain.UpdatePushTokenUseCase
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val accountModule = module {
    single { get<CircasoDatabase>().accountDao() }
    single<AccountRemote> { KtorAccountRemote(get()) }
    single { PushTokenHandler(get()) } bind OutboxHandler::class
    single<AccountRepository> { AccountRepositoryImpl(get(), get(), get(), kickOutbox = get(named("kickOutbox"))) }
    factory { ObserveSecurityOverviewUseCase(get()) }
    factory { ObserveSecurityLogUseCase(get()) }
    factory { RefreshSecurityOverviewUseCase(get()) }
    factory { RefreshSecurityLogUseCase(get()) }
    factory { LinkProviderUseCase(get(), get(), get()) }
    factory { UnlinkProviderUseCase(get()) }
    factory { DeleteAccountUseCase(get()) }
    factory { UpdatePushTokenUseCase(get()) }
    factory { RequestRecoveryUseCase(get()) }
    factory { RecoveryStatusUseCase(get()) }
}
