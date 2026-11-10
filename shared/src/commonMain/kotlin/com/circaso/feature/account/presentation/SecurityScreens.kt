package com.circaso.feature.account.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.DetailRow
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.auth.domain.SocialProvider

@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onBack: () -> Unit,
    onActivity: () -> Unit,
    onSendMoney: () -> Unit = {},
    payments: @Composable () -> Unit = {},
    /** The Payments section's own sheets (turning biometric approval off), drawn with the screen's other sheets. */
    paymentsOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    SecurityContent(
        state, onBack, onActivity, viewModel::onLink, viewModel::onConfirmRequested, viewModel::onConfirmDismissed,
        viewModel::onDeleteText, viewModel::onConfirmed, onSendMoney = onSendMoney, onDeleteBlockedDismissed = viewModel::onDeleteBlockedDismissed,
        payments = payments, paymentsOverlay = paymentsOverlay,
    )
}

/** Design ID-24, ID-26, ID-27 and ID-28. Wallet sections come in through [payments]; the recovery key is not shown yet. */
@Composable
fun SecurityContent(
    state: SecurityUiState,
    onBack: () -> Unit,
    onActivity: () -> Unit,
    onLink: (SocialProvider) -> Unit,
    onConfirmRequested: (SecurityConfirm) -> Unit,
    onConfirmDismissed: () -> Unit,
    onDeleteText: (String) -> Unit,
    onConfirmed: () -> Unit,
    onSendMoney: () -> Unit = {},
    onDeleteBlockedDismissed: () -> Unit = {},
    payments: @Composable () -> Unit = {},
    paymentsOverlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val overview = state.overview
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Account and Security", onBack = onBack) },
        banner = { ConnectionBanner() },
        overlay = {
            paymentsOverlay()
            val confirm = state.confirm
            ConfirmSheet(
                visible = confirm == SecurityConfirm.SignOut,
                title = "Sign out of Circaso?",
                message = "You can sign back in with Google or Apple. Your chats and money stay safe, and this phone stays yours. Anything not yet sent from this phone is removed.",
                confirmLabel = "Sign Out", onConfirm = onConfirmed, onDismiss = onConfirmDismissed,
            )
            ConfirmSheet(
                visible = confirm == SecurityConfirm.RemovePhone,
                title = "Remove this phone?",
                message = "For when you sell or give it away. Your Circaso data is wiped from this phone, and the next sign-in on any phone will verify your number again.",
                confirmLabel = "Remove This Phone", destructive = true, onConfirm = onConfirmed, onDismiss = onConfirmDismissed,
            )
            ConfirmSheet(
                visible = confirm is SecurityConfirm.Unlink,
                title = "Remove this sign-in?",
                message = "You'll no longer be able to sign in with it. You need at least one sign-in account.",
                confirmLabel = "Remove", destructive = true, onConfirm = onConfirmed, onDismiss = onConfirmDismissed,
            )
            // ID-28b: the server refused because the wallet still holds money.
            CircasoSheet(visible = state.deleteBlocked, onDismiss = onDeleteBlockedDismissed, label = "Send out your money first") {
                BasicText("Send out your money first", style = t.title2.copy(color = c.ink))
                BasicText("Your wallet still holds ${state.walletBalance ?: "money"}. Move it to someone or to an external wallet, then come back to delete your account.", style = t.body.copy(color = c.inkSecondary))
                state.walletBalance?.let { DetailRow("Wallet balance · Available now", it) }
                CircasoButton("Send My Money", { onDeleteBlockedDismissed(); onSendMoney() }, size = ButtonSize.Large, block = true)
                CircasoButton("Not Now", onDeleteBlockedDismissed, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
            CircasoSheet(visible = confirm == SecurityConfirm.DeleteAccount, onDismiss = onConfirmDismissed, label = "Delete your account") {
                BasicText("Delete your account?", style = t.title2.copy(color = c.ink))
                BasicText("This can't be undone. Your account closes now. After 30 days your profile, contacts and Asha chat are erased for good, and your number and handle are released.", style = t.body.copy(color = c.inkSecondary))
                CircasoTextField(state.deleteText, onDeleteText, label = "Type DELETE to confirm", placeholder = "DELETE")
                CircasoButton("Delete My Account", onConfirmed, style = ButtonStyle.Destructive, size = ButtonSize.Large, enabled = state.canDelete, block = true)
                CircasoButton("Cancel", onConfirmDismissed, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
        ) {
            Section("Phone") {
                ListRow("Phone number", sub = overview?.maskedPhone?.maskedForDisplay() ?: "Not loaded yet", meta = if (overview?.maskedPhone != null) "Verified" else null)
            }
            overview?.device?.let { device ->
                Section("This phone") { ListRow(device.model, sub = "Active since ${device.activeSince.substringBefore('T')}") }
            }
            Section("Sign-in accounts") {
                SocialProvider.entries.forEachIndexed { index, provider ->
                    if (index > 0) RowDivider()
                    val linked = overview?.providers?.firstOrNull { it.provider.equals(provider.apiName, ignoreCase = true) }
                    ListRow(
                        title = provider.name,
                        sub = linked?.maskedEmail ?: if (linked != null) "Linked" else null,
                        trailing = {
                            if (linked != null) CircasoButton("Remove", { onConfirmRequested(SecurityConfirm.Unlink(provider)) }, style = ButtonStyle.Destructive, size = ButtonSize.Small, enabled = state.online && !state.busy)
                            else CircasoButton("Link", { onLink(provider) }, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = state.online && !state.busy)
                        },
                    )
                }
            }
            payments()
            Section("Activity") { ListRow("Security activity", trailing = { RowChevron() }, onClick = onActivity) }
            Section(null) {
                ListRow("Sign out", onClick = { onConfirmRequested(SecurityConfirm.SignOut) })
                RowDivider()
                ListRow("Remove this phone", destructive = true, onClick = if (state.online) ({ onConfirmRequested(SecurityConfirm.RemovePhone) }) else null)
                RowDivider()
                ListRow("Delete account", destructive = true, onClick = if (state.online) ({ onConfirmRequested(SecurityConfirm.DeleteAccount) }) else null)
            }
            if (!state.online) BasicText("Removing this phone and deleting your account need a connection.", style = t.footnote.copy(color = c.inkSecondary))
            state.message?.let { BasicText(it, Modifier.fillMaxWidth(), style = t.subhead.copy(color = c.ink, textAlign = TextAlign.Start)) }
        }
    }
}

@Composable
fun SecurityLogScreen(viewModel: SecurityLogViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    SecurityLogContent(state, onBack)
}

@Composable
fun SecurityLogContent(state: SecurityLogUiState, onBack: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoScaffold(
        grouped = true,
        topBar = { CircasoNavBar(title = "Security Activity", onBack = onBack) },
        banner = { ConnectionBanner() },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            if (state.events.isEmpty()) {
                BasicText(if (state.loaded) "Nothing to show yet." else "Loading…", Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6), style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
            } else {
                Section(null) {
                    state.events.forEachIndexed { index, event ->
                        if (index > 0) RowDivider()
                        ListRow(
                            title = SecurityLogViewModel.label(event.type),
                            sub = listOfNotNull(event.deviceModel, event.at.substringBefore('T')).joinToString(" · "),
                        )
                    }
                }
                BasicText("Something you don't recognise? Sign out everywhere by removing this phone, then sign in again.", style = t.footnote.copy(color = c.inkSecondary))
            }
        }
    }
}
