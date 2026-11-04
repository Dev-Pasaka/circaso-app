package com.circaso.feature.assistant.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.datastore.AppPreferences
import com.circaso.feature.assistant.data.remote.AshaRemote
import com.circaso.feature.assistant.data.remote.VoicesResponse
import com.circaso.feature.assistant.domain.AshaLook
import com.circaso.feature.assistant.domain.AshaLookSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The mascot's look from the voice in use: the person's choice, else the server's default voice (A12 `gender`). The
 * gender is kept in preferences, so the right look shows offline and before the voices are read again.
 */
class AshaLookRepository(private val prefs: AppPreferences, private val remote: AshaRemote) : AshaLookSource {
    override val look: Flow<AshaLook> = prefs.ashaVoiceGender.map { AshaLook.forVoiceGender(it) }.distinctUntilChanged()

    override suspend fun refresh() {
        val voices = (remote.voices() as? AppResult.Success)?.value ?: return
        prefs.setAshaVoiceGender(genderInUse(voices, prefs.ashaVoice.first()))
    }

    companion object {
        /** The chosen voice's gender, or the default voice's when nothing (or a voice that no longer exists) is chosen. */
        fun genderInUse(voices: VoicesResponse, chosen: String?): String? =
            (voices.voices.firstOrNull { it.id == chosen } ?: voices.voices.firstOrNull { it.default })?.gender
    }
}
