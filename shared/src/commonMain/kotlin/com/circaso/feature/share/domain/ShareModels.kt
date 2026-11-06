package com.circaso.feature.share.domain

/**
 * Share links (decided by Pasaka, 2026-11-06): every share in the app is a short link on a Circaso domain that opens a
 * formatted page, never plain text. The server builds the page from its own records (so a receipt can't be forged)
 * and keeps only what the sharer chose to show. Moments are end-to-end encrypted, so they are never shared as links.
 */
enum class ShareKind(val apiName: String) {
    Receipt("receipt"),
    Statement("statement"),
    Invite("invite"),
    PayMe("pay_me"),
    Address("address"),
    ;

    companion object {
        fun from(apiName: String): ShareKind? = entries.firstOrNull { it.apiName == apiName }
    }
}

/** How long a link works. [days] null: until it is turned off. */
enum class LinkEnds(val label: String, val days: Int?) {
    Never("Never", null),
    OneDay("1 day", 1),
    OneWeek("7 days", 7),
    OneMonth("30 days", 30),
}

/**
 * What the person asked to share. Receipts, statements and addresses first show the options sheet ([hasOptions]);
 * an invite and "pay me" go straight to the share sheet.
 */
sealed interface ShareTarget {
    val kind: ShareKind

    /** One line of what the page will show, for the options sheet. */
    val preview: String

    val hasOptions: Boolean get() = true

    data class Receipt(val transferId: String, override val preview: String, val currency: String? = null) : ShareTarget {
        override val kind get() = ShareKind.Receipt
    }

    data class Statement(val chatId: String, override val preview: String, val currency: String? = null) : ShareTarget {
        override val kind get() = ShareKind.Statement
    }

    data class Address(val blockchain: String, override val preview: String) : ShareTarget {
        override val kind get() = ShareKind.Address
    }

    data object PayMe : ShareTarget {
        override val kind get() = ShareKind.PayMe
        override val preview get() = "Your name and @handle, with a button to pay you"
        override val hasOptions get() = false
    }

    data object Invite : ShareTarget {
        override val kind get() = ShareKind.Invite
        override val preview get() = "Your name, with a button to chat with you"
        override val hasOptions get() = false
    }
}

/** SH1's body, as the domain sees it. */
data class ShareRequest(
    val kind: ShareKind,
    val transferId: String? = null,
    val chatId: String? = null,
    val blockchain: String? = null,
    val showNames: Boolean? = null,
    val currency: String? = null,
    val expiresInDays: Int? = null,
) {
    companion object {
        /** The request for [target]; names and the end only where the kind takes them. */
        fun of(target: ShareTarget, showNames: Boolean = true, ends: LinkEnds = LinkEnds.Never): ShareRequest = when (target) {
            is ShareTarget.Receipt -> ShareRequest(target.kind, transferId = target.transferId, showNames = showNames, currency = target.currency, expiresInDays = ends.days)
            is ShareTarget.Statement -> ShareRequest(target.kind, chatId = target.chatId, showNames = showNames, currency = target.currency, expiresInDays = ends.days)
            is ShareTarget.Address -> ShareRequest(target.kind, blockchain = target.blockchain, showNames = showNames, expiresInDays = ends.days)
            ShareTarget.PayMe -> ShareRequest(ShareKind.PayMe)
            // The invite ends with its own 7-day invite on the server.
            ShareTarget.Invite -> ShareRequest(ShareKind.Invite)
        }
    }
}

/** One link the person made (SH1, SH2). */
data class ShareLink(
    val code: String,
    val url: String,
    val kind: ShareKind?,
    val title: String,
    val summary: String,
    val createdAt: String,
    val expiresAt: String?,
)

/** What the share sheet opens with: one short line and the link. */
data class ShareMessage(val text: String, val subject: String)

object ShareText {
    const val InviteSubject = "Join me on Circaso"

    fun of(link: ShareLink): ShareMessage = when (link.kind) {
        ShareKind.Receipt -> ShareMessage("Payment receipt on Circaso: ${link.url}", "Circaso payment")
        ShareKind.Statement -> ShareMessage("Group statement on Circaso: ${link.url}", link.title)
        ShareKind.Invite -> ShareMessage("Join me on Circaso. We can chat and send money to each other, end to end encrypted. ${link.url}", InviteSubject)
        ShareKind.PayMe -> ShareMessage("Pay me on Circaso: ${link.url}", "Pay me on Circaso")
        ShareKind.Address -> ShareMessage("My USDC address on Circaso: ${link.url}", link.title)
        null -> ShareMessage("${link.title}: ${link.url}", link.title)
    }
}

/** SH4: a link opened on this phone, with what its page shows and what this person can open from it. */
data class SharedLink(
    val code: String,
    val url: String,
    val kind: ShareKind?,
    val title: String,
    val summary: String,
    val createdAt: String,
    val expiresAt: String?,
    val mine: Boolean,
    val receipt: SharedReceipt? = null,
    val statement: SharedStatement? = null,
    val person: SharedPerson? = null,
    val address: SharedAddress? = null,
    val open: OpenTarget? = null,
)

data class SharedFiat(val currency: String, val amount: String)

data class SharedReceipt(
    val sent: Boolean,
    val amount: String,
    val asset: String,
    val fiat: SharedFiat?,
    val status: String,
    val blockchain: String,
    val txHash: String?,
    val explorerUrl: String?,
    val at: String,
    val fromName: String?,
    val toName: String?,
)

data class SharedStatementLine(val at: String, val incoming: Boolean, val amount: String, val name: String?, val note: String?)

data class SharedStatement(
    val goalName: String,
    val asset: String,
    val target: String,
    val raised: String,
    val paidOut: String,
    val held: String,
    val fiatCurrency: String?,
    val fiatRate: String?,
    val memberCount: Int,
    val lines: List<SharedStatementLine>,
)

data class SharedPerson(val displayName: String, val handle: String?)

data class SharedAddress(val blockchain: String, val address: String, val asset: String, val name: String?)

/** Where the app can take this person from a link; the server decides (a party to the payment, a group member). */
sealed interface OpenTarget {
    data class Transfer(val transferId: String) : OpenTarget
    data class Chama(val chatId: String) : OpenTarget
    data class Invite(val token: String, val displayName: String?) : OpenTarget
    data class Pay(val userId: String?, val handle: String?, val displayName: String?) : OpenTarget
    data class PayAddress(val address: String, val blockchain: String?, val displayName: String?) : OpenTarget
}
