package com.circaso.di

import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.data.CallsRemote
import com.circaso.feature.calls.data.KtorCallsRemote
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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

val callsModule = module {
    single<CallsRemote> { KtorCallsRemote(get()) }
    single {
        val chats = get<ChatsRepository>()
        val auth = get<com.circaso.core.session.AuthManager>()
        CallController(get(), get(), myUserId = { auth.userId }, scope = get(), names = { chatId -> chats.chat(chatId).first()?.let { it.title to (it.kind == ChatKind.Group) } },
            labels = { chatId -> chats.chat(chatId).first()?.members.orEmpty().mapNotNull { m -> m.handle?.let { m.userId to "@$it" } }.toMap() },
            personName = get<com.circaso.feature.names.domain.PeopleNames>()::of,
            stopRinging = ringing,
            online = get<com.circaso.core.sync.ConnectionState>().status.map { it == com.circaso.core.platform.ConnectionStatus.Online },
            log = { e ->
                get<com.circaso.feature.messaging.domain.MessagesRepository>().logCall(
                    e.chatId, e.callId, e.outgoing, e.video, com.circaso.feature.messaging.domain.CallOutcome.valueOf(e.outcome.name), e.durationSeconds, e.callerId, e.at,
                )
            },
            photos = { chatId -> callPhotos(chats.chat(chatId).first(), get(), get()) },
            // 2026-11-09: pull someone once both connections are up (the SFU held earlier pulls ~9 s); re-read the
            // call every 5 s and on every socket reconnect, so a lost call.ended can't strand a call; hang up a call
            // that has received no audio for 20 s.
            pullGraceMillis = 3_000,
            pollMillis = 5_000,
            socketUp = get<com.circaso.core.realtime.RealtimeClient>().connected,
            stallMillis = 20_000,
        )
    }
}
