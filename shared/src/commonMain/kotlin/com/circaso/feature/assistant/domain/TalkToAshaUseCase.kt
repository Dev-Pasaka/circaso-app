package com.circaso.feature.assistant.domain

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * A live conversation with Asha, with the phone's two duties handled here so the screen only sees the conversation:
 * her tool requests are run on the phone and answered on the socket (at most 30 snippets, as the server allows), and
 * fresh tokens go out in an `auth` frame whenever the session's token changes, before the old one's 15 minutes are up.
 */
class TalkToAshaUseCase(
    private val repository: LiveVoiceRepository,
    private val tools: DeviceToolRunner,
    private val tokenCheckMillis: Long = TokenCheckMillis,
) {
    suspend operator fun invoke(language: String = "en", chatReading: Boolean = true): LiveVoiceStart =
        when (val start = repository.open(language, chatReading)) {
            is LiveVoiceStart.Opened -> LiveVoiceStart.Opened(Conversation(start.session))
            else -> start
        }

    private inner class Conversation(private val inner: LiveVoiceSession) : LiveVoiceSession by inner {
        override val events: Flow<LiveVoiceEvent> = channelFlow {
            val refresher = launch {
                var sent = repository.currentTokens()?.first
                while (true) {
                    delay(tokenCheckMillis)
                    val now = repository.currentTokens() ?: continue
                    if (now.first != sent) { inner.sendAuth(now.first, now.second); sent = now.first }
                }
            }
            inner.events.collect { event ->
                when (event) {
                    is LiveVoiceEvent.ToolRequest -> {
                        send(LiveVoiceEvent.UsingTool(event.name))
                        launch {
                            val snippets = tools.run(event.name, event.argumentsJson).take(MaxSnippets)
                            inner.sendToolResults(event.turnId, event.callId, snippets)
                        }
                    }
                    else -> send(event)
                }
                if (event is LiveVoiceEvent.Closed) { refresher.cancel(); close() }
            }
            awaitClose { refresher.cancel() }
        }
    }

    private companion object {
        const val MaxSnippets = 30
        /** Access tokens last 15 minutes and are refreshed early by the session; a check a minute catches the change in time. */
        const val TokenCheckMillis = 60_000L
    }
}
