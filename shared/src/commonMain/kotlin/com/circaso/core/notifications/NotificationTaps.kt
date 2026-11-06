package com.circaso.core.notifications

import com.circaso.core.navigation.ActivityRoute
import com.circaso.core.navigation.AshaRoute
import com.circaso.core.navigation.ChatRoute
import com.circaso.core.navigation.ContactsRoute
import com.circaso.core.navigation.EditProfileRoute
import com.circaso.core.navigation.MomentViewerRoute
import com.circaso.core.navigation.NeedsAttentionRoute
import com.circaso.core.navigation.NotificationsRoute
import com.circaso.core.navigation.Route
import com.circaso.core.navigation.SecurityRoute
import com.circaso.core.navigation.TransferRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The notification the person just tapped, waiting for the app to show it. Platforms put it here (Android's
 * MainActivity from the intent, cold or warm; iOS's notification delegate), before or after the UI exists; the
 * navigation takes it once someone is signed in and Home is underneath. Only the newest tap is kept.
 */
object NotificationTaps {
    private val _pending = MutableStateFlow<NotificationDestination?>(null)
    val pending: StateFlow<NotificationDestination?> = _pending.asStateFlow()

    fun open(destination: NotificationDestination) {
        // The in-app browser sits over everything; close it so the place the tap opens is what they see.
        com.circaso.core.web.InAppBrowser.close()
        _pending.value = destination
    }

    /** The extras or `userInfo` of a tapped notification; false when they name nowhere (an ordinary launch). */
    fun openFrom(extras: Map<String, String>): Boolean {
        val destination = NotificationDestination.fromExtras(extras) ?: return false
        open(destination)
        return true
    }

    /** Handled (or dropped on sign-out): only clears [destination] if nothing newer replaced it meanwhile. */
    fun consumed(destination: NotificationDestination) { _pending.compareAndSet(destination, null) }
}

/** What opening a destination means on screen: [routes] pushed over Home, or one of Home's tabs ([tab]). */
data class NotificationOpening(val routes: List<Route>, val tab: HomeTabName? = null)

enum class HomeTabName { Chats, Wallet, You }

/** What the phone knows that decides exactly where a tap lands (the card's row, the call's row). */
interface NotificationLookups {
    suspend fun messageLocalId(messageId: String): Long?
    suspend fun paymentCardLocalId(chatId: String, transferId: String): Long?
    suspend fun lastCallLocalId(chatId: String): Long?
    /** The chat a payment was made in, from the wallet's own record of it. */
    suspend fun chatOfPayment(key: String): String?
}

object NotificationRoutes {
    suspend fun resolve(destination: NotificationDestination, lookups: NotificationLookups): NotificationOpening = when (destination) {
        is NotificationDestination.Chat -> NotificationOpening(
            listOf(ChatRoute(destination.chatId, destination.localId ?: destination.messageId?.let { lookups.messageLocalId(it) })),
        )
        is NotificationDestination.Payment -> {
            val key = destination.transferId ?: destination.transactionId
            val chat = destination.chatId ?: key?.let { lookups.chatOfPayment(it) }
            when {
                chat != null -> NotificationOpening(listOf(ChatRoute(chat, destination.transferId?.let { lookups.paymentCardLocalId(chat, it) })))
                key != null -> NotificationOpening(listOf(TransferRoute(key)))
                else -> NotificationOpening(listOf(ActivityRoute()))
            }
        }
        is NotificationDestination.MissedCall -> destination.chatId
            ?.let { NotificationOpening(listOf(ChatRoute(it, lookups.lastCallLocalId(it)))) }
            ?: NotificationOpening(listOf(NotificationsRoute))
        is NotificationDestination.Moment -> NotificationOpening(listOf(MomentViewerRoute(destination.posterId, destination.momentId)))
        NotificationDestination.Asha -> NotificationOpening(listOf(AshaRoute))
        NotificationDestination.Security -> NotificationOpening(listOf(SecurityRoute))
        NotificationDestination.Contacts -> NotificationOpening(listOf(ContactsRoute))
        NotificationDestination.Wallet -> NotificationOpening(emptyList(), HomeTabName.Wallet)
        NotificationDestination.Profile -> NotificationOpening(listOf(EditProfileRoute))
        NotificationDestination.NeedsAttention -> NotificationOpening(listOf(NeedsAttentionRoute))
        NotificationDestination.Inbox -> NotificationOpening(listOf(NotificationsRoute))
    }
}

/** A tab Home should switch to (a tapped "set up your wallet" opens the Wallet tab). Taken once by Home. */
object HomeTabRequests {
    val wanted = MutableStateFlow<HomeTabName?>(null)
}

/**
 * Keeps a copy of Notification privacy where code outside the app can read it: iOS's Notification Service Extension
 * (which words pushes before Circaso runs) reads it from the App Group's defaults. Android words every push inside the
 * app, so it has nothing to do.
 */
expect fun mirrorNotificationPrivacy(on: Boolean)

/**
 * Whether Notification privacy can keep its promise here, so You shows the setting. Android words every notification
 * on the phone. iOS needs its Notification Service Extension (with APNs, M7) to word pushes, so it's false there
 * until that ships.
 */
expect val notificationPrivacySupported: Boolean
