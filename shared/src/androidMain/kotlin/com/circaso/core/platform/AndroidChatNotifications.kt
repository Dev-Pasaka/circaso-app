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
import com.circaso.core.notifications.NoticePerson
import com.circaso.core.notifications.NotificationContent
import com.circaso.core.notifications.NotificationDestination
import com.circaso.core.notifications.NotificationDestination.Companion.toExtras
import com.circaso.designsystem.components.avatarInitials
import com.circaso.designsystem.theme.DarkColors
import com.circaso.designsystem.theme.LightColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.mp.KoinPlatform

/**
 * Circaso's own notifications: messages on the "Messages" channel, money on "Money received" with its chime. Each
 * carries where a tap lands ([NotificationDestination], read back by MainActivity), so a tap opens the chat at the
 * message, the chat at the payment's card, and so on, never just the app.
 *
 * A chat message is a conversation notification (WhatsApp-style, 2026-11-13): MessagingStyle with the sender as a
 * [Person] carrying their round photo (initials when there's none), the group's name over a group's messages, one
 * notification per chat gathering its latest lines, Reply and Mark as read. Each chat is a long-lived conversation
 * shortcut published before the notification, so Android 11+ draws the photo as the notification's own icon with
 * Circaso's small badge in its corner. Money from a person is a conversation notification too, so their photo shows
 * beside the amount. The photo is also the large icon, for MIUI and Android below 11, which ignore the shortcut.
 * With Notification privacy on there is no [Notice.conversation] or [Notice.from]: the plain words show, with no
 * photo and no shortcut.
 */
class AndroidChatNotifications(private val context: Context) : ChatNotifications {
    override var activeChatId: String? = null

    private val manager get() = NotificationManagerCompat.from(context)

    /**
     * Its own loader (the app's singleton is set up by the UI, which may not be running), on Coil's process-wide disk
     * cache: a photo the chat list already downloaded is read from disk, keyed as the Avatar component keys it, and
     * one fetched here is kept there for next time.
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

    private fun allowed() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * The chat is open and Circaso is on screen. The open thread stays [activeChatId] when the person leaves Circaso
     * from it, and its messages must still be notified then.
     */
    private fun onScreen(chatId: String?): Boolean = chatId != null && chatId == activeChatId &&
        runCatching { KoinPlatform.getKoin().get<CurrentActivity>().inFront.value }.getOrDefault(true)

    override suspend fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed()) PermissionBridge.request(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun show(chatId: String, title: String, text: String, messageId: String?) {
        showPlain(Notice(NotificationContent(title, text), NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId), chatId))
    }

    /**
     * Shows [notice], replacing any earlier one with the same key (a payment's card and its `payment.received` are one
     * notification, and it chimes once). No message notification for the chat on screen. [coins] picks the money chime.
     * A chat message with its [Notice.conversation], or money with its [Notice.from], is a conversation notification;
     * its photos get [PhotoWaitMillis].
     */
    suspend fun show(notice: Notice, coins: Boolean = true) {
        if (!allowed()) return
        val conversation = notice.conversation
        val from = notice.from
        when {
            notice.channel == NoticeChannel.Message && conversation != null -> if (!onScreen(conversation.chatId)) showConversation(notice, conversation)
            notice.channel == NoticeChannel.Money && from != null -> showMoney(notice, from, coins)
            else -> showPlain(notice, coins)
        }
    }

    private fun showPlain(notice: Notice, coins: Boolean = true) {
        if (!allowed()) return
        if (notice.channel == NoticeChannel.Message && onScreen(notice.chatId)) return
        val id = notice.key.hashCode()
        val text = notice.content.text
        val notification = NotificationCompat.Builder(context, channelOf(notice.channel, coins))
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

    private fun channelOf(channel: NoticeChannel, coins: Boolean) = when (channel) {
        NoticeChannel.Money -> if (coins) MoneyChannelId else MoneyQuietChannelId
        else -> ChannelId
    }

    private fun dark() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Both photos at once, each from disk in the usual case; whatever isn't ready in time is drawn as initials. */
    private suspend fun photos(sender: NoticePerson, group: Boolean, groupName: String?, groupPhoto: String?): Pair<Bitmap, Bitmap?> {
        val dark = dark()
        // Structured, so the push window's own timeout cancels the loads too.
        // Each on its own clock, so a slow download never costs the other (a group photo is a local file).
        val loaded = coroutineScope {
            val senderPhoto = async(Dispatchers.IO) { withTimeoutOrNull(PhotoWaitMillis) { photo(sender.photo) } }
            val groupBitmap = async(Dispatchers.IO) { if (group) withTimeoutOrNull(PhotoWaitMillis) { photo(groupPhoto) } else null }
            senderPhoto.await() to groupBitmap.await()
        }
        val senderBitmap = loaded.first ?: initials(sender.name, dark)
        val groupBitmap = if (group) loaded.second ?: initials(groupName ?: "Group", dark) else null
        return senderBitmap to groupBitmap
    }

    private fun personOf(p: NoticePerson, photo: Bitmap) =
        Person.Builder().setName(p.name).setKey(p.userId ?: "name:${p.name}").setIcon(IconCompat.createWithBitmap(photo)).build()

    private suspend fun showConversation(notice: Notice, c: NoticeConversation) {
        val id = notice.key.hashCode()
        val (senderBitmap, groupBitmap) = photos(c.sender, c.group, c.title, c.groupPhoto)
        val sender = personOf(c.sender, senderBitmap)
        val previous = active(id)?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        // The same message twice (FCM delivering again): shown once, alerting once.
        val seen = c.messageId != null && previous?.messages?.any { it.extras.getString(ExtraMessageId) == c.messageId } == true
        val style = conversationStyle(c.group, c.title)
        previous?.messages?.takeLast(if (seen) MaxLines else MaxLines - 1)?.forEach { style.addMessage(it) }
        if (!seen) style.addMessage(NotificationCompat.MessagingStyle.Message(c.line, c.at, sender).also { m -> c.messageId?.let { m.extras.putString(ExtraMessageId, it) } })
        val chatIcon = groupBitmap ?: senderBitmap
        val shortcutId = conversationShortcut(c.chatId, c.group, if (c.group) c.title else c.sender.name, sender, IconCompat.createWithBitmap(chatIcon))
        post(id, ChannelId, c.chatId, notice.content, style, notice.destination, shortcutId, chatIcon, alert = !seen, actions = true)
    }

    /**
     * Money from a person: one line from them ("Sent you KES 1,300"), their photo as the icon. Not gathered with the
     * chat's messages: every payment is its own notification.
     *
     * A direct payment is a conversation notification on the chat's shortcut (the person's photo is the chat's).
     * A group's money (decided 2026-11-13) shows the SENDER's face on every phone: titled by the group, with no
     * conversation shortcut (that would draw the group's photo on Android 11+ and the sender's on MIUI); the sender's
     * photo is the person's icon and the large icon.
     */
    private suspend fun showMoney(notice: Notice, from: NoticePerson, coins: Boolean) {
        val id = notice.key.hashCode()
        val chatId = notice.chatId
        val group = notice.group
        val title = if (group) notice.content.title else null
        val (senderBitmap, _) = photos(from, false, null, null)
        val sender = personOf(from, senderBitmap)
        val line = notice.fromLine ?: notice.content.text
        val style = conversationStyle(group, title).addMessage(NotificationCompat.MessagingStyle.Message(line, System.currentTimeMillis(), sender))
        val shortcutId = if (group) null else chatId?.let { conversationShortcut(it, false, from.name, sender, IconCompat.createWithBitmap(senderBitmap)) }
        post(id, channelOf(NoticeChannel.Money, coins), chatId, notice.content, style, notice.destination, shortcutId, senderBitmap, alert = true, actions = false, money = true)
    }

    /** The style every chat notification starts from: me as the owner, the group's name on a group's. */
    private fun conversationStyle(group: Boolean, title: String?) =
        NotificationCompat.MessagingStyle(Person.Builder().setName("You").setKey(MeKey).build())
            .setGroupConversation(group)
            .also { if (group) it.setConversationTitle(title) }

    private fun post(
        id: Int,
        channel: String,
        chatId: String?,
        content: NotificationContent,
        style: NotificationCompat.MessagingStyle,
        destination: NotificationDestination?,
        shortcutId: String?,
        largeIcon: Bitmap?,
        alert: Boolean,
        actions: Boolean,
        money: Boolean = false,
    ) {
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.circaso.shared.R.drawable.ic_stat_circaso)
            // For surfaces that don't draw the style (some watches, older launchers).
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setStyle(style)
            .setCategory(if (money) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert || money)
            .setContentIntent(tapIntent(context, id, destination))
            .apply {
                if (!money) setGroup(Group)
                shortcutId?.let { setShortcutId(it) }
                chatId?.let { setLocusId(androidx.core.content.LocusIdCompat(it)) }
                // MIUI and Android below 11 don't draw the conversation's photo: there it is the large icon.
                largeIcon?.let { setLargeIcon(it) }
                if (actions && chatId != null) {
                    addAction(replyAction(id, chatId))
                    addAction(markReadAction(id, chatId))
                }
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
     * The chat as a long-lived conversation shortcut, published before the notification (Android 11+ needs one for a
     * conversation notification: the photo as its icon, the Conversations section, priority conversations). Opening
     * it opens the chat. Null when the launcher refuses or there's no name to give it.
     */
    private fun conversationShortcut(chatId: String, group: Boolean, label: String?, sender: Person, icon: IconCompat): String? {
        val id = ShortcutPrefix + chatId
        val name = label?.takeIf { it.isNotBlank() } ?: return null
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            NotificationDestination.Chat(chatId).toExtras().forEach { (k, v) -> putExtra(k, v) }
        } ?: return null
        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(name)
            .setLongLived(true)
            .setLocusId(androidx.core.content.LocusIdCompat(chatId))
            .setIcon(icon)
            .setIntent(open)
            .setCategories(setOf(ConversationCategory))
            .apply { if (!group) setPerson(sender) }
            .build()
        return runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut); id }.getOrNull()
    }

    /**
     * A profile or group photo, round and notification-sized; null when it can't be had (offline and not on disk). A
     * profile photo's link is pre-signed and changes: its path is the disk key, as the Avatar component keys it.
     */
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
        val letters = avatarInitials(name)
        if (letters.isNotEmpty()) {
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colors.ink.toArgb()
                textAlign = Paint.Align.CENTER
                textSize = PhotoPx * 0.36f
                // SemiBold, as the Avatar component's headline style.
                typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(Typeface.DEFAULT, 600, false)
                else Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
    /**
     * Returns whether the notification was re-posted. Android keeps the reply field spinning until the app posts the
     * notification again, so a caller that gets false must still settle it ([settleAfterReply]).
     */
    internal fun addMyReply(chatId: String, text: String, failed: Boolean = false): Boolean {
        val id = "chat:$chatId".hashCode()
        val mine = NotificationCompat.MessagingStyle.Message(if (failed) ReplyNotSent else text, System.currentTimeMillis(), null as Person?)
        val current = active(id)
        val style = current?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        if (current == null || style == null) {
            // The shown one couldn't be read (MIUI can hand back a stripped copy, or it was swiped meanwhile): post the
            // reply on its own, quietly, so the field still stops spinning and the person sees it went.
            val fresh = conversationStyle(group = false, title = null).addMessage(mine)
            return runCatching {
                post(
                    id, ChannelId, chatId, NotificationContent("Circaso", text), fresh, NotificationDestination.Chat(chatId),
                    current?.let { NotificationCompat.getShortcutId(it) }, largeIcon = null, alert = false, actions = true,
                )
            }.isSuccess
        }
        val updated = conversationStyle(style.isGroupConversation, style.conversationTitle?.toString())
        style.messages.takeLast(MaxLines - 1).forEach { updated.addMessage(it) }
        updated.addMessage(mine)
        val title = current.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
        // The photo stays (MIUI and Android below 11 draw it from the large icon).
        @Suppress("DEPRECATION")
        val photo = current.extras.getParcelable<Bitmap>(NotificationCompat.EXTRA_LARGE_ICON)
        return runCatching {
            post(
                id, ChannelId, chatId, NotificationContent(title, text), updated, NotificationDestination.Chat(chatId),
                NotificationCompat.getShortcutId(current), largeIcon = photo, alert = false, actions = true,
            )
        }.isSuccess
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
        private const val PhotoPx = 192
        /** A photo on disk takes a few milliseconds; one from the network gets this long, then initials. */
        private const val PhotoWaitMillis = 2_500L

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
            // Whatever happens below, the notification is settled at the end: Android spins the reply field until the
            // app posts or removes it, and a spinner that never stops reads as a reply that never went.
            var settled = false
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
                            settled = notifications?.addMyReply(chatId, text, failed = !queued) == true
                        }
                    }
                    AndroidChatNotifications.ActionMarkRead -> {
                        withTimeoutOrNull(ActionMillis) { messages.markRead(chatId) }
                        notifications?.clear(chatId)
                        settled = notifications != null
                    }
                }
            } catch (_: Exception) {
                // Nothing to tell anyone from here; the notification stays and the chat opens as usual.
            } finally {
                if (!settled) runCatching { NotificationManagerCompat.from(context).cancel("chat:$chatId".hashCode()) }
                pending.finish()
            }
        }
    }

    private companion object {
        /** A broadcast gets about ten seconds; both steps are local writes (the outbox sends on its own). */
        const val ActionMillis = 4_000L
    }
}
