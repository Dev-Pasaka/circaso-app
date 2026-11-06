package com.circaso.core.notifications

/**
 * Where tapping a notification takes the person (Pasaka, 2026-11-06): never just the home screen. Carried as a flat
 * string map so it fits an Android intent's extras and an iOS notification's `userInfo` alike.
 */
sealed interface NotificationDestination {
    /** A chat, at [messageId] (a server id) or [localId] (this phone's row) when known. */
    data class Chat(val chatId: String, val messageId: String? = null, val localId: Long? = null) : NotificationDestination

    /**
     * A payment: the chat holding its card, scrolled to the card; with no chat, the payment's details (by [transferId],
     * or the history row's [transactionId] for money from outside Circaso).
     */
    data class Payment(val chatId: String? = null, val transferId: String? = null, val transactionId: String? = null) : NotificationDestination

    /** A missed call: the chat it was in, at its call row (calls are kept in the chat, WhatsApp-style). */
    data class MissedCall(val chatId: String?, val callId: String? = null) : NotificationDestination

    /** A person's moment; [posterId] null is my own (a reaction to my moment). */
    data class Moment(val posterId: String? = null, val momentId: String? = null) : NotificationDestination

    data object Asha : NotificationDestination
    data object Security : NotificationDestination
    data object Contacts : NotificationDestination
    data object Wallet : NotificationDestination
    data object Profile : NotificationDestination
    data object NeedsAttention : NotificationDestination

    /** The notifications inbox (F4): where anything without a better home can be read. */
    data object Inbox : NotificationDestination

    companion object {
        const val Prefix = "circaso.open."
        private const val To = Prefix + "to"

        /** The extras an app-made notification carries for its tap. */
        fun NotificationDestination.toExtras(): Map<String, String> = buildMap {
            fun put(key: String, value: Any?) { if (value != null) this@buildMap[Prefix + key] = value.toString() }
            when (val d = this@toExtras) {
                is Chat -> { put("to", "chat"); put("chatId", d.chatId); put("messageId", d.messageId); put("localId", d.localId) }
                is Payment -> { put("to", "payment"); put("chatId", d.chatId); put("transferId", d.transferId); put("transactionId", d.transactionId) }
                is MissedCall -> { put("to", "missedCall"); put("chatId", d.chatId); put("callId", d.callId) }
                is Moment -> { put("to", "moment"); put("posterId", d.posterId); put("momentId", d.momentId) }
                Asha -> put("to", "asha")
                Security -> put("to", "security")
                Contacts -> put("to", "contacts")
                Wallet -> put("to", "wallet")
                Profile -> put("to", "profile")
                NeedsAttention -> put("to", "needsAttention")
                Inbox -> put("to", "inbox")
            }
        }

        /**
         * The destination in a tapped notification's extras: one the app made ([toExtras]), else a push the system
         * showed by itself (its data keys arrive as extras). Null for an ordinary launch.
         */
        fun fromExtras(extras: Map<String, String>): NotificationDestination? {
            val to = extras[To] ?: return fromPush(extras)
            fun get(key: String) = extras[Prefix + key]?.takeIf { it.isNotBlank() }
            return when (to) {
                "chat" -> get("chatId")?.let { Chat(it, get("messageId"), get("localId")?.toLongOrNull()) }
                "payment" -> Payment(get("chatId"), get("transferId"), get("transactionId"))
                "missedCall" -> MissedCall(get("chatId"), get("callId"))
                "moment" -> Moment(get("posterId"), get("momentId"))
                "asha" -> Asha
                "security" -> Security
                "contacts" -> Contacts
                "wallet" -> Wallet
                "profile" -> Profile
                "needsAttention" -> NeedsAttention
                else -> Inbox
            }
        }

        /**
         * A push's data (guide section 8: ids only, plus `kind`), as the server sends it. A ringing call is null: its own
         * notification answers it. An unknown kind still opens somewhere it can be read: its chat, else the inbox.
         */
        fun fromPush(data: Map<String, String>): NotificationDestination? {
            fun get(key: String) = data[key]?.takeIf { it.isNotBlank() }
            val kind = get("kind")
            val chatId = get("chatId")
            return when {
                kind == "call.ringing" -> null
                kind == "payment.received" || kind == "transfer.updated" -> Payment(chatId, get("transferId"), get("transactionId"))
                kind == "call.missed" || kind == "call.ended" -> MissedCall(chatId, get("callId"))
                kind?.startsWith("chama.") == true -> chatId?.let { Chat(it) } ?: Wallet
                kind == "moment.loved" -> Moment(null, get("momentId"))
                kind?.startsWith("moment.") == true -> Moment(get("userId"), get("momentId"))
                kind == "contact.joined" || kind == "connection.added" -> Contacts
                kind == "wallet.setup" -> Wallet
                kind == "session.revoked" -> null // signed out: the app opens on sign-in, which says so
                kind?.startsWith("security.") == true -> Security
                kind?.startsWith("asha") == true -> Asha
                chatId != null -> Chat(chatId, get("messageId"))
                kind != null -> Inbox
                // A system-shown push from an older server with no kind and no ids: still somewhere it can be read.
                get("google.message_id") != null || get("gcm.notification.title") != null -> Inbox
                else -> null
            }
        }
    }
}
