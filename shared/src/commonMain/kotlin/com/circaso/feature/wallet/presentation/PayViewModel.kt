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
import kotlinx.coroutines.launch

/** Where the payment is (designs C4 → C5 → C6). */
enum class PayStep { Pick, Amount, Signing, Done }

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
) {
    val contribution get() = groupId != null || (goalName != null && requestId == null)
    val payout get() = requestId != null
}

/** GW19 (and G2): the payment didn't go. Nothing was taken from [wallet]; [reference] is the request id for support. */
data class PayFailure(val wallet: String, val message: String, val reference: String?)

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
    val fiatText: String? get() = if (inUsdc && currency != null && rate != null) amountMinor?.let { usdcToFiat(it, rate!!) }?.let { "$currency $it" } else null
    val overBalance: Boolean get() = amountMinor?.let { MinorUnits.lessThan(balanceMinor, it) } ?: false
    val onHold: Boolean get() = wallet?.holdUntil != null
    val canPay: Boolean get() = online && !onHold && payee != null && payChain != null && problem == null && !checking && !assetMissing && amountMinor?.let { !MinorUnits.isZero(it) } == true && !overBalance && step == PayStep.Amount &&
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
            )
            when (val r = pay(request, idempotencyKey)) {
                is AppResult.Success -> {
                    idempotencyKey = newIdempotencyKey()
                    val transfer = r.value
                    val target = chatId ?: if (toAddress == null && !s.toGroup) (openDirectChat(payee.userId) as? AppResult.Success)?.value?.chatId else null
                    if (target != null) sendCard(target, PaymentCard(transfer.transferId, transfer.amount.amountMinor, transfer.amount.asset, transfer.blockchain, note))
                    local.update { it.copy(step = PayStep.Done, result = transfer, chatId = it.chatId ?: target) }
                    // Treasurer model only: with group wallets, W4's payoutRequestId ties the payment to the request.
                    if (!s.groupWallets) s.requestId?.let { attach(it, transfer.transferId) }
                }
                is AppResult.Failure -> {
                    val code = (r.error as? AppError.Api)?.code
                    if (code != "wallet.cancelled") idempotencyKey = newIdempotencyKey().takeIf { code == "wallet.sdk_failed" || code == "wallet.payout_in_progress" } ?: idempotencyKey
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
        local.update { it.copy(step = PayStep.Amount, amountText = "", note = "", result = null, error = null, assetTag = null) }
    }

    private companion object {
        const val AttachAttempts = 3
        const val AttachBackoffMillis = 1_000L
    }
}

/** What the group-wallet side of the review reads, combined. */
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
