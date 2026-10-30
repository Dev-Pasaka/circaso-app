package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.contacts.data.remote.ContactsRemote
import com.circaso.feature.contacts.data.remote.KtorContactsRemote
import com.circaso.feature.contacts.data.repository.BlockClearHandler
import com.circaso.feature.contacts.data.repository.BlockSetHandler
import com.circaso.feature.contacts.data.repository.ContactRemoveHandler
import com.circaso.feature.contacts.data.repository.ContactsRepositoryImpl
import com.circaso.feature.contacts.domain.BlockUseCase
import com.circaso.feature.contacts.domain.CanReachUseCase
import com.circaso.feature.contacts.domain.ConnectUseCase
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.CreateInviteUseCase
import com.circaso.feature.contacts.domain.ObserveBlocksUseCase
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.RefreshBlocksUseCase
import com.circaso.feature.contacts.domain.RefreshContactsUseCase
import com.circaso.feature.contacts.domain.RemoveContactUseCase
import com.circaso.feature.contacts.domain.RevokeInviteUseCase
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import com.circaso.feature.contacts.domain.UnblockUseCase
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val contactsModule = module {
    single { get<CircasoDatabase>().contactsDao() }
    single<ContactsRemote> { KtorContactsRemote(get()) }
    single { ContactRemoveHandler(get()) } bind OutboxHandler::class
    single { BlockSetHandler(get()) } bind OutboxHandler::class
    single { BlockClearHandler(get()) } bind OutboxHandler::class
    single<ContactsRepository> { ContactsRepositoryImpl(get(), get(), get(), kickOutbox = get(named("kickOutbox"))) }
    factory { ObserveContactsUseCase(get()) }
    factory { ObserveBlocksUseCase(get()) }
    factory { RefreshContactsUseCase(get()) }
    factory { RefreshBlocksUseCase(get()) }
    factory { SyncPhoneBookUseCase(get()) }
    factory { ConnectUseCase(get()) }
    factory { CreateInviteUseCase(get()) }
    factory { RevokeInviteUseCase(get()) }
    factory { RemoveContactUseCase(get()) }
    factory { BlockUseCase(get()) }
    factory { UnblockUseCase(get()) }
    factory { CanReachUseCase(get()) }
}
