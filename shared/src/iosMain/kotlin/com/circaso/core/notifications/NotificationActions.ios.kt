package com.circaso.core.notifications

import com.circaso.core.platform.ChatNotifications
import com.circaso.core.sync.OutboxProcessor
import com.circaso.di.initKoinIos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.Koin
import org.koin.mp.KoinPlatform

/**
 * Reply and Mark as read on an iPhone chat notification (the CHAT_MESSAGE categories registered in AppDelegate, set on
 * chat messages by the Notification Service Extension), without opening Circaso (2026-11-13). The work is Android's
 * (ChatNotificationActionReceiver), through the same [NotificationReplies]; this only gets Circaso ready when iOS
 * launched it in the background just for the action, and reports back on the main thread.
 *
 * Every path answers, exactly once: queued, not queued, too slow ([QueueMillis]) or an exception. Nothing thrown here
 * reaches Swift. ChatNotificationActions.swift also keeps its own deadline for iOS's completion handler.
 */
object NotificationActions {
    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Sends [text] to [chatId] through the outbox. [queued] says whether it was queued, names the chat (as the chat list
     * does) for the "couldn't send" notice, and whether trying again could help; AppDelegate then lets iOS go on. A
     * queued reply takes the chat's notifications down at once, so it reads as answered, as in Messages. [finished]
     * comes once the outbox has had its try at sending it and the chat is read (the end of Circaso's background time),
     * with the chat's name when the server refused the reply for good, so the person still hears it didn't go.
     */
    fun reply(chatId: String, text: String, queued: (Boolean, String?, Boolean) -> Unit, finished: (Boolean, String?) -> Unit) {
        val koin = ready()
        scope(koin).launch {
            var answered = false
            var refused = false
            var title: String? = null
            try {
                val app = koin ?: error("Koin not started")
                val replies = app.get<NotificationReplies>()
                val outcome = withTimeoutOrNull(QueueMillis) { replies.reply(chatId, text, thenRead = false) } ?: NotificationReplies.Outcome.NotSent(null)
                if (outcome == NotificationReplies.Outcome.Queued) runCatching { app.get<ChatNotifications>().clear(chatId) }
                answered = true
                // Nothing typed is not a failure: there is nothing to say.
                val notSent = outcome as? NotificationReplies.Outcome.NotSent
                onMain { queued(notSent == null, notSent?.chatTitle, notSent?.canRetry ?: false) }
                if (outcome == NotificationReplies.Outcome.Queued) {
                    // Sent now while iOS still gives Circaso time; if the network is in the way, the background sync sends it.
                    withTimeoutOrNull(SendMillis) { runCatching { app.get<OutboxProcessor>().drain() } }
                    if (replies.refused(chatId, text)) { refused = true; title = replies.title(chatId) }
                    // The message answered is saved now, so "read" reaches it.
                    withTimeoutOrNull(ReadMillis) { runCatching { replies.markRead(chatId, pullFirst = true) } }
                }
            } catch (_: Throwable) {
                // Koin not up, a store that wouldn't open: answered below as not sent.
            } finally {
                withContext(NonCancellable) {
                    if (!answered) onMain { queued(false, null, true) }
                    onMain { finished(refused, title) }
                }
            }
        }
    }

    /** Marks [chatId] read, here and on the server, and takes its notifications down. [done] always comes. */
    fun markRead(chatId: String, done: () -> Unit) {
        val koin = ready()
        scope(koin).launch {
            try {
                val app = koin ?: error("Koin not started")
                runCatching { app.get<ChatNotifications>().clear(chatId) }
                withTimeoutOrNull(ReadMillis) { runCatching { app.get<NotificationReplies>().markRead(chatId, pullFirst = true) } }
            } catch (_: Throwable) {
            } finally {
                withContext(NonCancellable) { onMain(done) }
            }
        }
    }

    /** Launched in the background for the action, Circaso has no screen yet: its stores and services start here. */
    private fun ready(): Koin? = runCatching { initKoinIos(); KoinPlatform.getKoin() }.getOrNull()

    private fun scope(koin: Koin?): CoroutineScope = koin?.let { runCatching { it.get<CoroutineScope>() }.getOrNull() } ?: fallbackScope

    private suspend fun onMain(block: () -> Unit) { withContext(Dispatchers.Main) { runCatching { block() } } }

    /** Queuing is local writes; past this the reply counts as not sent and the person is told. */
    private const val QueueMillis = 8_000L

    /** iOS gives about 30 s in the background; the send and the inbox share what's left. */
    private const val SendMillis = 10_000L
    private const val ReadMillis = 8_000L
}
