package com.circaso.di

import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.data.CallsRemote
import com.circaso.feature.calls.data.KtorCallsRemote
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.dsl.bind
import org.koin.dsl.module

/** Calls (guide CL1–CL8, designs H1–H5): one app-wide controller over the platform's WebRTC engine. */
/** What clears a ringing notification on this platform (Android posts one; iOS rings in the app). */
var ringing: (String) -> Unit = {}

/**
 * Everyone's photo for a call, from this phone only: the chat's members first, then contacts (someone calling from a
 * chat this phone hasn't loaded yet), and my own from my profile. The calls API carries no photos (yet).
 */
internal suspend fun callPhotos(
    chat: com.circaso.feature.messaging.domain.Chat?,
    contacts: com.circaso.feature.contacts.domain.ContactsRepository,
    profile: com.circaso.feature.profile.domain.ProfileRepository,
): com.circaso.feature.calls.CallPhotos = kotlinx.coroutines.coroutineScope {
    // Both reads at once.
    val known = async { kotlinx.coroutines.withTimeoutOrNull(200) { contacts.contacts.first() }.orEmpty() }
    val me = async { kotlinx.coroutines.withTimeoutOrNull(200) { profile.profile.first() }?.avatarUrl }
    val members = chat?.members.orEmpty().mapNotNull { m -> m.avatarUrl?.let { m.userId to it } }.toMap()
    val fromContacts = known.await().mapNotNull { c -> c.person.avatarUrl?.let { c.person.userId to it } }.toMap()
    com.circaso.feature.calls.CallPhotos(chat = chat?.avatarUrl, people = fromContacts + members, me = me.await())
}

/** The call's timeline kept in DataStore while it runs, so a call cut off by the app being killed is still reported. */
private class PreferencesCallTimeline(private val prefs: com.circaso.core.datastore.AppPreferences) : com.circaso.feature.calls.CallTimelineStore {
    override suspend fun save(snapshot: String) = prefs.setText(Key, snapshot)
    override suspend fun load(): String? = prefs.text(Key).first()
    override suspend fun clear() = prefs.setText(Key, null)
    private companion object { const val Key = "calls.timeline" }
}

val callsModule = module {
    single<CallsRemote> { KtorCallsRemote(get()) }
    single { com.circaso.feature.calls.data.CallReportHandler(get()) } bind com.circaso.core.sync.OutboxHandler::class
    single {
        val info = get<com.circaso.core.platform.ClientInfo>()
        val monitor = get<com.circaso.core.platform.ConnectivityMonitor>()
        val outbox = get<com.circaso.core.sync.Outbox>()
        val kick = get<() -> Unit>(org.koin.core.qualifier.named("kickOutbox"))
        val json = kotlinx.serialization.json.Json { encodeDefaults = false; explicitNulls = false }
        com.circaso.feature.calls.CallRecorder(
            scope = get(), engine = get(),
            device = { com.circaso.feature.calls.CallReportDevice(info.platform, info.appVersion, info.osVersion, listOf(info.manufacturer, info.model).filter { it.isNotBlank() }.joinToString(" ")) },
            submit = { report ->
                outbox.enqueue(com.circaso.feature.calls.data.CallReportHandler.Type, json.encodeToString(com.circaso.feature.calls.QueuedCallReport.serializer(), report))
                kick()
            },
            store = PreferencesCallTimeline(get()),
            network = monitor.osStatus.map { if (it == com.circaso.core.platform.ConnectionStatus.Online) "online" else "offline" },
            transport = monitor.transport,
            reach = get<com.circaso.core.sync.ConnectionState>().status.map { if (it == com.circaso.core.platform.ConnectionStatus.Online) "online" else "offline" },
            socket = get<com.circaso.core.realtime.RealtimeClient>().connected.map { if (it) "up" else "down" },
        )
    }
    single {
        val chats = get<ChatsRepository>()
        val auth = get<com.circaso.core.session.AuthManager>()
        CallController(get(), get(), myUserId = { auth.userId }, scope = get(), names = { chatId -> chats.chat(chatId).first()?.let { it.title to (it.kind == ChatKind.Group) } },
            labels = { chatId -> chats.chat(chatId).first()?.members.orEmpty().mapNotNull { m -> m.handle?.let { m.userId to "@$it" } }.toMap() },
            personName = get<com.circaso.feature.names.domain.PeopleNames>()::of,
            stopRinging = ringing,
            // The phone's network as the OS sees it, never "can our server be reached" (2026-11-13: a socket ping
            // timeout made the app "offline" and a call whose audio was fine hung up 40 s later).
            online = get<com.circaso.core.platform.ConnectivityMonitor>().osStatus.map { it == com.circaso.core.platform.ConnectionStatus.Online },
            log = { e ->
                get<com.circaso.feature.messaging.domain.MessagesRepository>().logCall(
                    e.chatId, e.callId, e.outgoing, e.video, com.circaso.feature.messaging.domain.CallOutcome.valueOf(e.outcome.name), e.durationSeconds, e.callerId, e.at,
                )
            },
            photos = { chatId -> callPhotos(chats.chat(chatId).first(), get(), get()) },
            // 2026-11-14: pull someone as soon as they say `connected` (their call.updated), without waiting for our own
            // connection; someone who never says it is pulled after 8 s. Re-read the call every 5 s and on every socket
            // reconnect, so a lost call.ended (or `connected`) can't strand a call; hang up a call that has received no
            // audio for 20 s (2026-11-13: reconnect first, end only if it can't recover).
            pullGraceMillis = 8_000,
            pollMillis = 5_000,
            socketUp = get<com.circaso.core.realtime.RealtimeClient>().connected,
            stallMillis = 20_000,
            recorder = get(),
            // 2026-11-13: the connection is built while a call rings (and beside CL1), each phone says "still here"
            // every 2 s, and media liveness is read every second, so a dropped peer shows within ~3 s and this
            // phone's own dropped connection reconnects at once (the 20 s stall check stands down).
            prewarm = true,
            heartbeatMillis = 2_000,
            sendFrame = { type, data -> get<com.circaso.core.realtime.RealtimeClient>().send("calls", type, data) },
            livenessMillis = 1_000,
        ).also { controller ->
            // A call the app didn't live to end (killed mid-call) is reported now, once someone is signed in.
            val recorder = get<com.circaso.feature.calls.CallRecorder>()
            get<kotlinx.coroutines.CoroutineScope>().launch {
                auth.state.first { it == com.circaso.core.session.AuthState.Active }
                if (controller.state.value == null) recorder.recoverInterrupted()
            }
        }
    }
}
