package com.circaso.feature.chama.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionBanner
import com.circaso.core.sync.ConnectionState
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.SuggestionChip
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.chama.domain.RemoveGoalUseCase
import com.circaso.feature.chama.domain.SetGoalUseCase
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.fiatToUsdcMinor
import com.circaso.feature.wallet.domain.usdcToFiat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TargetUiState(
    val groupName: String = "",
    val name: String = "",
    val amountText: String = "",
    val by: GoalDeadline = GoalDeadline.None,
    val fiat: FiatValue? = null,
    val money: GroupMoney? = null,
    val online: Boolean = true,
    val saving: Boolean = false,
    val removing: Boolean = false,
    val error: String? = null,
    val reference: String? = null,
    val done: Boolean = false,
    /** Arrived from the wallet set-up (GW7 → GW5 → here): GW7b's "wallet is ready" line. */
    val walletJustReady: Boolean = false,
) {
    val amountMinor: String? get() = (fiat?.let { fiatToUsdcMinor(amountText, it.rate) } ?: MinorUnits.fromDisplay(amountText, 6))?.takeIf { !MinorUnits.isZero(it) }
    val usdcHint: String? get() = if (fiat != null) amountMinor?.let { "≈ " + MinorUnits.toDisplay(it, 6) + " USDC" } else null
    val editing get() = money?.goal != null
    val canSave get() = online && !saving && !removing && name.isNotBlank() && amountMinor != null && money?.walletActive != false
}

/**
 * GW7b: add (or change) a group's target: what it's for, how much, an optional date. Admins only, saved online
 * (CH2); a target needs the group's active wallet (`chama.no_wallet` otherwise). Remove Target is CH8: the wallet,
 * its money and open requests stay.
 */
class TargetViewModel(
    private val chatId: String,
    walletReady: Boolean,
    observeChat: ObserveChatUseCase,
    observeMoney: ObserveGroupMoneyUseCase,
    private val setGoal: SetGoalUseCase,
    private val removeGoal: RemoveGoalUseCase,
    observeWallet: ObserveWalletUseCase,
    connection: ConnectionState,
    private val refreshMoney: RefreshGroupMoneyUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(TargetUiState(walletJustReady = walletReady))
    val state: StateFlow<TargetUiState> = _state.asStateFlow()
    private var filled = false

    init {
        viewModelScope.launch { observeChat(chatId).collect { chat -> _state.update { it.copy(groupName = chat?.title.orEmpty()) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
        viewModelScope.launch { observeWallet().collect { w -> _state.update { it.copy(fiat = w?.fiat) } } }
        viewModelScope.launch {
            observeMoney(chatId).collect { m ->
                _state.update { it.copy(money = m) }
                // Changing a target starts from what it is now.
                val g = m?.goal
                if (!filled && g != null) {
                    filled = true
                    val fiat = _state.value.fiat
                    val text = fiat?.let { usdcToFiat(g.targetMinor, it.rate)?.substringBefore('.') } ?: MinorUnits.toDisplay(g.targetMinor, 6).replace(",", "")
                    _state.update { it.copy(name = g.name, amountText = text) }
                }
            }
        }
        viewModelScope.launch { refreshMoney(chatId) }
    }

    fun onName(text: String) = _state.update { it.copy(name = text.take(60), error = null) }
    fun onAmount(text: String) {
        val clean = text.filter { it.isDigit() || it == '.' }
        if (clean.count { it == '.' } > 1) return
        _state.update { it.copy(amountText = clean, error = null) }
    }
    fun onBy(by: GoalDeadline) = _state.update { it.copy(by = by) }

    fun onSave() {
        val s = _state.value
        val minor = s.amountMinor ?: return
        if (!s.canSave) return
        _state.update { it.copy(saving = true, error = null, reference = null) }
        viewModelScope.launch {
            when (val r = setGoal(chatId, s.name.trim(), minor, deadlineIso(s.by))) {
                is AppResult.Success -> { refreshMoney(chatId); _state.update { it.copy(saving = false, done = true) } }
                is AppResult.Failure -> _state.update { it.copy(saving = false, error = ErrorMessages.forError(r.error), reference = ErrorMessages.reference(r.error)) }
            }
        }
    }

    fun onRemove() {
        val s = _state.value
        if (!s.online || s.removing || !s.editing) return
        _state.update { it.copy(removing = true, error = null) }
        viewModelScope.launch {
            when (val r = removeGoal(chatId)) {
                is AppResult.Success -> _state.update { it.copy(removing = false, done = true) }
                is AppResult.Failure -> _state.update { it.copy(removing = false, error = ErrorMessages.forError(r.error), reference = ErrorMessages.reference(r.error)) }
            }
        }
    }
}

@Composable
fun TargetScreen(viewModel: TargetViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.done) { if (state.done) onBack() }
    TargetContent(state, onBack, viewModel::onName, viewModel::onAmount, viewModel::onBy, viewModel::onSave, viewModel::onRemove)
}

@Composable
fun TargetContent(state: TargetUiState, onBack: () -> Unit, onName: (String) -> Unit, onAmount: (String) -> Unit, onBy: (GoalDeadline) -> Unit, onSave: () -> Unit, onRemove: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = if (state.editing) "Change Target" else "Add a Target", onBack = onBack) },
        banner = { ConnectionBanner(showing = "Saving a target needs a connection") },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = CircasoSpacing.s4)
                .padding(top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            if (state.walletJustReady) {
                Row(
                    Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircasoIconImage(CircasoIcon.Check, c.ink, Modifier.size(18.dp))
                    BasicText("${state.groupName.ifEmpty { "The group" }}’s wallet is ready.", style = t.subhead.copy(color = c.ink))
                }
            }
            if (state.money?.walletActive == false || (state.money != null && state.money.wallet == null)) {
                BasicText("A target needs the group’s wallet first.", style = t.footnote.copy(color = c.warning))
            }
            CircasoTextField(state.name, onName, label = "What it’s for", placeholder = "December trip to the coast")
            CircasoTextField(
                state.amountText, onAmount, label = "Target", placeholder = if (state.fiat != null) "50,000" else "400",
                leading = { BasicText(state.fiat?.currency ?: "USDC", style = t.body.copy(color = c.inkSecondary)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            state.usdcHint?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(start = CircasoSpacing.s1)) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("By (optional)", style = t.footnote.copy(color = c.inkSecondary))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    items(GoalDeadline.entries) { d -> SuggestionChip(d.label, { onBy(d) }, selected = d == state.by) }
                }
            }
            BasicText(
                if (state.editing) "Changing the target keeps what's been put in towards it. Everyone in the group sees it."
                else "Everyone in the group sees the target and how far it is. Contributions count towards it from now.",
                style = t.footnote.copy(color = c.inkSecondary),
            )
            state.error?.let { e ->
                BasicText(e + (state.reference?.let { " Ref $it" } ?: ""), style = t.footnote.copy(color = c.critical))
            }
            CircasoButton("Save Target", onSave, size = ButtonSize.Large, block = true, enabled = state.canSave, busy = state.saving)
            if (!state.online) BasicText("Needs a connection", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            if (state.editing && state.money?.isAdmin == true) {
                CircasoButton("Remove Target", onRemove, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true, enabled = state.online && !state.saving, busy = state.removing)
                BasicText("The wallet, its money and any open requests stay.", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            }
        }
    }
}
