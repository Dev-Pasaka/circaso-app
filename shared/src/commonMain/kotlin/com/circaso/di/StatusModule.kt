package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.status.data.remote.KtorMomentsRemote
import com.circaso.feature.status.data.remote.MomentsRemote
import com.circaso.feature.status.data.repository.MomentKeys
import com.circaso.feature.status.data.repository.MomentPostHandler
import com.circaso.feature.status.data.repository.MomentsRepositoryImpl
import com.circaso.feature.status.domain.MomentsRepository
import org.koin.core.qualifier.named
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Moments (Spaces phase 1, backend `status` module ST1–ST8): read from Room, posted through the outbox, end to end
 * encrypted with a sender key of their own in the same Signal store as chats.
 */
val statusModule = module {
    single { get<CircasoDatabase>().momentsDao() }
    single<MomentsRemote> { KtorMomentsRemote(get()) }
    single { MomentKeys(get(), get(), get(), get(), get(named("ownUserId"))) }
    single {
        MomentsRepositoryImpl(
            dao = get(), remote = get(), keys = get(), contacts = get(), crypto = get(), files = get(),
            storage = get(named("plain")), outbox = get(), kickOutbox = get(named("kickOutbox")), json = get(),
            ownUserId = get(named("ownUserId")),
        )
    }
    // The outbox processor collects every handler while it is built; this one finds the repository only when it
    // sends, so the repository (which kicks the outbox and reads contacts) is never part of that graph.
    single { MomentPostHandler { get<MomentsRepositoryImpl>() } } bind OutboxHandler::class
    single<MomentsRepository> { get<MomentsRepositoryImpl>() }
    viewModel { com.circaso.feature.status.presentation.SpacesViewModel(get(), get(), get(), get()) }
}
