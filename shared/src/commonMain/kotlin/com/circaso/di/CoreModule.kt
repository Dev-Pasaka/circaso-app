package com.circaso.di

import io.ktor.client.request.get
import com.circaso.core.common.StartupGate
import com.circaso.core.common.map
import com.circaso.core.network.NoBody
import com.circaso.core.network.call
import com.circaso.core.platform.PushTokenProvider
import com.circaso.core.push.PushTokenRegistrar
import com.circaso.feature.messaging.data.keys.KeyMaintainer
import com.circaso.core.push.PushTokenRequest
import com.circaso.core.session.AuthState
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.combine
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.database.buildDatabase
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.network.AccessTokenProvider
import com.circaso.core.network.ApiConfig
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NetworkEvents
import com.circaso.core.network.SessionRefresher
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.circasoJson
import com.circaso.core.network.createHttpClient
import com.circaso.core.network.createPlainHttpClient
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.realtime.RealtimeDispatcher
import com.circaso.core.realtime.RealtimeSupervisor
import com.circaso.core.platform.ClientInfo
import com.circaso.core.database.RoomSessionDataCleaner
import com.circaso.core.session.AuthManager
import com.circaso.core.session.SessionDataCleaner
import com.circaso.core.session.refreshOverHttp
import com.circaso.core.session.DeviceIdentity
import com.circaso.core.session.SessionStore
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.Outbox
import com.circaso.core.sync.OutboxProcessor
import com.circaso.core.sync.ReconnectCoordinator
import com.circaso.core.sync.SyncAnnouncements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.mp.KoinPlatform

/** Everything that is the same on both platforms. Platform modules provide ClientInfo, ConnectivityMonitor, StoreLauncher, SecureStorage, DeviceKeyStore, the Room builder and the DataStore. */
val coreModule = module {
    single { circasoJson() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SessionStore(get(), get()) }
    single { DeviceIdentity(get(), get()) }
    // The session manager is both the token source and the refresher; the API client reaches it lazily to avoid a cycle.
    single<SessionDataCleaner> { RoomSessionDataCleaner(get(), get<AppPreferences>()) }
    single { AuthManager(get(), get(), refreshOverHttp(lazy { get<CircasoApi>() }), cleaner = get()) }
    single<AccessTokenProvider> { get<AuthManager>() }
    single<SessionRefresher> { get<AuthManager>() }
    single {
        val info = get<ClientInfo>()
        ApiConfig(baseUrl = ApiConfig.DEV_BASE_URL, clientVersion = "${info.platform}/${info.appVersion}")
    }
    single { NetworkHealth() }
    single { NetworkEvents() }
    single { createHttpClient(get(), get(), get()) }
    single { CircasoApi(get(), get(), get(), get(), lazy { get<SessionRefresher>() }) }
    single {
        val config = get<com.circaso.core.network.ApiConfig>()
        val plain = get<io.ktor.client.HttpClient>(org.koin.core.qualifier.named("plain"))
        ConnectionState(get(), get(), get(), probe = {
            // Any answer from our server means it can be reached; only no answer at all is offline.
            plain.get(config.baseUrl.trimEnd('/') + "/health/live").status.value < 500
        })
    }
    single { buildDatabase(get()) }
    single { get<CircasoDatabase>().outboxDao() }
    // A queued write belongs to the person who made it; the outbox only ever hands out the signed-in person's writes.
    single { Outbox(get(), owner = { get<AuthManager>().userId }) }
    single(named("plain")) { createPlainHttpClient() }
    single { OutboxProcessor(get(), getAll()) }
    single { SyncAnnouncements() }
    single {
        val processor = get<OutboxProcessor>()
        val background = getOrNull<com.circaso.core.sync.BackgroundSync>() ?: com.circaso.core.sync.NoBackgroundSync
        ReconnectCoordinator({ processor.drain().also { if (it is com.circaso.core.sync.DrainResult.Paused) background.schedule() } }, get())
    }
    // Asks the outbox to send now; safe to call from anywhere, does nothing useful while offline.
    single<() -> Unit>(named("kickOutbox")) {
        val scope = get<CoroutineScope>()
        val processor = get<OutboxProcessor>()
        val background = getOrNull<com.circaso.core.sync.BackgroundSync>() ?: com.circaso.core.sync.NoBackgroundSync
        {
            scope.launch {
                // Sent now if it can be; if the network is in the way, the platform sends it when it's back.
                if (processor.drain() is com.circaso.core.sync.DrainResult.Paused) background.schedule()
            }
            Unit
        }
    }
    single { AppPreferences(get()) }
    single {
        val auth = get<AuthManager>(); val api = get<CircasoApi>(); val connection = get<ConnectionState>()
        PushTokenRegistrar(
            ready = combine(auth.state, connection.status) { a, c -> a == AuthState.Active && c == ConnectionStatus.Online },
            userId = { auth.userId },
            provider = get<PushTokenProvider>(),
            send = { token ->
                api.call<NoBody> {
                    method = HttpMethod.Put
                    url("/v1/devices/current/push-token")
                    contentType(ContentType.Application.Json)
                    setBody(PushTokenRequest(token))
                }.map { }
            },
            scope = get(),
        )
    }
    single { StartupGate(get(), get(), get()) }
}

private var started = false

/** Starts Koin once. Called from each platform's entry point with its platform module. */
fun initKoin(platform: Module) {
    if (started) return
    started = true
    val koin = startKoin { modules(coreModule, authModule, profileModule, contactsModule, accountModule, assistantModule, realtimeModule, messagingModule, walletModule, chamaModule, statusModule, callsModule, homeModule, platform) }.koin
    // Open the stores off the main thread while the first frame is being prepared, and put any write that a
    // crash left half-sent back in line (its Idempotency-Key makes the retry safe).
    val scope = koin.get<CoroutineScope>()
    scope.launch { koin.get<AuthManager>().restore() } // the session first: it decides what the person sees
    koin.get<RealtimeDispatcher>().start()
    koin.get<RealtimeSupervisor>().start()
    koin.get<PushTokenRegistrar>().start()
    koin.get<KeyMaintainer>().start() // encryption keys on the server, whenever signed in and online
    koin.get<com.circaso.feature.messaging.data.repository.MessagingStarter>().start()
    scope.launch {
        koin.get<AppPreferences>().themeOverrides.first()
        koin.get<Outbox>().requeueInterrupted()
        // Send what is waiting now, and again every time the connection comes back.
        val reconnect = koin.get<ReconnectCoordinator>()
        val chamas = koin.get<com.circaso.feature.chama.domain.ChamaRepository>()
        koin.get<ConnectionState>().status.collect {
            reconnect.onStatus(it)
            // A treasurer's payment that went while the link didn't: send the link whenever the phone is back online.
            if (it == com.circaso.core.platform.ConnectionStatus.Online) launch { chamas.retryPendingLinks() }
        }
    }
}

/** For the Android launch splash: true once the first real screen can be drawn. False before Koin has started. */
fun isStartupReady(): Boolean = runCatching { KoinPlatform.getKoin().get<StartupGate>().ready.value }.getOrDefault(false)
