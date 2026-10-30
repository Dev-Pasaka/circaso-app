package com.circaso.feature.support

import com.circaso.feature.messaging.domain.Chat

/**
 * Support (designs SP01–SP06) is a chat with a dedicated Circaso support account (decision 2026-10-31): it has no
 * wallet, can't sign in on the phones, and the team answers from the admin dashboard, which holds its keys on the
 * server. Until that account exists, no chat is treated as support.
 */
object Support {
    /**
     * The support account's user id, pinned in the app: handles can be claimed and changed, an id can't, so only this
     * account ever shows as Verified Circaso support. Pasaka creates the account and puts its id here; until then
     * Help says support isn't set up yet, and no chat is treated as support.
     */
    // None yet: Pasaka's own account was the stopgap and is an ordinary account again (2026-10-31). The dedicated
    // support account's id goes here once it exists (and later comes from its profile's support flag instead).
    var UserId: String? = null
    const val Handle = "circaso"
    const val Name = "Circaso Support"
    const val Hours = "Real people in Nairobi, every day from 7am to 10pm."
    const val NeverAsk = "Circaso will never ask for your PIN, recovery key or SMS code. Not in chat, not on a call."

    /** The direct chat whose other person is the support account. */
    fun isSupportChat(chat: Chat?, myUserId: String?): Boolean {
        val id = UserId ?: return false
        return chat != null && chat.peerId == id && id != myUserId
    }

    /** "What's it about?" (SP01): the first message says it, so whoever picks it up knows where to start. */
    enum class Topic(val label: String, val sub: String, val opener: String) {
        Payment("A payment", "Pending, sent to the wrong person, or missing", "Hi, I need help with a payment."),
        Wallet("My wallet", "Balance, networks, PIN or recovery", "Hi, I need help with my wallet."),
        Account("Signing in", "Your number, Google or Apple, or a new phone", "Hi, I need help with signing in or my account."),
        Group("A group goal", "Contributions, approvals or the treasurer", "Hi, I need help with a group goal."),
        Other("Something else", "Anything we haven't listed", "Hi, I need some help."),
    }
}
