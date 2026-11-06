package com.circaso.feature.wallet.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.presentation.GroupWalletReader
import com.circaso.feature.chama.presentation.GroupWalletUiState
import com.circaso.feature.chama.presentation.decideFailed
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.CreateWalletUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Hide balances, once for every wallet screen (a Koin single): someone who hid them on the Wallet tab doesn't see a
 * group's money in full after tapping in from group info. Per run, as before.
 */
class BalancePrivacy {
    val hidden = MutableStateFlow(false)
    fun toggle() { hidden.value = !hidden.value }
}

/** A sheet that says a designed flow has no backend yet (E2 Add money, E6 Cash out). */
enum class ComingFlow { AddMoney, CashOut }

data class WalletUiState(
    /** Null until the first read; `status` None means "Create wallet". */
    val wallet: Wallet? = null,
    val online: Boolean = true,
    val loading: Boolean = false,
    /** The Circle SDK is on screen, or the wallet is being made on chain. */
    val creating: Boolean = false,
    val hidden: Boolean = false,
    val handle: String? = null,
    val error: String? = null,
    /** Who asked this person to set up a wallet (`setup.requested`), until a wallet exists. */
    val setupRequestedBy: String? = null,
    val receiveSheet: Boolean = false,
    val coming: ComingFlow? = null,
    /** Supported networks this wallet isn't on yet (everyone should hold all of them; decision 2026-10-30). */
    val missingNetworks: List<String> = emptyList(),
    val addingNetworks: Boolean = false,
    /** The wallet was just made on this phone: offer Face ID / fingerprint approval once, while the PIN is fresh. */
    val offerBiometrics: Boolean = false,
    /** Group wallets (GW16): every wallet the person can see, named. */
    val wallets: List<NamedWallet> = emptyList(),
    /** The wallet the tab shows; null is the person's own (or, under Groups, no group wallet yet). */
    val selectedWalletId: String? = null,
    /** The Groups overflow menu is open (Android; on iOS UIKit owns its menu). */
    val switching: Boolean = false,
    /** Personal or Groups, from the segmented control at the top; remembered between launches. */
    val mode: WalletMode = WalletMode.Personal,
    /** Group wallets are on for this person (the flag). The control shows only once they have a group wallet. */
    val groupsEnabled: Boolean = false,
    /** The group wallet Groups returns to; kept while Personal is on screen. */
    val lastGroupWalletId: String? = null,
    /** The searchable list of every group wallet, for someone in more groups than the menu lists. */
    val allGroups: Boolean = false,
    /**
     * The saved list of wallets has been read from the phone (a few milliseconds): until then the tab doesn't draw, so
     * the Personal | Groups control never pops in under a page already up, and a remembered Groups opens on Groups.
     */
    val walletsLoaded: Boolean = true,
    /** The remembered mode and group are in: until then the tab doesn't draw, so it never opens on the wrong side. */
    val choiceRestored: Boolean = true,
    /**
     * The group wallet on screen, drawn with the personal wallet's own design (Pasaka, 2026-11-04); null for the
     * person's own. Set by the tab when a group is picked in the switcher, and by the pushed group wallet.
     */
    val group: GroupWalletUiState? = null,
    /** The last balance this phone read for each wallet in the switcher (null key: the personal wallet). */
    val balances: Map<String?, Wallet?> = emptyMap(),
    /** Group wallets whose signer has left the group: nothing can leave them (GW8b). */
    val lockedWallets: Set<String> = emptySet(),
) {
    val selected: NamedWallet get() = wallets.firstOrNull { it.walletId == selectedWalletId } ?: NamedWallet.Personal
    /** The chosen group's chat, when the tab shows a group wallet. */
    val selectedGroupChat: String? get() = selected.chatId.takeIf { !selected.personal }
    /** What the picked wallet's own parts are keyed on: its group's chat. */
    val selectedGroupKey: String? get() = selectedGroupChat
    /**
     * The Personal | Groups control shows: group wallets are on and the person has at least one (Pasaka, 2026-11-06).
     * With none there is nothing to switch to, so there is no control and no empty Groups page, just their wallet.
     */
    val showSwitcher get() = groupsEnabled && groupWallets.isNotEmpty()
    /** The group wallets, in the server's order: what Groups chooses from. */
    val groupWallets: List<NamedWallet> get() = wallets.filter { it.kind == com.circaso.feature.wallet.domain.WalletOwnerType.Group }
    /** Groups is chosen and a group wallet is on screen. */
    val showingGroups get() = showSwitcher && !selected.personal
    val status get() = wallet?.status ?: WalletStatus.None
    val active get() = status == WalletStatus.Active
    val canSend get() = active && online && wallet?.holdUntil == null
}

/**
 * The Wallet tab (design E1): balance, the four actions, coins, recent payments; create and set-up states before that.
 * With group wallets (GW16) it drives the corner switcher too: the picked wallet's money, in the same design.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WalletViewModel(
    observeWallet: ObserveWalletUseCase,
    private val refreshWallet: RefreshWalletUseCase,
    private val createWallet: CreateWalletUseCase,
    connection: ConnectionState,
    observeProfile: ObserveProfileUseCase,
    repository: WalletRepositoryImpl,
    private val getChains: com.circaso.feature.wallet.domain.GetChainsUseCase? = null,
    private val addMissingChains: com.circaso.feature.wallet.domain.AddMissingChainsUseCase? = null,
    private val groupWallets: com.circaso.core.flags.GroupWalletsFlag = com.circaso.core.flags.GroupWalletsFlag.off(),
    private val sources: GroupWalletSources? = null,
    /** Reads the group wallet picked in the switcher; null where group wallets aren't wired. */
    private val groupReader: GroupWalletReader? = null,
    private val privacy: BalancePrivacy = BalancePrivacy(),
    /** Remembers Personal or Groups, and which group, between launches. */
    private val choices: WalletChoiceStore = WalletChoiceStore.memory(),
) : ViewModel() {
    // Until the remembered side and the saved list are read, the tab knows group wallets may be on (so it waits).
    private val local = MutableStateFlow(WalletUiState(choiceRestored = false, walletsLoaded = sources == null, groupsEnabled = sources != null && groupWallets.isOn))
    /** The picked group wallet's own refresh, approval and message state; fresh whenever another wallet is picked. */
    private val groupLocal = MutableStateFlow(GroupWalletUiState(myUserId = groupReader?.myUserId))

    /** The person chose a mode or a group this run: the remembered choice, read a moment later, doesn't override it. */
    private var chosen = false

    init {
        // GW16: every wallet the person can see (W16, named from their chats). With the flag off the list is empty, so
        // there is no Personal | Groups control and the tab is the personal wallet.
        sources?.let { src ->
            viewModelScope.launch {
                combine(groupWallets.enabled, src.named) { on, named -> on to (if (on) named else emptyList()) }
                    .collect { (on, named) ->
                        // A group that went away (left, or the flag went off) gives way to the next group, or to the
                        // empty state; the remembered one is kept, so it comes back if the list does.
                        reselect { it.copy(wallets = named, groupsEnabled = on, walletsLoaded = true) }
                    }
            }
        }
        // The last mode and group, from the previous run.
        viewModelScope.launch {
            val saved = try { choices.walletChoice.first() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
            if (saved == null || chosen) { local.update { it.copy(choiceRestored = true) }; return@launch }
            reselect { it.copy(mode = saved.mode, lastGroupWalletId = saved.groupWalletId ?: it.lastGroupWalletId, choiceRestored = true) }
        }
    }

    /** Applies [change], works out the wallet on screen again, and starts reading a group that just came on screen. */
    private fun reselect(change: (WalletUiState) -> WalletUiState) {
        val before = local.value.selectedWalletId
        var dropped = false
        local.update { old ->
            val changed = change(old)
            changed.reselected().also { dropped = changed.mode == WalletMode.Groups && it.mode == WalletMode.Personal }
        }
        // Groups gave way to Personal because no group wallet is left: remembered too, so the next launch doesn't open
        // on Groups once a group wallet comes back (D-M10). Not the person's own choice, so [chosen] stays as it is.
        if (dropped) local.value.let { s -> writeChoice(SavedWalletChoice(WalletMode.Personal, s.lastGroupWalletId)) }
        val now = local.value.selected
        if (now.walletId == before) return
        groupLocal.value = GroupWalletUiState(myUserId = groupReader?.myUserId, nameHint = now.name)
        if (!now.personal) refreshGroup()
    }

    private fun saveChoice() {
        chosen = true
        val s = local.value
        writeChoice(SavedWalletChoice(s.mode, s.selectedWalletId ?: s.lastGroupWalletId))
    }

    private fun writeChoice(choice: SavedWalletChoice) {
        viewModelScope.launch {
            try { choices.saveWalletChoice(choice) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { /* a lost preference write only forgets the choice */ }
        }
    }

    /** The segmented control: Personal shows the person's own wallet; Groups the last group shown (or the first). */
    fun onMode(mode: WalletMode) {
        reselect { it.copy(mode = mode, switching = false, allGroups = false, receiveSheet = false, choiceRestored = true) }
        // The group Groups opened on is now the one it comes back to (only a choice the person made moves it, so a
        // list still loading at launch never overwrites the remembered group).
        local.value.selectedWalletId?.let { id -> local.update { it.copy(lastGroupWalletId = id) } }
        saveChoice()
    }

    /** The Groups overflow menu: it opens only while a group wallet is on screen. */
    fun onSwitching(open: Boolean) = local.update { it.copy(switching = open && it.showingGroups && it.selectedWalletId != null) }

    /** Every group wallet, searchable (from the menu's "All Group Wallets…"). */
    fun onAllGroups(open: Boolean) = local.update { it.copy(allGroups = open && it.groupWallets.isNotEmpty(), switching = false) }

    /**
     * Picks the wallet the tab shows: a group (from the menu or the full list) moves to Groups and is remembered;
     * Personal moves back. The layout stays; only its money, payments and group-only parts change.
     */
    fun onSelectWallet(wallet: NamedWallet) {
        reselect {
            val closed = it.copy(switching = false, allGroups = false, receiveSheet = false, choiceRestored = true)
            if (wallet.personal) closed.copy(mode = WalletMode.Personal) else closed.copy(mode = WalletMode.Groups, lastGroupWalletId = wallet.walletId)
        }
        saveChoice()
    }

    private val selection = local.map { it.selected }.distinctUntilChanged { a, b -> a.walletId == b.walletId && a.chatId == b.chatId }

    /** The picked group's wallet from the phone's copy; null for Personal. */
    private val groupSide = selection.flatMapLatest { sel ->
        val chat = sel.chatId
        val reader = groupReader
        val walletId = sel.walletId
        if (sel.personal || chat == null || reader == null || walletId == null) flowOf(null)
        else combine(groupLocal, reader.observe(chat, walletId)) { ui, d ->
            ui.copy(chat = d.chat, money = d.money, wallet = d.wallet, activity = d.activity, nameHint = sel.name)
        }
    }

    /** The menu's small balances and its locked marks, from what the phone already holds. */
    private val switcherSide = local.map { it.wallets }.distinctUntilChanged().flatMapLatest { ws ->
        val src = sources
        if (ws.isEmpty() || src == null) flowOf(emptyMap<String?, Wallet?>() to emptySet())
        else {
            val groups = ws.filter { !it.personal && it.chatId != null && it.signerUserId != null }
            val locked = if (groups.isEmpty()) flowOf(emptySet())
            else combine(groups.map { w -> src.chat(w.chatId!!).map { chat -> w.walletId.takeIf { signerGone(chat, w.signerUserId) } } }) { ids -> ids.filterNotNull().toSet() }
            combine(src.balances(ws), locked) { b, l -> b to l }
        }
    }

    val state: StateFlow<WalletUiState> = combine(
        local, observeWallet(), connection.status, observeProfile(), repository.setupRequestedBy, groupSide, switcherSide, privacy.hidden,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val ui = values[0] as WalletUiState
        val online = values[2] == ConnectionStatus.Online
        @Suppress("UNCHECKED_CAST")
        val switcher = values[6] as Pair<Map<String?, Wallet?>, Set<String>>
        ui.copy(
            wallet = values[1] as Wallet?,
            online = online,
            handle = (values[3] as com.circaso.feature.profile.domain.Profile?)?.handle,
            setupRequestedBy = values[4] as String?,
            group = (values[5] as GroupWalletUiState?)?.copy(online = online),
            balances = switcher.first,
            lockedWallets = switcher.second,
            hidden = values[7] as Boolean,
        )
    // The first frame, before the phone's copy is read: the wallet's skeleton (loading), never "Couldn't open your
    // wallet", and the same wait for the remembered side as [local].
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value.copy(loading = true))

    init { refresh() }

    /** [fromPull]: the person asked, so a failure is said out loud; the quiet background refreshes stay quiet. */
    fun refresh(fromPull: Boolean = false) {
        if (!local.value.selected.personal) refreshGroup()
        if (local.value.loading) return
        local.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = refreshWallet()
            if (groupWallets.isOn) sources?.refresh(null, null)
            loadMissingNetworks()
            local.update { it.copy(loading = false, error = (result as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }?.takeIf { _ -> fromPull || state.value.wallet == null }) }
        }
    }

    /** The picked group's CH7, W1 and W6. Its failure shows only while the phone has nothing of that wallet. */
    private fun refreshGroup() {
        val sel = local.value.selected
        val reader = groupReader ?: return
        val chat = sel.chatId ?: return
        groupLocal.update { it.copy(loading = true) }
        viewModelScope.launch {
            val f = reader.refresh(chat, sel.walletId) as? AppResult.Failure
            // The answer belongs to the wallet that asked: if another was picked meanwhile, it isn't shown.
            if (local.value.selectedWalletId != sel.walletId) return@launch
            groupLocal.update { it.copy(loading = false, error = f?.let { e -> ErrorMessages.forError(e.error) }, reference = f?.let { e -> ErrorMessages.reference(e.error) }) }
        }
    }

    /** CH5 on the picked group's payout request, online-only. */
    fun onDecide(request: ChamaRequest, approve: Boolean) {
        val reader = groupReader ?: return
        if (groupLocal.value.busy || !state.value.online) return
        groupLocal.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = reader.decide(request, approve)
            groupLocal.update { it.copy(busy = false, message = (r as? AppResult.Failure)?.let(::decideFailed)) }
        }
    }

    fun onGroupMessageShown() = groupLocal.update { it.copy(message = null) }

    /** W2 then the Circle SDK: the person sets a PIN and security questions, Circle makes the wallet on Base. */
    fun onCreate() {
        if (local.value.creating || !state.value.online) return
        local.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            when (val r = createWallet()) {
                is AppResult.Success -> local.update {
                    val ready = r.value == WalletStatus.Active
                    it.copy(creating = false, offerBiometrics = ready, error = if (ready) null else "Your wallet is on its way. It will be ready in a moment.")
                }
                is AppResult.Failure -> local.update { it.copy(creating = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    private suspend fun loadMissingNetworks() {
        val chains = (getChains?.invoke() as? AppResult.Success)?.value ?: return
        val missing = chains.chains.filter { it.status == com.circaso.feature.wallet.domain.ChainStatus.Available && it.receiveEnabled }.map { it.blockchain }
        local.update { it.copy(missingNetworks = missing) }
    }

    /** One tap (and the PIN) puts the wallet on every network it's missing. */
    fun onAddMissingNetworks() {
        val add = addMissingChains ?: return
        if (local.value.addingNetworks || !state.value.online) return
        local.update { it.copy(addingNetworks = true, error = null) }
        viewModelScope.launch {
            val r = add()
            local.update { it.copy(addingNetworks = false, error = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
            loadMissingNetworks()
        }
    }

    fun onToggleHidden() = privacy.toggle()
    fun onReceive(open: Boolean) = local.update { it.copy(receiveSheet = open) }
    fun onComing(flow: ComingFlow?) = local.update { it.copy(coming = flow) }
    fun onErrorShown() = local.update { it.copy(error = null) }
    fun onBiometricsOffered() = local.update { it.copy(offerBiometrics = false) }
}

/** The signer is no longer in the group (the same rule as group info's locked wallet, GW8b). */
internal fun signerGone(chat: com.circaso.feature.messaging.domain.Chat?, signerUserId: String?): Boolean =
    signerUserId != null && chat != null && chat.members.isNotEmpty() && chat.members.none { it.userId == signerUserId }
