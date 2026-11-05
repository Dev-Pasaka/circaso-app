package com.circaso.feature.spaces.presentation

/**
 * Where a spaces screen can go. Built once in `AppNavigation` over the back stack; the screens never see routes, so
 * each area (discover, page, create, chat, admin) is its own file set and stays out of the others' way.
 */
class SpacesNav(
    val back: () -> Unit,
    /** Back to the Spaces tab (SS6d's "Back to Spaces"), clearing what was opened over Home. */
    val home: () -> Unit,
    val openSpace: (spaceId: String) -> Unit,
    /** The space page with its join card open (SD1 Join, SH2e Join to Pay, a Renew). */
    val joinSpace: (spaceId: String) -> Unit,
    val openSpaceByHandle: (handle: String) -> Unit,
    /** The space chat (SH1); members only. */
    val openSpaceChat: (spaceId: String) -> Unit,
    /** An ordinary chat thread: the private chat with an owner (SH2), opened by the screen with `openViaSpace`. */
    val openChat: (chatId: String) -> Unit,
    val search: () -> Unit,
    val suggested: () -> Unit,
    val create: (name: String?) -> Unit,
    /** SN4–SN6; [created] right after SN3 (then SN6 "is live" follows). */
    val walletSetup: (spaceId: String, created: Boolean) -> Unit,
    val settings: (spaceId: String) -> Unit,
    val editDetails: (spaceId: String) -> Unit,
    val joinSettings: (spaceId: String) -> Unit,
    val members: (spaceId: String) -> Unit,
    val roles: () -> Unit,
    val report: (spaceId: String, postId: String?) -> Unit,
    val reports: (spaceId: String) -> Unit,
    val invite: (spaceId: String) -> Unit,
    val invites: (spaceId: String) -> Unit,
    val spaceWallet: (spaceId: String) -> Unit,
    /** SS6e: "tell Circaso through Asha". */
    val asha: () -> Unit,
    /** SM1g: Receive shows the personal wallet's addresses (the Wallet tab's Receive). */
    val receive: () -> Unit = {},
    /** The person's own profile in the app (a member row → their chat or profile). */
    val openPerson: (userId: String) -> Unit = {},
    /** A pushed wallet's own links (See All, assets, a payment), for the wallet on screen (SW1). */
    val walletActions: (walletId: String?) -> com.circaso.feature.wallet.presentation.WalletActions = { _ ->
        com.circaso.feature.wallet.presentation.WalletActions({}, {}, {}, {}, {})
    },
    /** SW1's Send: Pay, from that wallet (the space wallet sends directly; its owner's PIN). */
    val payFrom: (walletId: String) -> Unit = {},
) {
    companion object {
        /** Previews: every action does nothing. */
        val None = SpacesNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})
    }
}
