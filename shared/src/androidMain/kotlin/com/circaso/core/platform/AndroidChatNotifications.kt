package com.circaso.core.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.circaso.core.notifications.Notice
import com.circaso.core.notifications.NoticeChannel
import com.circaso.core.notifications.NotificationContent
import com.circaso.core.notifications.NotificationDestination
import com.circaso.core.notifications.NotificationDestination.Companion.toExtras

/**
 * Circaso's own notifications: messages on the "Messages" channel, money on "Money received" with its chime. Each
 * carries where a tap lands ([NotificationDestination], read back by MainActivity), so a tap opens the chat at the
 * message, the chat at the payment's card, and so on, never just the app.
 */
class AndroidChatNotifications(private val context: Context) : ChatNotifications {
    override var activeChatId: String? = null

    private val manager get() = NotificationManagerCompat.from(context)

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

    override suspend fun ensurePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !allowed()) PermissionBridge.request(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun show(chatId: String, title: String, text: String, messageId: String?) {
        show(Notice(NotificationContent(title, text), NoticeChannel.Message, "chat:$chatId", NotificationDestination.Chat(chatId, messageId), chatId))
    }

    /**
     * Shows [notice], replacing any earlier one with the same key (a payment's card and its `payment.received` are one
     * notification, and it chimes once). No message notification for the chat on screen. [coins] picks the money chime.
     */
    fun show(notice: Notice, coins: Boolean = true) {
        if (!allowed()) return
        if (notice.channel == NoticeChannel.Message && notice.chatId != null && notice.chatId == activeChatId) return
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

    override fun clear(chatId: String) { manager.cancel("chat:$chatId".hashCode()) }

    override fun clearShown() {
        runCatching {
            manager.activeNotifications
                .filter { NotificationCompat.getChannelId(it.notification) in setOf(ChannelId, MoneyChannelId, MoneyQuietChannelId) }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    companion object {
        const val ChannelId = "messages"
        const val MoneyChannelId = "money"
        const val MoneyQuietChannelId = "money_quiet"
        const val Group = "com.circaso.messages"

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
