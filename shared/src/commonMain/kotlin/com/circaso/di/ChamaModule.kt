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
    single<ChamaRemote> { KtorChamaRemote(get()) }
    single { ChamaRepositoryImpl(get(), get(), get(), links = get<com.circaso.core.datastore.AppPreferences>(), scope = get()) }
    single<ChamaRepository> { get<ChamaRepositoryImpl>() }
    factory { ObserveChamaUseCase(get()) }
    factory { RefreshChamaUseCase(get()) }
    factory { SetGoalUseCase(get()) }
    factory { LoadLedgerUseCase(get()) }
    factory { CreateChamaRequestUseCase(get()) }
    factory { DecideChamaRequestUseCase(get()) }
    factory { AttachChamaPaymentUseCase(get()) }
    factory { ThreadChama(get(), get(), get(), get(), get(), get()) }
    viewModel { (chatId: String) -> ChamaViewModel(chatId, get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
