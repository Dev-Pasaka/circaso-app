package com.circaso.feature.assistant.data.repository

import com.circaso.core.common.Diagnostics
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.session.AuthManager
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.assistant.data.remote.LiveVoiceRemote
import com.circaso.feature.assistant.domain.LiveVoiceRepository
import com.circaso.feature.assistant.domain.LiveVoiceStart
import kotlinx.coroutines.CancellationException

class LiveVoiceRepositoryImpl(
    private val remote: LiveVoiceRemote,
    private val auth: AuthManager,
    private val connection: ConnectionState,
) : LiveVoiceRepository {
    override suspend fun open(language: String, chatReading: Boolean, conversationId: String?): LiveVoiceStart {
        if (connection.status.value != ConnectionStatus.Online) return LiveVoiceStart.Offline
        val (access, agent) = currentTokens() ?: return LiveVoiceStart.SignedOut
        return try {
            LiveVoiceStart.Opened(remote.open(access, agent, language, chatReading, conversationId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Diagnostics.note("voice: could not open (${e::class.simpleName})")
            LiveVoiceStart.Offline
        }
    }

    override suspend fun currentTokens(): Pair<String, String?>? {
        val access = auth.accessToken() ?: return null
        return access to auth.agentToken()
    }
}
