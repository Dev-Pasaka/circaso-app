package com.circaso.di

import com.circaso.core.datastore.AppPreferences
import com.circaso.core.network.ApiConfig
import com.circaso.core.realtime.KtorSocketFactory
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.realtime.RealtimeDispatcher
import com.circaso.core.realtime.RealtimeSupervisor
import com.circaso.core.realtime.SocketFactory
import com.circaso.core.realtime.realtimeUrl
import com.circaso.core.session.AuthManager
import com.circaso.feature.contacts.domain.RefreshBlocksUseCase
import com.circaso.feature.contacts.domain.RefreshContactsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.profile.domain.RefreshProfileUseCase
import kotlinx.coroutines.flow.first
import org.koin.dsl.module

/** The live socket and what its frames do. Started once at launch (see `initKoin`). */
val realtimeModule = module {
    single<SocketFactory> { KtorSocketFactory(get()) }
    single<RealtimeCursors> { get<AppPreferences>() }
    single { RealtimeClient(get(), realtimeUrl(get<ApiConfig>().baseUrl), get<AuthManager>(), get(), get()) }
    single { RealtimeSupervisor(get(), get(), get(), get()) }
    single { com.circaso.core.realtime.PresenceReporter(get(), get()) }
    single {
        val refreshContacts = get<RefreshContactsUseCase>()
        val refreshBlocks = get<RefreshBlocksUseCase>()
        val refreshProfile = get<RefreshProfileUseCase>()
        val observeProfile = get<ObserveProfileUseCase>()
        val auth = get<AuthManager>()
        RealtimeDispatcher(
            frames = get<RealtimeClient>().frames,
            scope = get(),
            onSessionRevoked = { auth.signOutLocally(endedByServer = true) },
            refreshContacts = { refreshContacts() },
            refreshBlocks = { refreshBlocks() },
            refreshOwnProfile = { refreshProfile() },
            isOwnUser = { observeProfile().first()?.userId == it },
            onMessagingFrame = { type, data -> get<com.circaso.feature.messaging.data.repository.MessagingFrameHandler>().handle(type, data) },
            reloadMessaging = { get<com.circaso.feature.messaging.domain.ChatsRepository>().refresh(); get<com.circaso.feature.messaging.domain.MessagesRepository>().syncInbox() },
            onWalletFrame = { type, data -> get<com.circaso.feature.wallet.domain.WalletRepository>().onFrame(type, data) },
            reloadWallet = { get<com.circaso.feature.wallet.domain.WalletRepository>().refresh() },
            onChamaFrame = { type, data -> get<com.circaso.feature.chama.domain.ChamaRepository>().onFrame(type, data) },
            onCallsFrame = { type, data -> get<com.circaso.feature.calls.CallController>().onFrame(type, data) },
        )
    }
}
