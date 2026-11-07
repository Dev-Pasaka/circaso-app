package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.network.ApiConfig
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.assistant.data.remote.AshaRemote
import com.circaso.feature.assistant.data.remote.KtorAshaRemote
import com.circaso.feature.assistant.data.remote.KtorLiveSocketFactory
import com.circaso.feature.assistant.data.remote.KtorLiveVoiceRemote
import com.circaso.feature.assistant.data.remote.LiveSocketFactory
import com.circaso.feature.assistant.data.remote.LiveVoiceRemote
import com.circaso.feature.assistant.data.remote.liveVoiceUrl
import com.circaso.feature.assistant.data.repository.LiveVoiceRepositoryImpl
import com.circaso.feature.assistant.domain.LiveVoiceRepository
import com.circaso.feature.assistant.domain.TalkToAshaUseCase
import com.circaso.feature.assistant.data.repository.AshaDeleteAllHandler
import com.circaso.feature.assistant.data.repository.AshaDeleteMessageHandler
import com.circaso.feature.assistant.data.repository.AshaRepositoryImpl
import com.circaso.feature.assistant.domain.AshaRepository
import com.circaso.feature.assistant.domain.AskAshaUseCase
import com.circaso.feature.assistant.domain.DeleteAshaHistoryUseCase
import com.circaso.feature.assistant.domain.DeleteAshaMessageUseCase
import com.circaso.feature.assistant.domain.DeviceToolRunner
import com.circaso.feature.assistant.domain.GetAshaUsageUseCase
import com.circaso.feature.assistant.domain.NoChatsToolRunner
import com.circaso.feature.assistant.domain.ObserveAshaHistoryUseCase
import com.circaso.feature.assistant.domain.RefreshAshaHistoryUseCase
import com.circaso.feature.assistant.domain.SpeakUseCase
import com.circaso.feature.assistant.domain.TranscribeVoiceUseCase
import kotlinx.coroutines.flow.first
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val assistantModule = module {
    viewModel { com.circaso.feature.assistant.presentation.AshaVoiceViewModel(get(), get(), get()) }
    single { get<CircasoDatabase>().ashaDao() }
    single<AshaRemote> { KtorAshaRemote(get(), get(), get(), get(), get()) }
    // Replaced when messaging exists and chats can be searched on the phone.
    single<DeviceToolRunner> { NoChatsToolRunner }
    single { AshaDeleteMessageHandler(get()) } bind OutboxHandler::class
    single { AshaDeleteAllHandler(get()) } bind OutboxHandler::class
    single<AshaRepository> { AshaRepositoryImpl(get(), get(), get(), get(), kickOutbox = get(named("kickOutbox")), voice = { get<com.circaso.core.datastore.AppPreferences>().ashaVoice.first() }, currency = { get<com.circaso.core.datastore.AppPreferences>().currency.first() ?: com.circaso.core.platform.localCurrencyCode() }) }
    factory { AskAshaUseCase(get(), get()) }
    factory { ObserveAshaHistoryUseCase(get()) }
    factory { RefreshAshaHistoryUseCase(get()) }
    factory { DeleteAshaMessageUseCase(get()) }
    factory { DeleteAshaHistoryUseCase(get()) }
    factory { TranscribeVoiceUseCase(get()) }
    factory { SpeakUseCase(get()) }
    factory { GetAshaUsageUseCase(get()) }
    // Asha down for maintenance (2026-11-07): one app-wide answer, fed by A14, failed turns and the socket.
    single<com.circaso.feature.assistant.domain.AshaAvailability> { com.circaso.feature.assistant.data.repository.AshaAvailabilityImpl(get(), get(), isOnline = { get<com.circaso.core.sync.ConnectionState>().status.value == com.circaso.core.platform.ConnectionStatus.Online }) }

    // Live voice (A11): its own socket, open only while the voice screen is up.
    single<LiveSocketFactory> { KtorLiveSocketFactory(get()) }
    single<LiveVoiceRemote> { KtorLiveVoiceRemote(get(), liveVoiceUrl(get<ApiConfig>().baseUrl), get(), conversation = { get<AshaRepository>().let { r -> r.current.value ?: r.conversations.value.firstOrNull()?.id } }, voice = { get<com.circaso.core.datastore.AppPreferences>().ashaVoice.first() }, currency = { get<com.circaso.core.datastore.AppPreferences>().currency.first() ?: com.circaso.core.platform.localCurrencyCode() }) }
    single<LiveVoiceRepository> { LiveVoiceRepositoryImpl(get(), get(), get()) }
    factory { TalkToAshaUseCase(get(), get()) }
}
