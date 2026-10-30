package com.circaso.feature.assistant.domain

/** Opens live conversations with Asha. Online-only: there is nothing to queue about a voice call. */
interface LiveVoiceRepository {
    suspend fun open(language: String, chatReading: Boolean): LiveVoiceStart

    /** The tokens to put in an `auth` frame now (refreshed early when needed), or null when signed out. */
    suspend fun currentTokens(): Pair<String, String?>?
}
