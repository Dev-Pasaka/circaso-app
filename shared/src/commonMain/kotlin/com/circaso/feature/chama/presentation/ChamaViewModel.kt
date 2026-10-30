package com.circaso.feature.chama.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.session.AuthManager
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.chama.domain.Chama
import com.circaso.feature.chama.domain.ChamaRequest
import com.circaso.feature.chama.domain.CreateChamaRequestUseCase
import com.circaso.feature.chama.domain.DecideChamaRequestUseCase
import com.circaso.feature.chama.domain.LedgerEntry
import com.circaso.feature.chama.domain.LoadLedgerUseCase
import com.circaso.feature.chama.domain.ObserveChamaUseCase
import com.circaso.feature.chama.domain.RefreshChamaUseCase
import com.circaso.feature.chama.domain.SetGoalUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.fiatToUsdcMinor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LedgerTab(val label: String) { Contributions("Contributions"), Payments("Payments"), Members("Members") }

/** The "Make a Payment" sheet (D4 → D5): who, how much, what for. */
data class PayoutDraft(
    val toUserId: String? = null,
    /** Paying someone outside the group (Coast Bus): a name for the ledger and their USDC address. */
    val someoneElse: Boolean = false,
    val toName: String = "",
    val address: String = "",
    val amountText: String = "",
    val note: String = "",
    val inFiat: Boolean = true,
)

data class ChamaUiState(
    val chat: Chat? = null,
    val chama: Chama? = null,
    val ledger: List<LedgerEntry> = emptyList(),
    val tab: LedgerTab = LedgerTab.Contributions,
    val fiat: FiatValue? = null,
    val online: Boolean = true,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val draft: PayoutDraft? = null,
    val goalDraft: GoalDraft? = null,
    val error: String? = null,
    val notice: String? = null,
    val myUserId: String? = null,
    /** The server said this group has no goal. Until then, an empty copy only means it hasn't been read yet. */
    val noGoal: Boolean = false,
) {
    val memberName: (String) -> String get() = { id -> chat?.members?.firstOrNull { it.userId == id }?.displayName ?: "Someone" }
    val shownLedger get() = ledger.filter { if (tab == LedgerTab.Payments) it.direction == com.circaso.feature.chama.domain.LedgerDirection.Out else it.direction == com.circaso.feature.chama.domain.LedgerDirection.In }
    val draftAmountMinor: String? get() = draft?.let { d -> if (d.inFiat && fiat != null) fiatToUsdcMinor(d.amountText, fiat.rate) else MinorUnits.fromDisplay(d.amountText, 6) }
    val canAsk: Boolean get() = online && !busy && draft != null && draftAmountMinor?.let { !MinorUnits.isZero(it) } == true &&
        (if (draft.someoneElse) draft.address.isNotBlank() && draft.toName.isNotBlank() else draft.toUserId != null)
}

/** Setting or changing the goal (admins; D1's "shared wallet and goal" part, done from chat info or the group wallet). */
data class GoalDraft(val name: String = "", val targetText: String = "", val inFiat: Boolean = true, val by: GoalDeadline = GoalDeadline.None)

/** When the goal is due. Choices rather than a calendar: groups save towards months, not days, and it needs no picker. */
enum class GoalDeadline(val label: String, val months: Int?) { None("No date", null), OneMonth("1 month", 1), ThreeMonths("3 months", 3), SixMonths("6 months", 6), OneYear("1 year", 12) }

/** The group wallet (design D4) and the requests it pays (D5). */
class ChamaViewModel(
    private val chatId: String,
    observeChat: ObserveChatUseCase,
    observeChama: ObserveChamaUseCase,
    private val refreshChama: RefreshChamaUseCase,
    private val loadLedger: LoadLedgerUseCase,
    private val createRequest: CreateChamaRequestUseCase,
    private val decide: DecideChamaRequestUseCase,
    private val setGoal: SetGoalUseCase,
    observeWallet: ObserveWalletUseCase,
    connection: ConnectionState,
    auth: AuthManager,
    private val prefs: AppPreferences? = null,
) : ViewModel() {
    private val local = MutableStateFlow(ChamaUiState(myUserId = auth.userId))
    private var requestKey = newIdempotencyKey()

    val state: StateFlow<ChamaUiState> = combine(local, observeChat(chatId), observeChama(chatId), observeWallet(), connection.status) { ui, chat, chama, wallet, c ->
        ui.copy(chat = chat, chama = chama, fiat = wallet?.fiat, online = c == ConnectionStatus.Online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val r = refreshChama(chatId)
            when (r) {
                is AppResult.Success -> local.update { it.copy(noGoal = r.value == null) }
                // Offline with nothing on the phone is its own state on screen; a toast would only repeat it.
                is AppResult.Failure -> if (r.error != com.circaso.core.common.AppError.Network || state.value.chama != null) local.update { it.copy(error = ErrorMessages.forError(r.error)) }
            }
            val ledger = loadLedger(chatId)
            local.update { it.copy(loading = false, ledger = (ledger as? AppResult.Success)?.value ?: it.ledger) }
        }
    }

    fun onTab(tab: LedgerTab) = local.update { it.copy(tab = tab) }

    fun onDraft(open: Boolean) = local.update { it.copy(draft = if (open) PayoutDraft(inFiat = it.fiat != null) else null, error = null) }
    fun onDraftChange(change: (PayoutDraft) -> PayoutDraft) = local.update { s -> s.copy(draft = s.draft?.let(change)) }

    /** CH4. */
    fun onAsk() {
        val s = state.value
        val d = s.draft ?: return
        val minor = s.draftAmountMinor ?: return
        if (!s.canAsk) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = createRequest(chatId, d.toUserId, d.address.trim().takeIf { d.toUserId == null && it.isNotEmpty() }, d.toName.trim().takeIf { it.isNotEmpty() }, minor, d.note.trim().takeIf { it.isNotEmpty() }, requestKey)
            when (r) {
                is AppResult.Success -> { requestKey = newIdempotencyKey(); local.update { it.copy(busy = false, draft = null, notice = if (r.value.state == com.circaso.feature.chama.domain.RequestState.Approved) "Approved. The treasurer can pay it now." else "Sent to the admins for approval.") } }
                is AppResult.Failure -> local.update { it.copy(busy = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    /** CH5. */
    fun onDecide(request: ChamaRequest, approve: Boolean) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = decide(request.requestId, approve)
            local.update { it.copy(busy = false, error = (r as? AppResult.Failure)?.let { f -> ErrorMessages.forError(f.error) }) }
        }
    }

    fun onGoalDraft(open: Boolean) = local.update { s -> s.copy(goalDraft = if (open) GoalDraft(name = s.chama?.goal?.name ?: s.chat?.title.orEmpty(), inFiat = s.fiat != null) else null, error = null) }
    fun onGoalDraftChange(change: (GoalDraft) -> GoalDraft) = local.update { s -> s.copy(goalDraft = s.goalDraft?.let(change)) }

    /** CH2. */
    fun onSaveGoal() {
        val s = state.value
        val d = s.goalDraft ?: return
        val minor = (if (d.inFiat && s.fiat != null) fiatToUsdcMinor(d.targetText, s.fiat.rate) else MinorUnits.fromDisplay(d.targetText, 6)) ?: return
        if (MinorUnits.isZero(minor) || d.name.isBlank()) return
        local.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = setGoal(chatId, d.name.trim(), minor, deadlineIso(d.by))) {
                is AppResult.Success -> {
                    // Whoever set the goal doesn't need it introduced to them (design D6).
                    prefs?.setFlag("chama.intro.$chatId", true)
                    local.update { it.copy(busy = false, goalDraft = null, noGoal = false, notice = "Goal saved. Everyone in the group can see it.") }
                    refresh()
                }
                is AppResult.Failure -> local.update { it.copy(busy = false, error = ErrorMessages.forError(r.error)) }
            }
        }
    }

    fun onNoticeShown() = local.update { it.copy(notice = null, error = null) }
}

/** The end of the chosen period, at midnight UTC, as the API's ISO-8601. */
internal fun deadlineIso(by: GoalDeadline, now: Long = com.circaso.core.sync.nowMillis()): String? {
    val months = by.months ?: return null
    val (y, m, d) = civilFromDays((now).floorDiv(86_400_000L))
    val total = y * 12 + (m - 1) + months
    val year = (total).floorDiv(12L)
    val month = (total - year * 12 + 1).toInt()
    // The same day of the month, or the month's last day (31 Jan + 1 month = 28 or 29 Feb).
    val day = minOf(d, daysInMonth(year, month))
    return kotlin.time.Instant.fromEpochMilliseconds(daysFromCivil(year, month, day) * 86_400_000L).toString()
}

private fun isLeap(y: Long) = (y % 4 == 0L && y % 100 != 0L) || y % 400 == 0L
private fun daysInMonth(y: Long, m: Int) = when (m) { 2 -> if (isLeap(y)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }

/** Howard Hinnant's civil-date algorithms: days since 1970-01-01 to (year, month, day) and back. */
private fun civilFromDays(z0: Long): Triple<Long, Int, Int> {
    val z = z0 + 719_468
    val era = (z).floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
}

private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (y).floorDiv(400L)
    val yoe = y - era * 400
    val mp = if (m > 2) m - 3 else m + 9
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}
