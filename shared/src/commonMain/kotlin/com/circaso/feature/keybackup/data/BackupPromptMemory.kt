package com.circaso.feature.keybackup.data

import com.circaso.core.datastore.AppPreferences
import kotlinx.coroutines.flow.first

/**
 * When the person said "Not Now" to a backup sheet (2026-11-23), kept on this phone per account and per sheet, so the
 * sheet waits a while before it asks again. Not secret: only a time. Forgotten on sign-out ([AppPreferences.clear]),
 * so the next sign-in is asked afresh.
 */
interface BackupPromptMemory {
    /** Until when [kind] is quiet for [userId]; 0 when it isn't. */
    suspend fun quietUntil(userId: String, kind: String): Long
    suspend fun quiet(userId: String, kind: String, until: Long)
}

class PrefsBackupPromptMemory(private val prefs: AppPreferences) : BackupPromptMemory {
    override suspend fun quietUntil(userId: String, kind: String): Long {
        val saved = prefs.text(key(kind)).first() ?: return 0
        if (saved.substringBefore('|') != userId) return 0
        return saved.substringAfter('|').toLongOrNull() ?: 0
    }

    override suspend fun quiet(userId: String, kind: String, until: Long) = prefs.setText(key(kind), "$userId|$until")

    private fun key(kind: String) = "$Prefix$kind"

    companion object {
        /** Under `text.`: [AppPreferences.clear] drops every name starting with this on sign-out. */
        const val Prefix = "keybackup.quiet."
    }
}
