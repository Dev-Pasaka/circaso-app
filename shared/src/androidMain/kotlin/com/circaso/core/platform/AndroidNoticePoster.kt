package com.circaso.core.platform

import com.circaso.core.datastore.AppPreferences
import com.circaso.core.notifications.Notice
import com.circaso.core.notifications.NoticePoster
import kotlinx.coroutines.flow.first

/**
 * Posts a notice worded on the phone (a message that arrived over the socket, 2026-11-13) exactly as the push path
 * does: through [AndroidChatNotifications], with the coins chime unless the person turned it off.
 */
class AndroidNoticePoster(
    private val notifications: ChatNotifications,
    private val prefs: AppPreferences,
) : NoticePoster {
    override suspend fun post(notice: Notice) {
        val android = notifications as? AndroidChatNotifications ?: return
        val coinsOff = prefs.flag(AppPreferences.MoneySoundOff).first()
        android.show(notice, coins = !coinsOff)
    }
}
