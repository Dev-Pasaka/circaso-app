package com.circaso.core.navigation

import androidx.navigation3.runtime.NavKey

/**
 * The screen a person summoned Asha on, as she is told it ("Wallet › Send"), so "how do I do this?" needs no
 * explaining (2026-11-09). Names only, never what is on the screen; null for screens Asha needn't know (sign-in, her own).
 * [homeTab] is Home's tab (0 Chats, 1 Wallet, 2 You).
 */
fun screenName(route: NavKey?, homeTab: Int): String? = when (route) {
    HomeRoute -> when (homeTab) { 0 -> "Chats"; 1 -> "Wallet"; 2 -> "You"; else -> null }
    is ChatRoute -> "Chats › A chat"
    is ChatInfoRoute -> "Chats › Chat info"
    is ChatMediaRoute -> "Chats › Chat media"
    is LocationMapRoute -> "Chats › Live location map"
    NeedsAttentionRoute -> "Chats › Needs attention"
    NewChatRoute -> "Chats › New chat"
    NewGroupRoute -> "Chats › New group"
    ShareTargetRoute -> "Sharing into Circaso"
    is PayRoute -> when {
        route.requestId != null -> "Group wallet › Pay a request"
        route.groupId != null -> "Group wallet › Add money"
        route.fromWalletId != null -> "Group wallet › Pay out"
        else -> "Wallet › Send"
    }
    is SendToWalletRoute -> "Wallet › Send to a wallet address"
    is TransferRoute -> "Wallet › Payment details"
    is ActivityRoute -> "Wallet › Activity"
    NetworksRoute -> "Wallet › Networks"
    is AssetActivityRoute -> "Wallet › A coin's activity"
    is NetworkActivityRoute -> "Wallet › A network's activity"
    is NetworkAssetsRoute -> "Wallet › A network's coins"
    is GroupWalletRoute -> "Group wallet"
    is GroupWalletSetupRoute -> "Group wallet › Setting it up"
    is TargetRoute -> "Group wallet › Savings target"
    is ChamaRoute -> "Chama"
    ContactsRoute -> "Contacts"
    MomentComposerRoute -> "Moments › New moment"
    is MomentViewerRoute -> "Moments › Watching a moment"
    MomentsPrivacyRoute -> "Moments › Who can see my moments"
    EditProfileRoute -> "You › Edit profile"
    BlockedRoute -> "You › Blocked people"
    SecurityRoute -> "You › Security"
    SecurityLogRoute -> "You › Security activity"
    NotificationsRoute -> "Notifications"
    HelpRoute -> "You › Help"
    CurrencyRoute -> "You › Currency"
    SharedLinksRoute -> "You › Shared links"
    is SharedLinkRoute -> "A shared link"
    else -> null
}
