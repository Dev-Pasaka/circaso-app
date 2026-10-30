package com.circaso.di

import com.circaso.feature.calls.CallController
import com.circaso.feature.calls.data.CallsRemote
import com.circaso.feature.calls.data.KtorCallsRemote
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.dsl.module

/** Calls (guide CL1–CL8, designs H1–H5): one app-wide controller over the platform's WebRTC engine. */
/** What clears a ringing notification on this platform (Android posts one; iOS rings in the app). */
var ringing: (String) -> Unit = {}

val callsModule = module {
    single<CallsRemote> { KtorCallsRemote(get()) }
    single {
        val chats = get<ChatsRepository>()
        val auth = get<com.circaso.core.session.AuthManager>()
        CallController(get(), get(), myUserId = { auth.userId }, scope = get(), names = { chatId -> chats.chat(chatId).first()?.let { it.title to (it.kind == ChatKind.Group) } },
            labels = { chatId -> chats.chat(chatId).first()?.members.orEmpty().mapNotNull { m -> m.handle?.let { m.userId to "@$it" } }.toMap() },
            stopRinging = ringing,
            online = get<com.circaso.core.sync.ConnectionState>().status.map { it == com.circaso.core.platform.ConnectionStatus.Online },
            log = { e ->
                get<com.circaso.feature.messaging.domain.MessagesRepository>().logCall(
                    e.chatId, e.callId, e.outgoing, e.video, com.circaso.feature.messaging.domain.CallOutcome.valueOf(e.outcome.name), e.durationSeconds, e.callerId, e.at,
                )
            },
        )
    }
}
