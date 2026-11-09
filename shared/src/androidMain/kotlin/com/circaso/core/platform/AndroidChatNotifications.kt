package com.circaso.core.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.ImageLoader
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import com.circaso.core.notifications.Notice
import com.circaso.core.notifications.NoticeChannel
import com.circaso.core.notifications.NoticeConversation
import com.circaso.core.notifications.NotificationContent
import com.circaso.core.notifications.NotificationDestination
import com.circaso.core.notifications.NotificationDestination.Companion.toExtras
import com.circaso.designsystem.theme.DarkColors
import com.circaso.designsystem.theme.LightColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform

/**
 * Circaso's own notifications: messages on the "Messages" channel, money on "Money received" with its chime. Each
 * carries where a tap lands ([NotificationDestination], read back by MainActivity), so a tap opens the chat at the
 * message, the chat at the payment's card, and so on, never just the app.
 *
 * A chat message is a conversation notification (WhatsApp-style, Android's MessagingStyle): the sender's name and
 * round photo (initials when there's none), the group's name over a group's messages, one notification per chat that
 * gathers its messages, Reply and Mark as read. The chat is a long-lived conversation shortcut, so Android 11+ lists
 * it under Conversations with the photo. With Notification privacy on there is no [Notice.conversation]: the plain
 * "New message" is shown and no shortcut is made.
 */
class AndroidChatNotifications(private val context: Context) : ChatNotifications {
    override var activeChatId: String? = null

    private val manager get() = NotificationManagerCompat.from(context)

    /**
     * Its own loader (the app's singleton is set up by the UI, which may not be running), on Coil's process-wide disk
     * cache: a photo the chat list already downloaded is read from disk, keyed as the Avatar component keys it.
     */
    private val images by lazy { ImageLoader.Builder(context).components { add(KtorNetworkFetcherFactory()) }.build() }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(ChannelId, "Messages", NotificationManager.IMPORTANCE_HIGH).apply { description = "New messages in your chats" }
            // Money gets its own channel and its own chime, so people know it's money before they look (the push for
            // money received names this channel). A channel's sound is fixed once made; people can still change it.
            val chime = android.net.Uri.parse("android.resource://${context.packageName}/raw/money_received")
            val audio = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val money = NotificationChannel(MoneyChannelId, "Money received", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone sends you money"
                setSound(chime, audio)
            }
            // With the coins sound turned off (You → Money sound), money uses the phone's usual notification sound.
            val quiet = NotificationChannel(MoneyQuietChannelId, "Money received (usual sound)", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone sends you money, with your phone's usual sound"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, money, quiet))
        }
    }

    /**
     * The chat is open and Circaso is on screen. The open thread stays [activeChatId] when the person leaves Circaso
     * from it, and its messages must still be notified then (they weren't, 2026-11-09).
     */
    private fun onScreen(chatId: String): Boolean = chatId == activeChatId &&
        runCatching { KoinPlatform.getKoin().get<com.circaso.core.realtime.PresenceReporter>().inForeground }.getOrDefault(true)

    private fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override suspend fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed()) PermissionBridge.request(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun show(chatId: String, title: String, text: String, messageId: String?) {
        showPlain(Notice(NotificationContent(title, text), NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId), chatId))
    }

    /**
     * Shows [notice], replacing any earlier one with the same key (a payment's card and its `payment.received` are one
     * notification, and it chimes once). No message notification for the chat on screen. [coins] picks the money chime.
     * A chat message with its [Notice.conversation] is a conversation notification; its photos get [PhotoWaitMillis].
     */
    suspend fun show(notice: Notice, coins: Boolean = true) {
        val conversation = notice.conversation
        if (conversation == null || notice.channel != NoticeChannel.Message) return showPlain(notice, coins)
        if (!allowed() || onScreen(conversation.chatId)) return
        showConversation(notice, conversation)
    }

    private fun showPlain(notice: Notice, coins: Boolean = true) {
        if (!allowed()) return
        if (notice.channel == NoticeChannel.Message && notice.chatId != null && onScreen(notice.chatId)) return
        val id = notice.key.hashCode()
        val channel = when (notice.channel) {
            NoticeChannel.Money -> if (coins) MoneyChannelId else MoneyQuietChannelId
            else -> ChannelId
        }
        val text = notice.content.text
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            .setContentTitle(notice.content.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (notice.channel == NoticeChannel.Money) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(notice.channel == NoticeChannel.Money)
            .setContentIntent(tapIntent(context, id, notice.destination))
            .apply { if (notice.channel == NoticeChannel.Message) setGroup(Group) }
            .build()
        runCatching { manager.notify(id, notification) }
    }

    private suspend fun showConversation(notice: Notice, c: NoticeConversation) {
        val id = notice.key.hashCode()
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // Both photos at once, each from disk in the usual case; whatever isn't ready in time is drawn as initials.
        // Structured, so the push window's own timeout cancels the loads too.
        val loaded = withTimeoutOrNull(PhotoWaitMillis) {
            coroutineScope {
                val senderPhoto = async(Dispatchers.IO) { photo(c.senderPhoto) }
                val groupPhoto = async(Dispatchers.IO) { if (c.group) photo(c.groupPhoto) else null }
                senderPhoto.await() to groupPhoto.await()
            }
        }
        val senderBitmap = loaded?.first ?: initials(c.senderName, dark)
        val groupBitmap = if (c.group) loaded?.second ?: initials(c.title ?: "Group", dark) else null
        val sender = Person.Builder().setName(c.senderName).setKey(c.senderId ?: "name:${c.senderName}")
            .setIcon(IconCompat.createWithBitmap(senderBitmap)).build()
        val previous = active(id)?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        // The same message twice (a push and a live frame, or FCM delivering again): shown once, alerting once.
        val seen = c.messageId != null && previous?.messages?.any { it.extras.getString(ExtraMessageId) == c.messageId } == true
        val style = conversationStyle(c.group, c.title)
        previous?.messages?.takeLast(if (seen) MaxLines else MaxLines - 1)?.forEach { style.addMessage(it) }
        if (!seen) style.addMessage(NotificationCompat.MessagingStyle.Message(c.line, c.at, sender).also { m -> c.messageId?.let { m.extras.putString(ExtraMessageId, it) } })
        val shortcutId = conversationShortcut(c, sender, IconCompat.createWithBitmap(groupBitmap ?: senderBitmap))
        // Below Android 9 MessagingStyle draws no person icons: the sender's photo goes beside a direct chat's lines.
        post(id, c.chatId, notice.content, style, notice.destination, shortcutId, largeIcon = groupBitmap ?: senderBitmap, alert = !seen)
    }

    /** The style every chat notification starts from: me as the owner, the group's name on a group's. */
    private fun conversationStyle(group: Boolean, title: String?) =
        NotificationCompat.MessagingStyle(Person.Builder().setName("You").setKey(MeKey).build())
            .setGroupConversation(group)
            .also { if (group) it.setConversationTitle(title) }

    private fun post(
        id: Int,
        chatId: String,
        content: NotificationContent,
        style: NotificationCompat.MessagingStyle,
        destination: NotificationDestination?,
        shortcutId: String?,
        largeIcon: Bitmap?,
        alert: Boolean,
    ) {
        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            // For surfaces that don't draw the style (some watches, older launchers).
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setGroup(Group)
            .setContentIntent(tapIntent(context, id, destination))
            .apply {
                shortcutId?.let { setShortcutId(it) }
                // Below Android 11 the group's photo has no other place; from 11 the conversation's shortcut carries it.
                if (largeIcon != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLargeIcon(largeIcon)
                addAction(replyAction(id, chatId))
                addAction(markReadAction(id, chatId))
            }
            .build()
        runCatching { manager.notify(id, notification) }
    }

    private fun replyAction(id: Int, chatId: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(ReplyKey).setLabel("Reply").build()
        val intent = PendingIntent.getBroadcast(
            context, id * 31 + 1,
            Intent(context, ChatNotificationActionReceiver::class.java).setAction(ActionReply).putExtra(ExtraChatId, chatId),
            // Mutable: the system writes the typed reply into it.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
        )
        return NotificationCompat.Action.Builder(com.circaso.shared.R.drawable.ic_stat_circaso, "Reply", intent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(id: Int, chatId: String): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context, id * 31 + 2,
            Intent(context, ChatNotificationActionReceiver::class.java).setAction(ActionMarkRead).putExtra(ExtraChatId, chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(com.circaso.shared.R.drawable.ic_stat_circaso, "Mark as read", intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * The chat as a long-lived conversation shortcut (Android 11+ needs one for the Conversations section, priority
     * conversations and the photo on the notification). Opening it opens the chat. Null when the launcher refuses.
     */
    private fun conversationShortcut(c: NoticeConversation, sender: Person, icon: IconCompat): String? {
        val id = ShortcutPrefix + c.chatId
        val label = (if (c.group) c.title else c.senderName)?.takeIf { it.isNotBlank() } ?: return null
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            NotificationDestination.Chat(c.chatId).toExtras().forEach { (k, v) -> putExtra(k, v) }
        } ?: return null
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(label)
            .setLongLived(true)
            .setIcon(icon)
            .setIntent(open)
            .setCategories(setOf(ConversationCategory))
            .apply { if (!c.group) setPerson(sender) }
            .build()
        return runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut); id }.getOrNull()
    }

    /** A profile or group photo, round and notification-sized; null when it can't be had (offline and not on disk). */
    private suspend fun photo(source: String?): Bitmap? {
        source ?: return null
        val key = source.substringBefore('?')
        val request = ImageRequest.Builder(context).data(source).diskCacheKey(key).memoryCacheKey("$key@notification")
            .size(PhotoPx).transformations(CircleCropTransformation()).allowHardware(false).build()
        val result = runCatching { images.execute(request) }.getOrNull() as? SuccessResult ?: return null
        return runCatching { result.image.toBitmap() }.getOrNull()
    }

    /** The Avatar component's look when there's no photo: initials on the strong fill, in the phone's light or dark. */
    private fun initials(name: String, dark: Boolean): Bitmap {
        val colors = if (dark) DarkColors else LightColors
        val bitmap = Bitmap.createBitmap(PhotoPx, PhotoPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.fillStrong.toArgb() }
        canvas.drawCircle(PhotoPx / 2f, PhotoPx / 2f, PhotoPx / 2f, fill)
        val letters = initialsOf(name)
        if (letters.isNotEmpty()) {
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.ink.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = PhotoPx * 0.36f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText(letters, PhotoPx / 2f, PhotoPx / 2f - (ink.descent() + ink.ascent()) / 2f, ink)
        }
        return bitmap
    }

    private fun active(id: Int): android.app.Notification? = runCatching {
        context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.id == id }?.notification
    }.getOrNull()

    /**
     * After a reply from the notification: my words join the conversation, quietly (Android expects the update, or the
     * reply field keeps spinning). [failed] says it couldn't be queued, in place of the words.
     */
    internal fun addMyReply(chatId: String, text: String, failed: Boolean = false) {
        val id = "chat:$chatId".hashCode()
        val current = active(id) ?: return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(current) ?: return
        val updated = conversationStyle(style.isGroupConversation, style.conversationTitle?.toString())
        style.messages.takeLast(MaxLines - 1).forEach { updated.addMessage(it) }
        updated.addMessage(NotificationCompat.MessagingStyle.Message(if (failed) ReplyNotSent else text, System.currentTimeMillis(), null as Person?))
        val title = current.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        post(
            id, chatId, NotificationContent(title, text), updated, NotificationDestination.Chat(chatId),
            NotificationCompat.getShortcutId(current), largeIcon = null, alert = false,
        )
    }

    override fun clear(chatId: String) { manager.cancel("chat:$chatId".hashCode()) }

    override fun clearShown() {
        runCatching {
            manager.activeNotifications
                .filter { NotificationCompat.getChannelId(it.notification) in setOf(ChannelId, MoneyChannelId, MoneyQuietChannelId) }
                .forEach { manager.cancel(it.tag, it.id) }
        }
        // Notification privacy is on, or the person signed out: the conversation shortcuts name people and show their
        // photos, so they go too, including the ones the system cached for Conversations, sharing and widgets.
        runCatching {
            val ids = ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED)
                .map { it.id }.filter { it.startsWith(ShortcutPrefix) }
            if (ids.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, ids)
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        }
    }

    companion object {
        const val ChannelId = "messages"
        const val MoneyChannelId = "money"
        const val MoneyQuietChannelId = "money_quiet"
        const val Group = "com.circaso.messages"
        const val ActionReply = "com.circaso.chat.REPLY"
        const val ActionMarkRead = "com.circaso.chat.MARK_READ"
        const val ExtraChatId = "com.circaso.chatId"
        const val ReplyKey = "com.circaso.reply"
        private const val ExtraMessageId = "com.circaso.messageId"
        private const val ShortcutPrefix = "chat:"
        private const val ReplyNotSent = "Your reply didn't send. Open the chat to try again."
        /** ShortcutInfo.SHORTCUT_CATEGORY_CONVERSATION (API 30); the string works on every version. */
        private const val ConversationCategory = "android.shortcut.conversation"
        private const val MeKey = "me"
        /** A chat's notification keeps its latest messages, as Android shows them. */
        private const val MaxLines = 7
        /** Big enough for the conversation avatar on a dense screen, small enough for the notification's limits. */
        private const val PhotoPx = 144
        /** A photo on disk takes a few milliseconds; the notification never waits long for one. */
        private const val PhotoWaitMillis = 1_500L

        internal fun initialsOf(name: String) =
            name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

        /**
         * Opens Circaso on [destination]: the launcher activity brought forward (single top, so a running app gets it in
         * `onNewIntent`), the destination in its extras. [request] keeps each notification's intent its own.
         */
        fun tapIntent(context: Context, request: Int, destination: NotificationDestination?): PendingIntent? {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                destination?.toExtras()?.forEach { (k, v) -> putExtra(k, v) }
            } ?: return null
            return PendingIntent.getActivity(context, request, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

/**
 * Reply and Mark as read on a chat notification, without opening Circaso. A reply goes through the outbox like one
 * typed in the chat (it waits for a connection if there is none) and joins the notification; Mark as read clears it.
 */
class ChatNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getStringExtra(AndroidChatNotifications.ExtraChatId) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val koin = KoinPlatform.getKoin()
                val messages = koin.get<com.circaso.feature.messaging.domain.MessagesRepository>()
                val notifications = koin.get<ChatNotifications>() as? AndroidChatNotifications
                when (intent.action) {
                    AndroidChatNotifications.ActionReply -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AndroidChatNotifications.ReplyKey)?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            // Queued in the outbox (it sends on its own, now or once there's a connection), or not at all.
                            val queued = runCatching { withTimeoutOrNull(ActionMillis) { messages.send(chatId, text) } != null }.getOrDefault(false)
                            // Replying is reading: the chat's unread count and its receipt follow.
                            if (queued) runCatching { withTimeoutOrNull(ActionMillis) { messages.markRead(chatId) } }
                            notifications?.addMyReply(chatId, text, failed = !queued)
                        }
                    }
                    AndroidChatNotifications.ActionMarkRead -> {
                        withTimeoutOrNull(ActionMillis) { messages.markRead(chatId) }
                        notifications?.clear(chatId)
                    }
                }
            } catch (_: Exception) {
                // Nothing to tell anyone from here; the notification stays and the chat opens as usual.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A broadcast gets about ten seconds; both steps are local writes (the outbox sends on its own). */
        const val ActionMillis = 4_000L
    }
}

/**
 * Circaso in the background with its socket still open gets no push for a new message (the server pushes only to
 * phones without a socket), so it words and shows the message itself, exactly as a push would. Nothing while Circaso
 * is on screen: the chat list and the open thread show it there.
 */
class AndroidLiveMessageNotifier : com.circaso.core.notifications.LiveMessageNotifier {
    /** On the app's scope, so the socket's next frames never wait on the wording or the photos. */
    override fun notify(chatId: String, messageId: String) {
        val koin = KoinPlatform.getKoin()
        if (koin.get<com.circaso.core.realtime.PresenceReporter>().inForeground) return
        koin.get<CoroutineScope>().launch { show(koin, chatId, messageId) }
    }

    private suspend fun show(koin: org.koin.core.Koin, chatId: String, messageId: String) {
        val notifications = koin.get<ChatNotifications>() as? AndroidChatNotifications ?: return
        val notice = withTimeoutOrNull(WindowMillis) {
            // Already on this phone: no inbox pull.
            koin.get<com.circaso.core.notifications.PushNotices>().forPush(mapOf("chatId" to chatId, "messageId" to messageId), sync = false)
        } ?: return
        val coinsOff = withTimeoutOrNull(1_000) {
            koin.get<com.circaso.core.datastore.AppPreferences>().flag(com.circaso.core.datastore.AppPreferences.MoneySoundOff).first()
        } ?: false
        notifications.show(notice, coins = !coinsOff)
    }

    private companion object { const val WindowMillis = 8_000L }
}
