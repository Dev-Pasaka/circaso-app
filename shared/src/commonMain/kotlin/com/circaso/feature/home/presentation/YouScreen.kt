package com.circaso.feature.home.presentation

import androidx.compose.foundation.layout.size
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.core.sync.FailedChange
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Where each row on the You tab leads. */
data class YouActions(
    val onEditProfile: () -> Unit,
    val onContacts: () -> Unit,
    val onBlocked: () -> Unit,
    val onSecurity: () -> Unit,
    val onAsha: () -> Unit,
    val onSecretMenu: () -> Unit,
    val onRetry: (FailedChange) -> Unit = {},
    val onDiscard: (FailedChange) -> Unit = {},
    val onNotifications: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onNeedsAttention: () -> Unit = {},
    val onCurrency: () -> Unit = {},
    val onMoneySound: (Boolean) -> Unit = {},
)

@Composable
fun YouScreen(viewModel: YouViewModel, actions: YouActions, appVersion: String, bottomPadding: androidx.compose.foundation.layout.PaddingValues) {
    val state by viewModel.state.collectAsState()
    YouContent(state, actions.copy(onRetry = viewModel::onRetry, onDiscard = viewModel::onDiscard), appVersion, bottomPadding, viewModel::onSignOutRequested, viewModel::onSignOutCancelled, viewModel::onSignOutConfirmed)
}

/** Design F2. Stateless so it can be shown with sample data. Money rows (currency, limits, verification) arrive with the wallet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YouContent(
    state: YouUiState,
    actions: YouActions,
    appVersion: String,
    bottomPadding: androidx.compose.foundation.layout.PaddingValues,
    onSignOutRequested: () -> Unit,
    onSignOutCancelled: () -> Unit,
    onSignOutConfirmed: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        // No title on the tabs (Pasaka, 2026-10-30): the tab bar says where you are.
        banner = { ConnectionBanner(showing = "Showing what is saved on this phone") },
        overlay = {
            ConfirmSheet(
                visible = state.confirmSignOut,
                title = "Sign out of Circaso?",
                message = "You can sign back in with Google or Apple. Your chats and money stay safe, and this phone stays yours. Anything not yet sent from this phone is removed.",
                confirmLabel = "Sign Out",
                onConfirm = onSignOutConfirmed,
                onDismiss = onSignOutCancelled,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = CircasoSpacing.s4)
                .padding(top = CircasoSpacing.s2, bottom = CircasoSpacing.s6 + bottomPadding.calculateBottomPadding() + padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
        ) {
            // Changes that couldn't be saved get their own screen (Pasaka, 2026-10-30); here, one row that says how many.
            if (state.failed.isNotEmpty()) {
                ListGroup {
                    ListRow(
                        title = "Needs attention",
                        sub = if (state.failed.size == 1) "1 change wasn't saved" else "${state.failed.size} changes weren't saved",
                        leading = { CircasoIconImage(CircasoIcon.Warning, c.critical, Modifier.size(22.dp)) },
                        trailing = { RowChevron() },
                        onClick = actions.onNeedsAttention,
                    )
                }
            }
            ListGroup {
                val profile = state.profile
                ListRow(
                    title = profile?.displayName ?: "Your profile",
                    sub = listOfNotNull(profile?.handle?.let { "@$it" }, state.maskedPhone?.maskedForDisplay()).joinToString("\n").ifEmpty { null },
                    meta = if (profile?.pendingSync == true) "Waiting to send" else null,
                    leading = { Avatar(profile?.displayName ?: "?", size = CircasoSizes.avatarLg, imageUrl = profile?.avatarUrl) },
                    trailing = { RowChevron() },
                    onClick = actions.onEditProfile,
                )
            }
            Section("People") {
                ListRow("Contacts", trailing = { RowChevron() }, onClick = actions.onContacts)
                RowDivider()
                ListRow("Blocked people", trailing = { RowChevron() }, onClick = actions.onBlocked)
            }
            Section("Security") {
                ListRow("Security and Privacy", trailing = { RowChevron() }, onClick = actions.onSecurity)
                RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                ListRow("Notifications", trailing = { RowChevron() }, onClick = actions.onNotifications)
            }
            Section("Money") {
                ListRow("Currency", sub = "How amounts are shown in your wallet and chats", trailing = { RowChevron() }, onClick = actions.onCurrency)
                RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                ListRow(
                    "Money sound", sub = "Coins dropping into your wallet when money arrives",
                    trailing = { com.circaso.designsystem.components.CircasoSwitch(state.moneySound, actions.onMoneySound) },
                    onClick = { actions.onMoneySound(!state.moneySound) },
                )
            }
            Section("Assistant") {
                ListRow("Asha", sub = "Ask about your money by text or voice", trailing = { RowChevron() }, onClick = actions.onAsha)
                RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                ListRow("Help", sub = "Chat with a person at Circaso", trailing = { RowChevron() }, onClick = actions.onHelp)
            }
            Section(null) {
                ListRow("Sign out", destructive = true, onClick = onSignOutRequested)
            }
            BasicText(
                "Circaso $appVersion",
                Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = actions.onSecretMenu),
                style = t.footnote.copy(color = c.inkTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
        }
    }
}

/** Changes that couldn't be saved, each with the whole reason and Retry or Discard; opened from the You tab. */
@Composable
fun NeedsAttentionScreen(viewModel: YouViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Nothing left to fix: go back rather than show an empty screen.
    // (only once the list has really been read: a new screen starts empty before the outbox answers)
    androidx.compose.runtime.LaunchedEffect(state.failedLoaded, state.failed.isEmpty()) { if (state.failedLoaded && state.failed.isEmpty()) onBack() }
    com.circaso.designsystem.chrome.CircasoScaffold(
        grouped = true,
        topBar = { com.circaso.designsystem.chrome.CircasoNavBar(title = "Needs Attention", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(top = CircasoSpacing.s4, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            BasicText("These didn't reach the server. Try again, or discard them to keep what was there.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s4 + 4.dp))
            ListGroup(Modifier.padding(horizontal = CircasoSpacing.s4)) {
                state.failed.forEachIndexed { index, change ->
                    if (index > 0) RowDivider()
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) {
                        BasicText(change.title, style = t.body.copy(color = c.ink))
                        BasicText(change.reason, style = t.subhead.copy(color = c.inkSecondary)) // the whole reason, never cut short
                        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            CircasoButton("Retry", { viewModel.onRetry(change) }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
                            CircasoButton("Discard", { viewModel.onDiscard(change) }, style = ButtonStyle.Destructive, size = ButtonSize.Small)
                        }
                    }
                }
            }
        }
    }
}
