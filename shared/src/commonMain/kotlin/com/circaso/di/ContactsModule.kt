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
import kotlinx.coroutines.flow.first
import org.koin.dsl.bind
import org.koin.dsl.module

/** On this phone: the contacts prompt was shown once from Home (whatever the answer), so it isn't shown again. */
private const val ContactsAskedFlag = "contacts.asked"
/** The ISO code of the person's own number's country (HomeCountry). */
private const val HomeCountryKey = "home.country"

val contactsModule = module {
    single { get<CircasoDatabase>().contactsDao() }
    single<ContactsRemote> { KtorContactsRemote(get()) }
    single { ContactRemoveHandler(get()) } bind OutboxHandler::class
    single { BlockSetHandler(get()) } bind OutboxHandler::class
    single { BlockClearHandler(get()) } bind OutboxHandler::class
    single<ContactsRepository> { ContactsRepositoryImpl(get(), get(), get(), kickOutbox = get(named("kickOutbox")), names = get()) }
    factory { ObserveContactsUseCase(get()) }
    factory { ObserveBlocksUseCase(get()) }
    factory { RefreshContactsUseCase(get()) }
    factory { RefreshBlocksUseCase(get()) }
    factory { SyncPhoneBookUseCase(get()) }
    single {
        val auth = get<com.circaso.core.session.AuthManager>()
        val connection = get<com.circaso.core.sync.ConnectionState>()
        val prefs = get<com.circaso.core.datastore.AppPreferences>()
        com.circaso.feature.contacts.data.repository.PhoneBookAutoSync(
            reader = get(),
            sync = get(),
            ready = kotlinx.coroutines.flow.combine(auth.state, connection.status) { a, c ->
                a == com.circaso.core.session.AuthState.Active && c == com.circaso.core.platform.ConnectionStatus.Online
            },
            asked = { prefs.flag(ContactsAskedFlag).first() },
            markAsked = { prefs.setFlag(ContactsAskedFlag, true) },
            scope = get(),
            now = { com.circaso.core.sync.nowMillis() },
            homeCountry = { get<com.circaso.core.platform.HomeCountry>().get() },
        )
    }
    single {
        val prefs = get<com.circaso.core.datastore.AppPreferences>()
        com.circaso.core.platform.HomeCountry(read = { prefs.text(HomeCountryKey).first() }, write = { prefs.setText(HomeCountryKey, it) })
    }
    factory { ConnectUseCase(get()) }
    factory { CreateInviteUseCase(get()) }
    factory { RevokeInviteUseCase(get()) }
    factory { RemoveContactUseCase(get()) }
    factory { BlockUseCase(get()) }
    factory { UnblockUseCase(get()) }
    factory { CanReachUseCase(get()) }
}
