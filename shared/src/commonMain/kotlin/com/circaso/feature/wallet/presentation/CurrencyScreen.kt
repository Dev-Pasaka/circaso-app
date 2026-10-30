package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.circaso.designsystem.theme.CircasoShapes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.localCurrencyCode
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.wallet.domain.RefreshWalletUseCase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A currency money can be shown in: the ISO 4217 code, its name and its country's (or union's) flag. */
data class CurrencyOption(val code: String, val name: String, val flag: String)

/** The currencies offered, East Africa first, then the rest of Africa, then the world's most used. */
val CurrencyOptions = listOf(
    CurrencyOption("KES", "Kenyan Shilling", "🇰🇪"), CurrencyOption("UGX", "Ugandan Shilling", "🇺🇬"), CurrencyOption("TZS", "Tanzanian Shilling", "🇹🇿"),
    CurrencyOption("RWF", "Rwandan Franc", "🇷🇼"), CurrencyOption("ETB", "Ethiopian Birr", "🇪🇹"), CurrencyOption("NGN", "Nigerian Naira", "🇳🇬"),
    CurrencyOption("GHS", "Ghanaian Cedi", "🇬🇭"), CurrencyOption("ZAR", "South African Rand", "🇿🇦"), CurrencyOption("EGP", "Egyptian Pound", "🇪🇬"),
    CurrencyOption("MAD", "Moroccan Dirham", "🇲🇦"), CurrencyOption("XOF", "West African CFA Franc", "🇸🇳"), CurrencyOption("ZMW", "Zambian Kwacha", "🇿🇲"),
    CurrencyOption("USD", "US Dollar", "🇺🇸"), CurrencyOption("EUR", "Euro", "🇪🇺"), CurrencyOption("GBP", "British Pound", "🇬🇧"),
    CurrencyOption("AED", "UAE Dirham", "🇦🇪"), CurrencyOption("INR", "Indian Rupee", "🇮🇳"), CurrencyOption("CNY", "Chinese Yuan", "🇨🇳"),
    CurrencyOption("JPY", "Japanese Yen", "🇯🇵"), CurrencyOption("CAD", "Canadian Dollar", "🇨🇦"), CurrencyOption("AUD", "Australian Dollar", "🇦🇺"),
    CurrencyOption("BRL", "Brazilian Real", "🇧🇷"),
)

/** The option for a code, or a plain one for a currency outside the list (a region we don't name). */
fun currencyOption(code: String): CurrencyOption = CurrencyOptions.firstOrNull { it.code == code } ?: CurrencyOption(code, code, "🏳️")

data class CurrencyUiState(val chosen: String? = null, val automatic: String? = localCurrencyCode()) {
    /** What money shows in now: the choice, else the phone's region's. */
    val inUse get() = chosen ?: automatic
}

/**
 * You → Currency: the currency every amount is shown in (wallet cards, chat money cards, the pay preview). Automatic
 * follows the phone's region. A change refreshes the wallet so every amount follows at once.
 */
class CurrencyViewModel(private val prefs: AppPreferences, private val refreshWallet: RefreshWalletUseCase) : ViewModel() {
    val state: StateFlow<CurrencyUiState> = prefs.currency.map { CurrencyUiState(chosen = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CurrencyUiState())

    fun onChoose(code: String?) {
        viewModelScope.launch {
            prefs.setCurrency(code)
            refreshWallet()
        }
    }
}

@Composable
fun CurrencyScreen(viewModel: CurrencyViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    CurrencyContent(state, viewModel::onChoose, onBack)
}

@Composable
fun CurrencyContent(state: CurrencyUiState, onChoose: (String?) -> Unit, onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val inUse = state.inUse?.let(::currencyOption)
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Currency", onBack = onBack) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = CircasoSpacing.s4, end = CircasoSpacing.s4, top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            // The one in use, always on top and larger: what every amount is shown in right now.
            if (inUse != null) item(key = "in-use") {
                Row(
                    Modifier.fillMaxWidth().animateItem().clip(CircasoShapes.lg).background(c.surface)
                        .border(1.5.dp, c.action, CircasoShapes.lg).padding(CircasoSpacing.s5)
                        .semantics(mergeDescendants = true) { contentDescription = "Showing amounts in ${inUse.name}" + if (state.chosen == null) ", automatic" else "" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
                ) {
                    BasicText(inUse.flag, style = t.largeTitle)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        BasicText(inUse.code, style = t.title1.copy(color = c.ink, fontWeight = FontWeight.Bold))
                        BasicText(inUse.name + if (state.chosen == null) " · automatic" else "", style = t.callout.copy(color = c.inkSecondary))
                    }
                    CircasoIconImage(CircasoIcon.Check, c.action, Modifier.size(24.dp))
                }
            }
            item(key = "explain") {
                BasicText(
                    "Amounts in your wallet and chats show in this currency. This only changes how amounts are shown; your coins don't change.",
                    style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            item(key = "list") {
                Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface)) {
                    CurrencyRow("📍", "Automatic", state.automatic?.let { "$it · from your phone's region" } ?: "From your phone's region", state.chosen == null) { onChoose(null) }
                    CurrencyOptions.filter { it.code != state.chosen }.forEach { option ->
                        RowDivider(Modifier.padding(start = 60.dp))
                        CurrencyRow(option.flag, option.name, option.code, selected = false) { onChoose(option.code) }
                    }
                }
            }
        }
    }
}

/** One choice: its flag, name and code, and a round marker that fills when it is the one in use. */
@Composable
private fun CurrencyRow(flag: String, title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        BasicText(flag, style = t.title2, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            BasicText(title, style = t.body.copy(color = c.ink))
            BasicText(sub, style = t.footnote.copy(color = c.inkSecondary))
        }
        Box(
            Modifier.size(22.dp).clip(CircasoShapes.circle)
                .then(if (selected) Modifier.background(c.action) else Modifier.border(1.5.dp, c.inkTertiary, CircasoShapes.circle)),
            contentAlignment = Alignment.Center,
        ) { if (selected) CircasoIconImage(CircasoIcon.Check, c.bg, Modifier.size(14.dp)) }
    }
}
