package com.circaso.feature.home.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.chatListTime
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.account.domain.ObserveSecurityLogUseCase
import com.circaso.feature.account.domain.RefreshSecurityLogUseCase
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.ObserveTransfersUseCase
import com.circaso.feature.wallet.domain.ObserveWalletUseCase
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.formatFiat
import com.circaso.feature.wallet.domain.usdcToFiat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** One line in the inbox (design F4): who, when, what, and where a tap goes. */
data class Notice(val id: String, val title: String, val text: String, val at: Long, val transferId: String? = null, val fromCircaso: Boolean = false, val unread: Boolean = false)

data class NotificationsUiState(val notices: List<Notice> = emptyList(), val loaded: Boolean = false)

/**
 * The inbox is built on the phone from what it already knows (payments, security events, wallet set-up requests):
 * there is no notification route on the server yet, so nothing is invented. Opening the screen marks everything read.
 */
class NotificationsViewModel(
    observeTransfers: ObserveTransfersUseCase,
    observeWallet: ObserveWalletUseCase,
    observeSecurityLog: ObserveSecurityLogUseCase,
    private val refreshSecurityLog: RefreshSecurityLogUseCase,
    wallet: WalletRepositoryImpl,
    private val preferences: AppPreferences,
) : ViewModel() {
    val state: StateFlow<NotificationsUiState> = combine(observeTransfers(), observeWallet(), observeSecurityLog(), wallet.setupRequestedBy, preferences.notificationsSeenAt) { transfers, w, events, requestedBy, seenAt ->
        val fiat = w?.fiat
        val notices = buildList {
            transfers.forEach { t ->
                val amount = amountText(t.amount.amountMinor, fiat)
                when {
                    t.direction == TransferDirection.In -> add(Notice(t.transferId, t.counterpartName ?: "Someone", "Sent you $amount" + (t.note?.let { " · $it" } ?: ""), t.createdAt, t.transferId))
                    t.state == TransferState.Confirmed -> add(Notice(t.transferId, t.counterpartName ?: "You", "You sent $amount" + (t.note?.let { " · $it" } ?: "") + " · delivered", t.updatedAt, t.transferId))
                    t.state == TransferState.Failed -> add(Notice(t.transferId, "Circaso", "Your payment of $amount didn't go through.", t.updatedAt, t.transferId, fromCircaso = true))
                }
            }
            events.forEach { e ->
                val at = try { Instant.parse(e.at).toEpochMilliseconds() } catch (x: Exception) { 0L }
                add(Notice("sec-" + e.eventId, "Circaso", securityText(e.type, e.deviceModel, e.detail), at, fromCircaso = true))
            }
            requestedBy?.let { add(Notice("setup", it, "Wants to send you money. Set up your wallet to receive it.", nowMillis())) }
        }.sortedByDescending { it.at }.map { it.copy(unread = it.at > seenAt) }
        NotificationsUiState(notices, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationsUiState())

    init { viewModelScope.launch { refreshSecurityLog() } }

    fun onSeen() { viewModelScope.launch { preferences.setNotificationsSeenAt(nowMillis()) } }

    private fun amountText(minor: String, fiat: FiatValue?) = fiat?.let { usdcToFiat(minor, it.rate) }?.let { formatFiat(fiat.currency, it) } ?: (MinorUnits.toDisplay(minor, 6) + " USDC")

    private fun securityText(type: String, device: String?, detail: String?) = when (type.lowercase()) {
        "sign_in", "signin", "session.created" -> "New sign-in" + (device?.let { " on $it" } ?: "")
        "device.registered", "device_registered" -> "This phone was registered" + (device?.let { ": $it" } ?: "")
        "provider.linked" -> "A sign-in account was linked"
        "provider.unlinked" -> "A sign-in account was unlinked"
        "recovery.started" -> "Account recovery started"
        "recovery.completed" -> "Account recovery completed"
        else -> detail ?: type.replace('_', ' ').replace('.', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Design F4. */
@Composable
fun NotificationsScreen(viewModel: NotificationsViewModel, onBack: () -> Unit, onOpenTransfer: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val now = nowMillis()
    LaunchedEffect(state.loaded) { if (state.loaded) viewModel.onSeen() }
    CircasoScaffold(topBar = { CircasoNavBar(title = "Notifications", onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)) {
            if (state.loaded && state.notices.isEmpty()) item { BasicText("Nothing yet. Payments, set-up requests and security notices will show here.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(CircasoSpacing.s4)) }
            items(state.notices, key = { it.id }) { n ->
                Row(
                    Modifier.fillMaxWidth().then(if (n.transferId != null) com.circaso.designsystem.motion.pressableModifierFor { onOpenTransfer(n.transferId) } else Modifier)
                        .padding(horizontal = CircasoSpacing.s4, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
                ) {
                    if (n.fromCircaso) AshaMark(size = CircasoSizes.avatarMd) else Avatar(n.title, size = CircasoSizes.avatarMd)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            BasicText(n.title, style = t.headline.copy(color = c.ink, fontWeight = if (n.unread) FontWeight.Bold else FontWeight.SemiBold))
                            BasicText("· " + chatListTime(n.at, now), style = t.caption1.copy(color = c.inkSecondary))
                        }
                        BasicText(n.text, style = t.callout.copy(color = if (n.unread) c.ink else c.inkSecondary))
                    }
                }
                RowDivider(Modifier.padding(start = 68.dp))
            }
        }
    }
}
