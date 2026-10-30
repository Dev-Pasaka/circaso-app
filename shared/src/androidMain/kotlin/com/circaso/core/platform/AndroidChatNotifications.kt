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

/** One notification per chat on the "Messages" channel; tapping it opens the app on that chat. */
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
        if (!allowed() || chatId == activeChatId) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ExtraChatId, chatId)
        }
        val tap = open?.let { PendingIntent.getActivity(context, chatId.hashCode(), it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(context, ChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setGroup(Group)
            .build()
        runCatching { manager.notify(chatId.hashCode(), notification) }
    }

    override fun clear(chatId: String) { manager.cancel(chatId.hashCode()) }

    /** Money received, on the money channel: the coins sound, or the usual one when the person turned it off. */
    fun showMoney(title: String, text: String, coins: Boolean) {
        if (!allowed()) return
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val tap = open?.let { PendingIntent.getActivity(context, MoneyNotificationId, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val notification = NotificationCompat.Builder(context, if (coins) MoneyChannelId else MoneyQuietChannelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { manager.notify(MoneyNotificationId + (System.currentTimeMillis() % 1000).toInt(), notification) }
    }

    companion object {
        const val ChannelId = "messages"
        const val MoneyChannelId = "money"
        const val MoneyQuietChannelId = "money_quiet"
        private const val MoneyNotificationId = 7_000
        const val Group = "com.circaso.messages"
        const val ExtraChatId = "com.circaso.chatId"
    }
}
