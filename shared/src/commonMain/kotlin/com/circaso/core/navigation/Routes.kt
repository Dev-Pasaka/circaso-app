package com.circaso.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** Every screen in the app. One `@Serializable` key per screen; data in the key, never objects. */
@Serializable
sealed interface Route : NavKey

/** Blank launch screen while the saved session is read. */
@Serializable
data object SplashRoute : Route

@Serializable
data object WelcomeRoute : Route

@Serializable
data object PhoneRoute : Route

/** Carries what the code screen needs; the full number stays inside the app's own saved state, never in a log. */
@Serializable
data class CodeRoute(
    val phone: String,
    val verificationId: String,
    val codeLength: Int,
    val expiresInSeconds: Int,
    val resendAfterSeconds: Int,
) : Route

@Serializable
data object RegisteringRoute : Route

/** The app once signed in: the tab bar with Chats, Wallet and You. */
@Serializable
data object HomeRoute : Route

@Serializable
data object EditProfileRoute : Route

@Serializable
data object ContactsRoute : Route

@Serializable
data object BlockedRoute : Route

@Serializable
data object SecurityRoute : Route

@Serializable
data object SecurityLogRoute : Route

@Serializable
data object AshaRoute : Route

/** Talking to Asha hands-free (guide A11); leaving it ends the conversation. */
@Serializable
data object VoiceRoute : Route

/** One chat, direct or group (designs C2, D2). */
@Serializable
data class ChatRoute(
    val chatId: String,
    /** Open the thread at this message (a search result, a shared video) rather than at the newest. */
    val focusLocalId: Long? = null,
) : Route

/** Changes that couldn't be saved, opened from the You tab. */
@Serializable
data object NeedsAttentionRoute : Route

/** A location message's map: the tapped place and everyone sharing live in the chat. */
@Serializable
data class LocationMapRoute(val chatId: String, val focusLocalId: Long) : Route

@Serializable
data object NewChatRoute : Route

@Serializable
data object NewGroupRoute : Route

@Serializable
data class ChatInfoRoute(val chatId: String) : Route

/** Pay a person (design C4/C5): [userId] null means pick one first; [chatId] when paying from that chat. */
@Serializable
data class PayRoute(val userId: String? = null, val chatId: String? = null, val displayName: String? = null, val amount: String? = null, val address: String? = null, val goalName: String? = null, val requestId: String? = null) : Route

/** Send to an outside wallet address (design E5). */
@Serializable
data object SendToWalletRoute : Route

/** One payment (design C7). */
@Serializable
data class TransferRoute(val transferId: String) : Route

/** The chains the wallet is on and can join (W12). */
@Serializable
data object NetworksRoute : Route

/** A group's shared goal: the group wallet (design D4) and its requests. */
@Serializable
data class ChamaRoute(val chatId: String) : Route

/** The inbox (design F4), built on the phone from what it knows. */
@Serializable
data object NotificationsRoute : Route

/** Design SP01. */
@Serializable
data object HelpRoute : Route

/** Design SP06: help for someone whose number belongs to an account they can't sign in to. */
@Serializable
data class LostAccessRoute(val phone: String? = null) : Route

/** Every payment this phone knows (design E7). */
@Serializable
data object ActivityRoute : Route

/** Asha's voice: pick one, hear it first. */
@Serializable
data object AshaVoiceRoute : Route

/** The currency amounts are shown in. */
@Serializable
data object CurrencyRoute : Route

/** One asset's statement on one network, opened from its row under the wallet card. */
@Serializable
data class AssetActivityRoute(val blockchain: String, val symbol: String) : Route

/** One network's payments: See All under the recent payments, for the card in front. */
@Serializable
data class NetworkActivityRoute(val blockchain: String) : Route

/** Every asset on one network, searchable: See All under a wallet card. */
@Serializable
data class NetworkAssetsRoute(val blockchain: String) : Route

/** Debug only: a screen shown with sample data (`-preview=you` launch argument). */
@Serializable
data class PreviewRoute(val name: String) : Route

/** Debug component gallery. Goes away when the first real flow lands. */
@Serializable
data object GalleryRoute : Route

/** Shown when the server says this build is too old (`426 request.client_outdated`). */
@Serializable
data object ForceUpdateRoute : Route

/** Tells the saved back stack how to (de)serialize each route. Add every new route here. */
val routeSerializers = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(SplashRoute::class)
        subclass(WelcomeRoute::class)
        subclass(PhoneRoute::class)
        subclass(CodeRoute::class)
        subclass(RegisteringRoute::class)
        subclass(HomeRoute::class)
        subclass(EditProfileRoute::class)
        subclass(ContactsRoute::class)
        subclass(BlockedRoute::class)
        subclass(SecurityRoute::class)
        subclass(SecurityLogRoute::class)
        subclass(AshaRoute::class)
        subclass(VoiceRoute::class)
        subclass(ChatRoute::class)
        subclass(NeedsAttentionRoute::class)
        subclass(LocationMapRoute::class)
        subclass(NewChatRoute::class)
        subclass(NewGroupRoute::class)
        subclass(ChatInfoRoute::class)
        subclass(PayRoute::class)
        subclass(SendToWalletRoute::class)
        subclass(TransferRoute::class)
        subclass(NetworksRoute::class)
        subclass(ActivityRoute::class)
        subclass(AssetActivityRoute::class)
        subclass(NetworkAssetsRoute::class)
        subclass(NetworkActivityRoute::class)
        subclass(CurrencyRoute::class)
        subclass(AshaVoiceRoute::class)
        subclass(NotificationsRoute::class)
        subclass(HelpRoute::class)
        subclass(LostAccessRoute::class)
        subclass(ChamaRoute::class)
        subclass(PreviewRoute::class)
        subclass(GalleryRoute::class)
        subclass(ForceUpdateRoute::class)
    }
}
