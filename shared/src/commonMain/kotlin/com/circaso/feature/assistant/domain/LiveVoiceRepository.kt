package com.circaso.feature.assistant.domain

/** Opens live conversations with Asha. Online-only: there is nothing to queue about a voice call. */
interface LiveVoiceRepository {
    /**
     * [conversationId] carries a call on in that Asha conversation (a reconnect after the line dropped); null means the
     * one open in the chat, else the server's latest.
     */
    suspend fun open(language: String, chatReading: Boolean, conversationId: String? = null): LiveVoiceStart

    /** The tokens to put in an `auth` frame now (refreshed early when needed), or null when signed out. */
    suspend fun currentTokens(): Pair<String, String?>?
}
