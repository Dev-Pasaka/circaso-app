package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.contacts.domain.Person
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.wallet.domain.CheckRecipientUseCase
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.PayUseCase
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RemindOutcome
import com.circaso.feature.wallet.domain.RemindRecipientUseCase
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.fiatToUsdcMinor
import com.circaso.feature.wallet.domain.shortAddress
import com.circaso.feature.wallet.domain.usdcToFiat
import com.circaso.feature.chama.domain.AlreadyLinkedCodes
import com.circaso.feature.chama.domain.AttachChamaPaymentUseCase
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Where the payment is (designs C4 → C5 → C6). */
enum class PayStep { Pick, Amount, Signing, Sending, Done }

/**
 * Who is being paid and why: a person (C4), a person or an address from a chat, a contribution to a group's goal
 * (D3: `goalName`, the treasurer as `userId`), or a treasurer paying an approved request (D5: `requestId`).
 */
data class PayIntent(
    val userId: String? = null,
    val chatId: String? = null,
    val displayName: String? = null,
    val amount: String? = null,
    val address: String? = null,
    val goalName: String? = null,
    val requestId: String? = null,
    /** Group wallets: pay into this group's wallet (a contribution from its chat, GW9a2/GW9d). */
    val groupId: String? = null,
    /** Group wallets: pay from this group wallet; with [requestId] it is an approved payout (GW9), else a request (GW9c). */
    val fromWalletId: String? = null,
    /** The note typed after the amount in the chat ("#1000 November"). */
    val note: String? = null,
    /**
     * Driven from a chat (Pasaka, 2026-11-04): the chat's money card is the review, so once the PIN is done the card in
     * the thread carries Sending → Sent and this resets for the next payment, rather than waiting here (GW17).
     */
    val inChat: Boolean = false,
) {
    val contribution get() = groupId != null || (goalName != null && requestId == null)
    val payout get() = requestId != null
}

/** GW19 (and G2): the payment didn't go. Nothing was taken from [wallet]; [reference] is the request id for support. */
data class PayFailure(val wallet: String, val message: String, val reference: String?)

/**
 * The one Circaso fee (spaces-plan §7b: network cost included, the sender pays it on top, quoted by W17 for this exact
 * payment and held 15 minutes, then charged by W4 with [Quoted.quoteId]). Phase 2F, 2026-11-04.
 */
sealed interface PayFee {
    /** No fee line yet (no amount), or a server that takes no fees. */
    data object None : PayFee
    /** GW13: being worked out; Pay waits. */
    data object Quoting : PayFee
    /**
     * GW9b/GW9d/GW13b: [amountMinor] in the payment's coin at [rateBps] (0..200); [minimum] when the floor that covers
     * the network cost set it. [quoteId] holds it for W4 until [expiresAt] (only for the wallet's signer).
     */
    data class Quoted(
        val amountMinor: String,
        val rateBps: Int,
        val minimum: Boolean = false,
        val quoteId: String? = null,
        val expiresAt: String? = null,
    ) : PayFee
    /** GW13d: couldn't be worked out (5xx after retries, or the network). Nothing can be signed; Try Again. */
    data class Failed(val reference: String?, val message: String? = null) : PayFee
    /** Offline: a fee can't be worked out, and money doesn't move offline anyway (G3/GW15). */
    data object Offline : PayFee
}

/** GW13c: W4 found a higher fee than the one shown (the quote ran out); the person sees it before signing. */
data class FeeChange(val fromMinor: String, val toMinor: String)

/**
 * Why W17 (or W4's refusal) says this payment can't go as it is (spaces-plan §7a, GW14/GW14b/GW20). Amounts in the
 * payment's coin minor units; [fitsAt] (ISO) when this payment fits the rolling 24 hours, null when it never can.
 */
sealed interface PayBlock {
    /** GW20: the amount fits but not with its fee; [maxAmountMinor] is the most that does. */
    data class Funds(val maxAmountMinor: String?) : PayBlock
    /** One payment's cap. */
    data class PerPayment(val maxAmountMinor: String?) : PayBlock
    /** GW14/GW14b: the rolling 24-hour cap, fees included. */
    data class Daily(val leftMinor: String?, val dailyMaxMinor: String?, val fitsAt: String?, val moreAt: String?, val maxAmountMinor: String?) : PayBlock
}

/** Why the chosen person can't be paid right now, and what the screen offers. */
sealed interface PayeeProblem {
    data object NoWallet : PayeeProblem
    data class NotOnChain(val theirChains: List<String>) : PayeeProblem
    data class Other(val message: String) : PayeeProblem
}

/** "@btc" means Bitcoin, which on these networks is a wrapped token; likewise ETH. */
private val AssetAliases = mapOf(
    "btc" to setOf("btc", "wbtc", "cbbtc"),
    "eth" to setOf("eth", "weth"),
)

data class PayUiState(
    val step: PayStep = PayStep.Amount,
    val payee: Person? = null,
    val chatId: String? = null,
    val contacts: List<Person> = emptyList(),
    val query: String = "",
    val wallet: Wallet? = null,
    val online: Boolean = true,
    /** The chain the payment leaves from: the wallet's default, or one the payee can receive on. */
    val blockchain: String? = null,
    /** True when the person types in fiat; the USDC amount is derived with the wallet's rate. */
    val inFiat: Boolean = true,
    val amountText: String = "",
    val note: String = "",
    val checking: Boolean = false,
    val problem: PayeeProblem? = null,
    val reminded: RemindOutcome? = null,
    val error: String? = null,
    val result: Transfer? = null,
    val goalName: String? = null,
    val requestId: String? = null,
    /** The group's goal, for "after your contribution" (design D3). */
    val chama: Chama? = null,
    /** The payment went but linking it to the request (CH6) hasn't yet: the treasurer must not pay twice. */
    val attachPending: Boolean = false,
    val attaching: Boolean = false,
    /** The chat's "@eth" / "@btc" (design C3 extension): which asset to pay in; null is USDC. */
    val assetTag: String? = null,
    // ---- Group wallets (phase 2)
    /** Group wallets are on (the flag and a server that has them). */
    val groupWallets: Boolean = false,
    /** Paying into this group's wallet: a contribution (GW9d). */
    val groupId: String? = null,
    /** The wallet the money leaves (GW9b's "Paying from"); Personal unless switched or paying a payout. */
    val payingFrom: NamedWallet = NamedWallet.Personal,
    /** Every wallet the person can send from (W16 `canSign`, active), personal first. One means no chip. */
    val choices: List<NamedWallet> = emptyList(),
    /** Each choice's last balance, for GW10's lines. */
    val balances: Map<String?, Wallet?> = emptyMap(),
    /** GW10 is open. */
    val choosing: Boolean = false,
    /** The group's money: the one paid into (target after this) or the one paid from (approvals). */
    val groupMoney: com.circaso.feature.chama.domain.GroupMoney? = null,
    /** The group's members' names, for "Approved by Otieno and Wanjiru". */
    val memberNames: Map<String, String> = emptyMap(),
    /** GW19: didn't go; nothing was taken. */
    val failure: PayFailure? = null,
    /** GW9c done: the request the admins now decide on. */
    val requested: com.circaso.feature.chama.domain.ChamaRequest? = null,
    /** When the wallets' balances were read, for "as of" offline. */
    val asOf: String? = null,
    /** §7b's fee line, from W17 (phase 2F). */
    val fee: PayFee = PayFee.None,
    /** GW13c: the fee rose since it was shown; said once, until the next quote. */
    val feeChange: FeeChange? = null,
    /** GW14/GW14b/GW20: what stops this payment as it is, from W17 or W4's refusal. */
    val block: PayBlock? = null,
) {
    /** Paying from a group wallet (only ever its signer). */
    val fromGroupWallet get() = !payingFrom.personal
    /** GW9: an approved payout, paid from the group wallet with its PIN. */
    val groupPayout get() = groupWallets && requestId != null && fromGroupWallet
    /** GW9c: from a group wallet without an approved request: the review becomes Request Payout (CH4). */
    val requestPayout get() = groupWallets && requestId == null && fromGroupWallet
    /** Pay into a group wallet. */
    val toGroup get() = groupId != null
    /** Show GW9b's chip: the person has other wallets, or this one is a group's. */
    val showPayingFrom get() = groupWallets && (choices.size > 1 || fromGroupWallet)
    /** The chip opens GW10: there is something to switch to, and nothing fixes the wallet (a payout, a contribution). */
    val canSwitch get() = showPayingFrom && choices.size > 1 && requestId == null && !toGroup && step == PayStep.Amount
    /** The approved request being paid. */
    val payoutRequest get() = requestId?.let { id -> groupMoney?.openRequests?.firstOrNull { it.requestId == id } }
    /** The wallet's name as the approval says it ("Approve from Kilimani Chama"). */
    val walletName get() = payingFrom.name
    /** Who sends from the group wallet, for a member who asks (GW9c). */
    val signerName get() = memberNames[payingFrom.signerUserId ?: groupMoney?.wallet?.signerUserId]?.substringBefore(' ') ?: "its signer"
    /** The held asset the tag names ("@btc" also matches wrapped BTC), on the chain that holds the most of it; null for USDC. */
    val tagged: Pair<String, com.circaso.feature.wallet.domain.Asset>?
        get() {
            val tag = assetTag?.lowercase() ?: return null
            if (tag == "usdc") return null
            val names = AssetAliases[tag] ?: setOf(tag)
            return wallet?.chains.orEmpty()
                .flatMap { chain -> chain.assets.map { chain.blockchain to it } }
                .filter { (_, a) -> a.symbol.lowercase() in names && !MinorUnits.isZero(a.amountMinor) }
                .maxWithOrNull { x, y -> if (MinorUnits.lessThan(x.second.amountMinor, y.second.amountMinor)) -1 else 1 }
        }

    /** "@eth" was typed but this wallet holds none. */
    val assetMissing get() = assetTag != null && assetTag.lowercase() != "usdc" && tagged == null

    /** What the payment is in: USDC unless a held asset was tagged. */
    val asset: String get() = tagged?.second?.symbol ?: Wallet.Usdc
    private val decimals: Int get() = tagged?.second?.decimals ?: 6
    private val inUsdc get() = tagged == null
    val contribution get() = toGroup || (goalName != null && requestId == null)
    val payout get() = requestId != null
    /** The request is marked paid only once the link has landed (treasurer model; W4 links it with group wallets). */
    val linked get() = payout && result != null && !attaching && !attachPending
    val currency get() = wallet?.fiat?.currency
    val rate get() = wallet?.fiat?.rate
    val canUseFiat get() = rate != null && currency != null

    /** The USDC amount in minor units for what was typed, or null when it isn't an amount yet. */
    val amountMinor: String?
        // Another asset is typed in its own units: the fiat rate is for USD stablecoins only.
        get() = if (inUsdc && inFiat && rate != null) fiatToUsdcMinor(amountText, rate!!) else MinorUnits.fromDisplay(amountText, decimals)

    /** USDC on the chosen chain. */
    val balanceMinor: String get() = tagged?.second?.amountMinor ?: wallet?.chains?.firstOrNull { it.blockchain == blockchain }?.usdc?.amountMinor ?: "0"

    /** The chain the money leaves from: the tagged asset's, else the chosen one. */
    val payChain: String? get() = tagged?.first ?: blockchain

    val usdcText: String? get() = amountMinor?.let { MinorUnits.toDisplay(it, decimals) + " " + asset }

    /**
     * The fee row's words (GW9b, GW13, GW13b, GW13d): "No fee", "Working it out", "KES 2.50 · 0.5%", "KES 5 · minimum",
     * and for a payout request, whose fee is set when it is paid, "about KES 50 · 1%, set when paid" (GW9c).
     */
    val feeText: String
        get() = when (val f = fee) {
            PayFee.None -> "No fee"
            PayFee.Quoting -> "Working it out"
            PayFee.Offline -> "Needs a connection"
            is PayFee.Failed -> "Couldn’t work it out"
            is PayFee.Quoted -> if (MinorUnits.isZero(f.amountMinor)) "No fee" else {
                val money = moneyText(f.amountMinor)
                val why = if (f.minimum) "minimum" else rateText(f.rateBps)
                if (requestPayout) "about $money · $why, set when paid" else "$money · $why"
            }
        }

    /** A coin amount in the person's currency when there is a rate ("KES 2.50", "KES 5"), else in the coin. */
    fun moneyText(minor: String): String =
        if (inUsdc && rate != null && currency != null) usdcToFiat(minor, rate!!)?.let { "$currency ${fiatDisplay(MinorUnits.fromDisplay(it, 2) ?: "0")}" } ?: (MinorUnits.toDisplay(minor, decimals) + " " + asset)
        else MinorUnits.toDisplay(minor, decimals) + " " + asset

    /** The fee's minor units, when one is quoted. */
    val feeMinor: String? get() = (fee as? PayFee.Quoted)?.amountMinor

    /** The fee is known (or there is none), so the total can be shown and signed. */
    val feeSettled: Boolean get() = fee is PayFee.None || fee is PayFee.Quoted

    /** A payout request may be over today's cap (it waits once approved, GW14); everything else waits on [block]. */
    val blocked: Boolean get() = block != null && !requestPayout

    /** What leaves the wallet: the amount plus the fee (the receiver gets exactly the amount, §7b). */
    val totalMinor: String? get() = amountMinor?.let { a -> (fee as? PayFee.Quoted)?.let { MinorUnits.add(a, it.amountMinor) } ?: a }

    /** The button's amount: the total, in the person's currency when typed in it. */
    val totalText: String?
        get() {
            val feeMinor = (fee as? PayFee.Quoted)?.amountMinor
            typedFiatCents?.let { cents ->
                val feeCents = feeMinor?.let { f -> usdcToFiat(f, rate!!)?.let { MinorUnits.fromDisplay(it, 2) } } ?: "0"
                return "$currency ${fiatDisplay(MinorUnits.add(cents, feeCents))}"
            }
            return totalMinor?.let { t -> if (inUsdc && currency != null && rate != null && inFiat) usdcToFiat(t, rate!!)?.let { "$currency $it" } else MinorUnits.toDisplay(t, decimals) + " " + asset }
        }
    /** Typed in the person's currency: what they typed ("#500" is KES 500, never the 499.99 a round trip through USDC gives). */
    private val typedFiatCents: String? get() = if (inUsdc && inFiat && rate != null && currency != null) MinorUnits.fromDisplay(amountText, 2) else null
    val fiatText: String?
        get() = typedFiatCents?.let { "$currency ${fiatDisplay(it)}" }
            ?: if (inUsdc && currency != null && rate != null) amountMinor?.let { usdcToFiat(it, rate!!) }?.let { "$currency $it" } else null
    val overBalance: Boolean get() = totalMinor?.let { MinorUnits.lessThan(balanceMinor, it) } ?: false
    val onHold: Boolean get() = wallet?.holdUntil != null
    val canPay: Boolean get() = online && !onHold && payee != null && payChain != null && problem == null && !checking && !assetMissing && amountMinor?.let { !MinorUnits.isZero(it) } == true && !overBalance && step == PayStep.Amount && (feeSettled || requestPayout) && !blocked &&
        // A request needs the group's own chat to post in; a payout needs the request it pays.
        (!requestPayout || payingFrom.chatId != null)
    val filteredContacts get() = contacts.filter { query.isBlank() || it.displayName.contains(query, true) || it.handle?.contains(query.trimStart('@'), true) == true }

    /** 0..1 of the goal once this contribution lands. */
    val progressAfter: Float?
        get() {
            groupMoney?.takeIf { toGroup }?.let { m ->
                val target = m.goal?.targetMinor?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
                val got = (m.contributedMinor.toDoubleOrNull() ?: 0.0) + (amountMinor?.toDoubleOrNull() ?: 0.0)
                return (got / target).coerceIn(0.0, 1.0).toFloat()
            }
            val c = chama ?: return null
            val target = c.goal.targetMinor.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val got = (c.contributedMinor.toDoubleOrNull() ?: 0.0) + (amountMinor?.toDoubleOrNull() ?: 0.0)
            return (got / target).coerceIn(0.0, 1.0).toFloat()
        }
}

/**
 * Paying a person (C4 Send sheet, C5 PIN in the Circle SDK, C6 the moment). The person is checked with W9 as soon as
 * they are chosen; the payment goes through W4, is signed in the SDK, and its card is sent into the chat with them,
 * encrypted, so both sides see it (the receiver loads W5 from the card).
 */
class PayViewModel(
    private val intent: PayIntent,
    observeWallet: ObserveWalletUseCase,
    private val checkRecipient: CheckRecipientUseCase,
    private val remind: RemindRecipientUseCase,
    private val pay: PayUseCase,
    observeContacts: ObserveContactsUseCase,
    connection: ConnectionState,
    private val sendCard: SendPaymentCardUseCase,
    private val openDirectChat: OpenDirectChatUseCase,
    private val attachPayment: AttachChamaPaymentUseCase,
    observeChama: ObserveChamaUseCase,
    private val scanner: com.circaso.core.qr.QrScanner = com.circaso.core.qr.NoQrScanner,
    private val findHandle: (suspend (String) -> AppResult<com.circaso.feature.profile.domain.PublicProfile>)? = null,
    // Group wallets (phase 2); off and absent in the treasurer model, tests and previews.
    private val groupWallets: com.circaso.core.flags.GroupWalletsFlag = com.circaso.core.flags.GroupWalletsFlag.off(),
    private val wallets: GroupWalletSources? = null,
    /** The phone's copy of one transfer: GW17 waits on it, so a group payment says Paid only once the chain has it. */
    private val observeTransfer: com.circaso.feature.wallet.domain.ObserveTransferUseCase? = null,
    /** W17, the fee and limits before signing (phase 2F); absent in older tests and previews, which show no fee. */
    private val quotePayment: com.circaso.feature.wallet.domain.QuotePaymentUseCase? = null,
    /** The pause after the last change before quoting again (GW13: "after a short pause"). */
    private val quoteDelayMillis: Long = QuoteDelayMillis,
) : ViewModel() {
    private val userId = intent.userId
    private val chatId = intent.chatId
    private val address = intent.address
    private val local = MutableStateFlow(
        PayUiState(
            step = if (userId == null && address == null && intent.groupId == null) PayStep.Pick else PayStep.Amount, chatId = chatId,
            amountText = intent.amount?.filter { it.isDigit() || it == '.' }.orEmpty(), goalName = intent.goalName, requestId = intent.requestId,
            // A request's amount is exact USDC (CH3); a `#500` shortcut is in the person's currency.
            inFiat = !intent.payout,
            groupId = intent.groupId, note = intent.note.orEmpty(),
        ),
    )
    /** The wallet chosen to pay from: the intent's group wallet, or one picked in GW10; null is Personal. */
    private val fromId = MutableStateFlow(intent.fromWalletId)
    private var idempotencyKey = newIdempotencyKey()
    private var pendingAttach: Pair<String, String>? = null

    /** The group whose money matters here: the one paid into, or the one whose wallet pays. */
    private val groupChatId: kotlinx.coroutines.flow.Flow<String?> =
        if (intent.groupId != null) flowOf(intent.groupId)
        else combine(fromId, wallets?.named ?: flowOf(emptyList())) { id, list -> id?.let { w -> list.firstOrNull { it.walletId == w }?.chatId ?: chatId } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val groupSide: kotlinx.coroutines.flow.Flow<GroupSide> = if (wallets == null) flowOf(GroupSide()) else
        combine(groupWallets.enabled, fromId, wallets.named, groupChatId) { on, from, named, group -> Triple(on, from, named) to group }
            .flatMapLatest { (t, group) ->
                val (on, from, named) = t
                if (!on) return@flatMapLatest flowOf(GroupSide())
                val choices = named.filter { it.canSign && it.status == com.circaso.feature.wallet.domain.WalletStatus.Active }
                    .ifEmpty { listOf(NamedWallet.Personal) }
                    .let { list -> if (list.none { it.personal }) listOf(NamedWallet.Personal) + list else list }
                val paying = from?.let { id -> named.firstOrNull { it.walletId == id } ?: NamedWallet(id, chatId, "the group", null, com.circaso.feature.wallet.domain.WalletOwnerType.Group, true, com.circaso.feature.wallet.domain.WalletStatus.Active, null) } ?: NamedWallet.Personal
                combine(
                    wallets.walletOf(from), wallets.balances(choices), group?.let { wallets.money(it) } ?: flowOf(null), group?.let { wallets.chat(it) } ?: flowOf(null),
                ) { source, balances, money, chat ->
                    val chatName = chat?.title
                    GroupSide(
                        on = true, choices = choices, balances = balances, money = money,
                        payingFrom = if (!paying.personal && chatName != null && paying.name == "the group") paying.copy(name = chatName, avatarUrl = chat.avatarUrl) else paying,
                        source = source, sourceIsGroup = from != null,
                        names = chat?.members.orEmpty().associate { it.userId to it.displayName },
                        groupTitle = chatName, groupAvatar = chat?.avatarUrl,
                    )
                }
            }

    val state: StateFlow<PayUiState> = combine(
        local, observeWallet(), observeContacts(), connection.status,
        combine(if (intent.goalName != null && chatId != null) observeChama(chatId) else flowOf(null), groupSide) { ch, g -> ch to g },
    ) { ui, personal, contacts, c, sides ->
        val (chama, group) = sides
        val people = contacts.map { it.person }
        // The money leaves the chosen wallet: its chains and balance, and its fiat rate (the server prices each one).
        val wallet = if (group.sourceIsGroup) group.source else personal
        ui.copy(
            wallet = wallet,
            contacts = people,
            online = c == ConnectionStatus.Online,
            chama = chama,
            payee = ui.payee
                ?: userId?.let { id -> people.firstOrNull { it.userId == id } ?: Person(id, intent.displayName ?: "Them", null, null, null) }
                ?: address?.let { Person("", intent.displayName ?: shortAddress(it), null, null, null) }
                // Paying into a group: the group itself is who receives (its name and photo come from the chat).
                ?: intent.groupId?.let { Person("", group.groupTitle ?: intent.displayName ?: "The group", null, group.groupAvatar, null) },
            blockchain = ui.blockchain?.takeIf { b -> wallet?.chains?.any { it.blockchain == b } != false } ?: wallet?.chainsReceiving?.firstOrNull()?.blockchain,
            inFiat = ui.inFiat && wallet?.fiat != null,
            groupWallets = group.on,
            payingFrom = group.payingFrom,
            choices = group.choices,
            balances = group.balances,
            groupMoney = group.money,
            memberNames = group.names,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    // Declared before init: the quoting collector starts at once (Main.immediate) and would read them unset.
    /** Bumped by Try Again on GW13d, so the same payment is quoted once more. */
    private val quoteTry = MutableStateFlow(0)

    /** The server has no W17 (before phase 2F): no fee is charged there, so nothing more is asked. */
    private var quotesUnsupported = false

    /** GW10: open or close the switcher. */
    fun onChoosing(open: Boolean) { if (!open || state.value.canSwitch) local.update { it.copy(choosing = open) } }

    /** GW10: pay from another wallet. A group wallet turns the review into Request Payout (GW9c). */
    fun onPayFrom(wallet: NamedWallet) {
        fromId.value = wallet.walletId
        idempotencyKey = newIdempotencyKey()
        local.update { it.copy(choosing = false, blockchain = null, failure = null) }
        state.value.payee?.userId?.takeIf { it.isNotEmpty() }?.let { check(it, null) }
    }

    init {
        // An address can't be checked (W9 is for people); the chain validates it when the payment goes. A group's
        // wallet is checked by W4 itself (members only).
        if (userId != null) check(userId, null)
        wallets?.let { w -> viewModelScope.launch { if (groupWallets.isOn) w.refresh(intent.fromWalletId, intent.groupId ?: intent.chatId) } }
        quotePayment?.let { quote -> viewModelScope.launch { quoteAsTyped(quote) } }
    }

    /**
     * GW13: whenever what decides the fee changes (who, how much, which coin and network, Paying from), the fee row says
     * "Working it out" at once and Pay waits; after a short pause W17 is asked, and only the latest answer counts.
     * Offline there is no quote (money never moves offline, G3/GW15). Nothing is guessed: a failure is GW13d.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun quoteAsTyped(quote: com.circaso.feature.wallet.domain.QuotePaymentUseCase) {
        combine(state, quoteTry) { s, attempt -> keyOf(s)?.copy(attempt = attempt) to s.online }
            .distinctUntilChanged()
            .collectLatest { (key, online) ->
                if (quotesUnsupported) return@collectLatest
                if (key == null) { local.update { it.copy(fee = PayFee.None, block = null, feeChange = null) }; return@collectLatest }
                if (!online) { local.update { it.copy(fee = PayFee.Offline, block = null) }; return@collectLatest }
                local.update { it.copy(fee = PayFee.Quoting, block = null) }
                delay(quoteDelayMillis)
                when (val r = quote(key.request)) {
                    is AppResult.Success -> {
                        val q = r.value
                        if (q == null) { quotesUnsupported = true; local.update { it.copy(fee = PayFee.None, block = null) }; return@collectLatest }
                        local.update {
                            it.copy(
                                fee = PayFee.Quoted(q.feeMinor, q.rateBps, q.minimum, q.quoteId, q.expiresAt),
                                block = q.blockOf(),
                                // A fresh quote for what the person now sees replaces GW13c's notice.
                                feeChange = it.feeChange?.takeIf { c -> c.toMinor == q.feeMinor },
                            )
                        }
                    }
                    is AppResult.Failure -> local.update {
                        it.copy(fee = PayFee.Failed(ErrorMessages.reference(r.error), ErrorMessages.forError(r.error)), block = null)
                    }
                }
            }
    }

    /**
     * What W17 is asked for this card as it stands, or null when there is nothing to quote yet: no amount, no payee or
     * network, a payee that can't be paid, or a coin the wallet doesn't hold.
     */
    private fun keyOf(s: PayUiState): QuoteKey? {
        val payee = s.payee ?: return null
        val chain = s.payChain ?: return null
        val minor = s.amountMinor?.takeIf { !MinorUnits.isZero(it) } ?: return null
        // Not the step: signing keeps the quote it signs, and coming back to the card mustn't ask again.
        if (s.step == PayStep.Pick || s.problem != null || s.assetMissing) return null
        val toAddress = address?.takeIf { payee.userId.isEmpty() && !s.toGroup }
        val toUser = payee.userId.takeIf { it.isNotEmpty() && toAddress == null && !s.toGroup }
        if (toAddress == null && toUser == null && s.groupId == null) return null
        return QuoteKey(
            PaymentRequest(
                blockchain = chain, toUserId = toUser, toAddress = toAddress, amountMinor = minor, asset = s.asset, chatId = chatId,
                toGroupId = s.groupId, fromWalletId = s.payingFrom.walletId,
            ),
        )
    }

    /** GW13d's Try Again: the same payment, quoted again. */
    fun onRetryQuote() { quoteTry.update { it + 1 } }

    /** GW14 / GW20's offer: the most that fits, in the unit the person is typing in, never a cent over. */
    fun onUseMax() {
        val s = state.value
        val max = when (val b = s.block) {
            is PayBlock.Funds -> b.maxAmountMinor
            is PayBlock.PerPayment -> b.maxAmountMinor
            is PayBlock.Daily -> b.maxAmountMinor
            null -> null
        } ?: return
        if (MinorUnits.isZero(max)) return
        val rate = s.rate
        val text = if (s.inFiat && rate != null && s.tagged == null) {
            var fiat = usdcToFiat(max, rate) ?: return
            // usdcToFiat rounds half up; step down a cent while the amount it stands for is over the most.
            while (fiatToUsdcMinor(fiat, rate)?.let { MinorUnits.lessThan(max, it) } == true) {
                val cents = MinorUnits.fromDisplay(fiat, 2) ?: return
                if (MinorUnits.isZero(cents)) return
                fiat = MinorUnits.toDisplay((cents.toLong() - 1).toString(), 2, minFraction = 2).replace(",", "")
            }
            fiat.removeSuffix(".00")
        } else MinorUnits.toDisplay(max, if (s.tagged == null) 6 else s.tagged!!.second.decimals).replace(",", "")
        local.update { it.copy(amountText = text, error = null, failure = null) }
    }

    fun onQuery(q: String) = local.update { it.copy(query = q) }

    val canScan: Boolean get() = scanner.available && findHandle != null

    /** Design E4 the other way round: scan someone's Circaso code to pay them; a wallet code opens nothing here. */
    fun onScan() {
        val find = findHandle ?: return
        viewModelScope.launch {
            val raw = scanner.scan() ?: return@launch
            when (val code = com.circaso.core.qr.QrPayload.read(raw)) {
                is com.circaso.core.qr.QrPayload.Scanned.Handle -> when (val r = find(code.handle)) {
                    is AppResult.Success -> onPick(Person(r.value.userId, r.value.displayName, r.value.handle, r.value.avatarUrl, r.value.about))
                    is AppResult.Failure -> local.update { it.copy(error = ErrorMessages.forError(r.error)) }
                }
                is com.circaso.core.qr.QrPayload.Scanned.Address -> local.update { it.copy(error = "That's a wallet address. Use Send to a Wallet for it.") }
                com.circaso.core.qr.QrPayload.Scanned.Unknown -> local.update { it.copy(error = "That code isn't a Circaso code.") }
            }
        }
    }

    fun onPick(person: Person) {
        local.update { it.copy(payee = person, step = PayStep.Amount, problem = null, reminded = null, error = null) }
        check(person.userId, state.value.blockchain)
    }

    /** Back from the amount: to the person picker when that's where the payment started; false means leave Pay. */
    fun onBackToPick(): Boolean {
        if (userId != null || address != null || intent.groupId != null || state.value.step != PayStep.Amount) return false
        local.update { it.copy(step = PayStep.Pick) }
        return true
    }

    private fun check(userId: String, blockchain: String?) {
        local.update { it.copy(checking = true) }
        viewModelScope.launch {
            val chain = blockchain ?: state.value.blockchain
            when (val r = checkRecipient(userId, chain, chatId)) {
                is AppResult.Success -> local.update {
                    it.copy(
                        checking = false,
                        problem = when (r.value.status) {
                            RecipientStatus.CanReceive -> null
                            RecipientStatus.NoWallet -> PayeeProblem.NoWallet
                            RecipientStatus.NoWalletOnChain -> PayeeProblem.NotOnChain(r.value.blockchains)
                        },
                    )
                }
                is AppResult.Failure -> local.update { it.copy(checking = false, problem = PayeeProblem.Other(ErrorMessages.forError(r.error))) }
            }
        }
    }

    /** They can be paid on one of their chains: pay from there if this wallet is on it too. */
    fun onChain(blockchain: String) {
        local.update { it.copy(blockchain = blockchain, problem = null) }
        state.value.payee?.let { check(it.userId, blockchain) }
    }

    fun onRemind() {
        val payee = state.value.payee ?: return
        viewModelScope.launch {
            when (val r = remind(payee.userId)) {
                is AppResult.Success -> local.update { it.copy(reminded = r.value, problem = if (r.value == RemindOutcome.HasWallet) null else it.problem) }
                is AppResult.Failure -> local.update { it.copy(error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onAmount(text: String) {
        // A request's amount is what the admins approved; changing it would fail the link after the money left.
        if (state.value.payout) return
        // Digits and one point; the decimals the unit allows (2 for fiat, 6 for USDC).
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1) return
        val max = if (state.value.inFiat) 2 else 6
        if (clean.substringAfter('.', "").length > max) return
        local.update { it.copy(amountText = clean, error = null) }
    }

    fun onToggleUnit() {
        val s = state.value
        if (!s.canUseFiat || s.payout) return
        // Carry the value across so the number doesn't jump.
        val minor = s.amountMinor
        val next = !s.inFiat
        val text = when {
            minor == null || MinorUnits.isZero(minor) -> ""
            next -> usdcToFiat(minor, s.rate!!) ?: ""
            else -> MinorUnits.toDisplay(minor, 6).replace(",", "")
        }
        local.update { it.copy(inFiat = next, amountText = text) }
    }

    fun onNote(text: String) = local.update { it.copy(note = text.take(140)) }

    /** The chat's "@eth": pay in that asset if the wallet holds it (the recipient is checked on its chain). */
    fun onAssetTag(tag: String?) {
        if (local.value.assetTag == tag) return
        local.update { it.copy(assetTag = tag) }
        state.value.payee?.userId?.takeIf { it.isNotEmpty() }?.let { check(it, state.value.payChain) }
    }

    /**
     * W4, the SDK, then the card into the chat. The same key on a retry returns the same transfer and challenge.
     * From a group wallet without an approved request this is CH4 instead (Request Payout, GW9c): no money moves.
     */
    fun onPay() {
        val s = state.value
        if (!s.canPay) return
        val payee = s.payee ?: return
        val chain = s.payChain ?: return
        val minor = s.amountMinor ?: return
        local.update { it.copy(step = PayStep.Signing, error = null, failure = null) }
        if (s.requestPayout) { requestPayout(s, payee, minor); return }
        viewModelScope.launch {
            val typed = s.note.trim().takeIf { it.isNotEmpty() }
            val group = s.groupMoney?.goal?.name ?: s.payee?.displayName.takeIf { s.toGroup }
            // The card says what the money was for: "Added to Holiday Fund", "Paid from Holiday Fund", or the note alone.
            val note = when {
                s.toGroup -> listOfNotNull("Added to ${s.groupMoney?.goal?.name ?: payee.displayName}", typed).joinToString(" · ")
                s.groupPayout -> listOfNotNull("Paid from ${s.walletName}", typed).joinToString(" · ")
                s.contribution -> listOfNotNull("Added to ${s.goalName}", typed).joinToString(" · ")
                s.payout -> listOfNotNull("Paid from ${s.goalName ?: "the group"}", typed).joinToString(" · ")
                else -> typed
            }
            val toAddress = address?.takeIf { payee.userId.isEmpty() && !s.toGroup }
            // Every approval names its wallet; a person with only a personal wallet keeps today's wording (GW11b).
            val subject = if (s.fromGroupWallet) s.payingFrom.subject else if (s.choices.size > 1) NamedWallet.Personal.subject else com.circaso.feature.wallet.domain.SignSubject.Personal
            val request = PaymentRequest(
                blockchain = chain, toUserId = payee.userId.takeIf { it.isNotEmpty() && toAddress == null && !s.toGroup }, toAddress = toAddress, amountMinor = minor,
                asset = s.asset, chatId = chatId, note = note, counterpartName = payee.displayName ?: group,
                toGroupId = s.groupId, fromWalletId = s.payingFrom.walletId, payoutRequestId = s.requestId.takeIf { s.groupPayout }, subject = subject,
                quoteId = (s.fee as? PayFee.Quoted)?.quoteId,
            )
            when (val r = pay(request, idempotencyKey)) {
                is AppResult.Success -> {
                    idempotencyKey = newIdempotencyKey()
                    val transfer = r.value
                    val target = chatId ?: if (toAddress == null && !s.toGroup) (openDirectChat(payee.userId) as? AppResult.Success)?.value?.chatId else null
                    if (target != null) sendCard(target, PaymentCard(transfer.transferId, transfer.amount.amountMinor, transfer.amount.asset, transfer.blockchain, note))
                    val follow = observeTransfer
                    if ((s.groupPayout || s.toGroup) && follow != null && !transfer.settled && !intent.inChat) {
                        // GW17: signed, not yet on chain. The review stays, named; nothing says paid until the server does.
                        local.update { it.copy(step = PayStep.Sending, result = transfer, chatId = it.chatId ?: target) }
                        followTransfer(follow, transfer.transferId, s)
                    } else local.update { it.copy(step = PayStep.Done, result = transfer, chatId = it.chatId ?: target) }
                    // Treasurer model only: with group wallets, W4's payoutRequestId ties the payment to the request.
                    if (!s.groupWallets) s.requestId?.let { attach(it, transfer.transferId) }
                }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    if (code != "wallet.cancelled") idempotencyKey = newIdempotencyKey().takeIf { code == "wallet.sdk_failed" || code == "wallet.payout_in_progress" || code in Refusals } ?: idempotencyKey
                    // Phase 2F: refused before anything was prepared, with the numbers to say why (GW13c, GW14b, GW20).
                    if (refusedWithNumbers(s, r.error as? AppError.Api)) return@launch
                    // G2 / GW19: say Not sent, and that nothing was taken from the named wallet; the approval stands.
                    val failure = if (code == "wallet.cancelled") null else PayFailure(s.walletName.takeIf { s.fromGroupWallet } ?: "your wallet", ErrorMessages.forError(r.error), ErrorMessages.reference(r.error))
                    local.update { it.copy(step = PayStep.Amount, failure = failure) }
                }
            }
        }
    }

    /** GW9c: from a group wallet, ask the admins (CH4) in that group's chat; the signer pays it once approved. */
    private fun requestPayout(s: PayUiState, payee: Person, minor: String) {
        val group = s.payingFrom.chatId ?: return
        val ask = wallets?.createRequest ?: return
        viewModelScope.launch {
            val r = ask(group, payee.userId.takeIf { it.isNotEmpty() }, address?.takeIf { payee.userId.isEmpty() }, payee.displayName, minor, s.note.trim().takeIf { it.isNotEmpty() }, idempotencyKey)
            when (r) {
                is AppResult.Success -> { idempotencyKey = newIdempotencyKey(); local.update { it.copy(step = PayStep.Done, requested = r.value) } }
                is AppResult.Failure -> local.update { it.copy(step = PayStep.Amount, failure = PayFailure(s.walletName, ErrorMessages.forError(r.error), ErrorMessages.reference(r.error))) }
            }
        }
    }

    fun onFailureShown() = local.update { it.copy(failure = null) }

    /** Confirmed: Done says it. Failed or expired: GW19 (nothing was taken). Leaving keeps it going; the chat card updates. */
    private fun followTransfer(follow: com.circaso.feature.wallet.domain.ObserveTransferUseCase, transferId: String, s: PayUiState) {
        viewModelScope.launch {
            follow(transferId).collect { t ->
                if (t == null) return@collect
                when (t.state) {
                    com.circaso.feature.wallet.domain.TransferState.Confirmed -> { local.update { it.copy(step = PayStep.Done, result = t) }; return@collect }
                    com.circaso.feature.wallet.domain.TransferState.Failed, com.circaso.feature.wallet.domain.TransferState.Expired -> {
                        idempotencyKey = newIdempotencyKey()
                        local.update { it.copy(step = PayStep.Amount, result = null, failure = PayFailure(s.walletName.takeIf { s.fromGroupWallet } ?: "your wallet", ErrorMessages.forError(AppError.Api("wallet.sdk_failed", 400, null, null, emptyMap())), null)) }
                    }
                    else -> local.update { it.copy(result = t) }
                }
            }
        }
    }

    /**
     * CH6: tell the chama which transfer paid the request. The repository writes the link down before sending it and
     * keeps trying after this screen is gone; here a few quick tries decide what the Done screen can honestly say.
     */
    private fun attach(requestId: String, transferId: String) {
        val chat = chatId ?: return
        pendingAttach = requestId to transferId
        local.update { it.copy(attaching = true, attachPending = false) }
        viewModelScope.launch {
            var failure: AppError? = null
            for (attempt in 0 until AttachAttempts) {
                val r = attachPayment(chat, requestId, transferId)
                val code = ((r as? AppResult.Failure)?.error as? AppError.Api)?.code
                when {
                    r is AppResult.Success || (attempt > 0 && code in AlreadyLinkedCodes) -> {
                        pendingAttach = null
                        local.update { it.copy(attaching = false, attachPending = false) }
                        return@launch
                    }
                    // The server says this transfer isn't the request's payment: retrying can't fix that, say so.
                    code == "chama.transfer_mismatch" -> { failure = r.let { (it as AppResult.Failure).error }; break }
                    else -> failure = (r as AppResult.Failure).error
                }
                if (attempt < AttachAttempts - 1) delay(AttachBackoffMillis * (attempt + 1))
            }
            local.update { it.copy(attaching = false, attachPending = true, error = failure?.let { f -> ErrorMessages.forError(f) }) }
        }
    }

    fun onAttachAgain() { pendingAttach?.let { (r, t) -> attach(r, t) } }

    fun onErrorShown() = local.update { it.copy(error = null) }

    /**
     * The chat's `#500` card (design C3) reuses this view model for each payment: after one is sent (or the chip is
     * cleared), it starts again empty, with a fresh idempotency key, and the payee check kept.
     */
    fun onQuickReset() {
        idempotencyKey = newIdempotencyKey()
        // Personal again (GW9b's default) unless the chat fixes the wallet (a payout from the group's).
        if (fromId.value != intent.fromWalletId) fromId.value = intent.fromWalletId
        local.update {
            it.copy(
                // A payout's amount is the approved one (CH3), so it comes back with it; a `#` card starts empty.
                step = PayStep.Amount, amountText = if (intent.payout) intent.amount?.filter { c -> c.isDigit() || c == '.' }.orEmpty() else "", note = "",
                result = null, error = null, assetTag = null, failure = null, choosing = false, requested = null,
                inFiat = !intent.payout, blockchain = null,
            )
        }
    }

    /**
     * A card in the chat said Not sent (the chain refused it; nothing was taken): Try Again puts exactly that payment
     * back on the chat's money card, in its coin amount, so a rounding or a rate move can't change what is sent. It is
     * signed again with a fresh key; nothing repeats on its own.
     */
    fun onRetryExact(amountMinor: String, asset: String, note: String?) {
        idempotencyKey = newIdempotencyKey()
        val decimals = state.value.wallet?.chains.orEmpty().flatMap { it.assets }.firstOrNull { it.symbol.equals(asset, true) }?.decimals ?: 6
        local.update {
            it.copy(
                step = PayStep.Amount, inFiat = false, amountText = MinorUnits.toDisplay(amountMinor, decimals).replace(",", ""),
                assetTag = asset.lowercase().takeIf { a -> a != "usdc" }, note = cardNoteTyped(note), failure = null, result = null, error = null,
            )
        }
        state.value.payee?.userId?.takeIf { it.isNotEmpty() }?.let { check(it, state.value.payChain) }
    }

    /**
     * W4 refused before preparing anything, with its numbers in `details.fields` (phase 2F): a higher fee than the one
     * shown (GW13c: the new fee and its quote are shown, and the next tap pays it), a limit (GW14b) or the balance with
     * the fee (GW20). The card stays, and says it; "Not sent" would be untrue, nothing was attempted. False when the
     * refusal isn't one of these, so the ordinary failure line shows.
     */
    private fun refusedWithNumbers(s: PayUiState, error: AppError.Api?): Boolean {
        val f = error?.fields ?: return false
        when (error.code) {
            "wallet.fee_changed" -> {
                val fee = f["feeMinor"] ?: return false
                local.update {
                    it.copy(
                        step = PayStep.Amount, failure = null,
                        fee = PayFee.Quoted(fee, f["rateBps"]?.toIntOrNull() ?: 0, f["minimum"] == "true", f["quoteId"], f["expiresAt"]),
                        feeChange = FeeChange(f["quotedFeeMinor"] ?: s.feeMinor ?: "0", fee),
                    )
                }
            }
            "wallet.limit_exceeded" -> {
                val block = when (f["limit"]) {
                    "DAILY" -> PayBlock.Daily(f["leftMinor"], f["limitMinor"], f["resetsAt"], f["moreAt"], f["maxAmountMinor"])
                    "PER_PAYMENT" -> PayBlock.PerPayment(f["maxAmountMinor"])
                    else -> return false
                }
                local.update { it.copy(step = PayStep.Amount, failure = null, block = block) }
            }
            "wallet.insufficient_funds" -> {
                val max = f["maxAmountMinor"] ?: return false
                local.update { it.copy(step = PayStep.Amount, failure = null, block = PayBlock.Funds(max)) }
            }
            else -> return false
        }
        return true
    }

    private companion object {
        const val AttachAttempts = 3
        const val AttachBackoffMillis = 1_000L

        /** W4 refusals that prepared nothing: the same key would be answered from the replay cache, so the next try is new. */
        val Refusals = setOf("wallet.fee_changed", "wallet.limit_exceeded", "wallet.insufficient_funds")
    }
}

/** GW13: the pause after the last keystroke before the fee is asked for. */
const val QuoteDelayMillis = 400L

/** What the group-wallet side of the review reads, combined. */
/** "0.5%", "1%", "2%" from basis points. */
internal fun rateText(bps: Int): String = (bps / 100.0).toString().removeSuffix(".0") + "%"

/** "500", "1,000", "502.50": whole amounts as the design writes them, cents only when there are some. */
internal fun fiatDisplay(cents: String): String {
    val digits = cents.trimStart('0')
    val whole = digits.isEmpty() || digits.endsWith("00")
    return MinorUnits.toDisplay(cents, 2, minFraction = if (whole) 0 else 2)
}

/** What the person typed, from a card's note ("Added to Holiday Fund · November" → "November"). */
/** One quote's question: the payment, and which try (GW13d's Try Again asks the same again). */
internal data class QuoteKey(val request: PaymentRequest, val attempt: Int = 0)

/** What W17 says stops the payment as it is, in the card's words. */
internal fun com.circaso.feature.wallet.domain.PaymentQuote.blockOf(): PayBlock? = when (problem) {
    com.circaso.feature.wallet.domain.QuoteProblem.InsufficientFunds -> PayBlock.Funds(maxAmountMinor)
    com.circaso.feature.wallet.domain.QuoteProblem.PerPaymentLimit -> PayBlock.PerPayment(maxAmountMinor)
    com.circaso.feature.wallet.domain.QuoteProblem.DailyLimit -> PayBlock.Daily(leftMinor, dailyMaxMinor, fitsAt, moreAt, maxAmountMinor)
    null -> null
}

internal fun cardNoteTyped(note: String?): String {
    val n = note?.trim().orEmpty()
    if (!n.startsWith("Added to ") && !n.startsWith("Paid from ")) return n
    return n.substringAfter(" · ", "")
}

private data class GroupSide(
    val on: Boolean = false,
    val choices: List<NamedWallet> = emptyList(),
    val balances: Map<String?, Wallet?> = emptyMap(),
    val money: com.circaso.feature.chama.domain.GroupMoney? = null,
    val payingFrom: NamedWallet = NamedWallet.Personal,
    val source: Wallet? = null,
    val sourceIsGroup: Boolean = false,
    val names: Map<String, String> = emptyMap(),
    val groupTitle: String? = null,
    val groupAvatar: String? = null,
)

/**
 * Where the review reads group wallets from (W16 named from the chats, each wallet's W1, the group's money, its chat)
 * and the one write it makes itself (CH4 for Request Payout). Reads come from Room; [refresh] fetches online.
 */
class GroupWalletSources(
    private val observeWallets: com.circaso.feature.wallet.domain.ObserveWalletsUseCase,
    private val refreshWallets: com.circaso.feature.wallet.domain.RefreshWalletsUseCase,
    private val observeWalletOf: com.circaso.feature.wallet.domain.ObserveWalletOfUseCase,
    private val refreshWalletOf: com.circaso.feature.wallet.domain.RefreshWalletOfUseCase,
    private val observeChats: com.circaso.feature.messaging.domain.ObserveChatsUseCase,
    private val observeChat: com.circaso.feature.messaging.domain.ObserveChatUseCase,
    private val observeMoney: com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase,
    private val refreshMoney: com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase,
    val createRequest: com.circaso.feature.chama.domain.CreateChamaRequestUseCase,
) {
    val named: kotlinx.coroutines.flow.Flow<List<NamedWallet>> = combine(observeWallets(), observeChats()) { list, chats -> nameWallets(list, chats) }
    fun walletOf(walletId: String?) = observeWalletOf(walletId)
    fun money(chatId: String) = observeMoney(chatId)
    fun chat(chatId: String) = observeChat(chatId)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun balances(choices: List<NamedWallet>): kotlinx.coroutines.flow.Flow<Map<String?, Wallet?>> =
        if (choices.isEmpty()) flowOf(emptyMap())
        else combine(choices.map { w -> observeWalletOf(w.walletId).map { w.walletId to it } }) { pairs -> pairs.toMap() }

    /** W16, the wallet paid from, and the group's money: once, when the review opens. */
    suspend fun refresh(fromWalletId: String?, groupChatId: String?) {
        val list = (refreshWallets() as? AppResult.Success)?.value.orEmpty()
        list.filter { !it.personal && it.canSign }.forEach { refreshWalletOf(it.walletId) }
        fromWalletId?.let { refreshWalletOf(it) }
        groupChatId?.let { refreshMoney(it) }
    }
}
