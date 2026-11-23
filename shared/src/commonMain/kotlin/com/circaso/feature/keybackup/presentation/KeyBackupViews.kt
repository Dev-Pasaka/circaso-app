package com.circaso.feature.keybackup.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Security › Chats: the group names backup, on or off; hidden on phones without PRF passkeys. */
@Composable
fun GroupBackupSection(viewModel: KeyBackupViewModel, unlock: String) {
    val s by viewModel.state.collectAsState()
    val reduce = CircasoTheme.reduceMotion
    AnimatedVisibility(
        s.supported,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(CircasoMotion.BaseMs)) + expandVertically(tween(CircasoMotion.BaseMs)),
        exit = fadeOut(snap()),
    ) {
        // The confirmation is drawn over the whole window from inside this Box (as HoldBanner's sheet is).
        Box {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Section("Chats") {
                    ListRow(
                        "Group names backup",
                        sub = when {
                            s.unknown -> "Checking…"
                            s.on && s.needsRepair -> "${KeyBackupCopy.RepairSub} · repair with $unlock"
                            s.on && s.unreadableGroups > 0 -> "Some group names are missing here · restore with $unlock"
                            s.on -> "Group names come back after you sign out or change phones"
                            !s.online -> "Turning this on needs a connection"
                            else -> "Keep group names when you sign out or change phones. Saved with your Circaso passkey."
                        },
                        meta = if (s.on) "On" else null,
                        trailing = {
                            when {
                                s.unknown -> Unit
                                s.on && s.needsRepair -> CircasoButton("Repair", viewModel::onTurnOn, style = ButtonStyle.Secondary, size = ButtonSize.Medium, busy = s.busy, enabled = s.online)
                                s.on && s.unreadableGroups > 0 -> CircasoButton("Restore", viewModel::onRestore, style = ButtonStyle.Secondary, size = ButtonSize.Medium, busy = s.busy, enabled = s.online)
                                s.on -> CircasoButton("Turn Off", viewModel::onTurnOffRequested, style = ButtonStyle.Destructive, size = ButtonSize.Medium, busy = s.busy, enabled = s.online && !s.busy)
                                else -> CircasoButton("Turn On", viewModel::onTurnOn, style = ButtonStyle.Secondary, size = ButtonSize.Medium, busy = s.busy, enabled = s.online)
                            }
                        },
                    )
                }
                Box(Modifier.padding(horizontal = CircasoSpacing.s4)) {
                    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                        ErrorLines(s)
                        // Here the question is always on offer beside Restore: someone sent from the Chats card finds it.
                        PasskeyMissingLines(s, viewModel, ask = s.askPasskeyMissing || s.unreadableGroups > 0)
                        if (s.on && s.passkeyMissing) {
                            CircasoButton(KeyBackupCopy.NewBackup, viewModel::onNewBackupRequested, style = ButtonStyle.Destructive, size = ButtonSize.Medium, busy = s.busy, enabled = s.online && !s.busy)
                        }
                    }
                }
            }
            ConfirmSheet(
                visible = s.confirmNew,
                title = KeyBackupCopy.NewBackupTitle,
                message = KeyBackupCopy.NewBackupMessage,
                confirmLabel = KeyBackupCopy.NewBackup, destructive = true, icon = com.circaso.designsystem.components.CircasoIcon.Shield,
                enabled = s.online, note = if (!s.online) "Starting a new backup needs a connection." else null, onConfirm = viewModel::onNewBackup, onDismiss = viewModel::onNewBackupDismissed,
            )
            ConfirmSheet(
                visible = s.confirmOff,
                title = "Turn off group names backup?",
                message = "Your backed-up group keys are deleted from Circaso, for every phone. Group names on this phone stay. After you sign out or change phones, groups show members' names until someone in the group is online.",
                confirmLabel = "Turn Off", destructive = true, icon = com.circaso.designsystem.components.CircasoIcon.Shield,
                enabled = s.online, note = if (!s.online) "Turning this off needs a connection." else null, onConfirm = viewModel::onTurnOff, onDismiss = viewModel::onTurnOffDismissed,
            )
        }
    }
}

/**
 * A restore that couldn't reach the backup's passkey: after backing out, a quiet question; once known (or answered
 * yes), where the passkey may be and what to do.
 */
@Composable
private fun PasskeyMissingLines(s: KeyBackupUi, viewModel: KeyBackupViewModel, ask: Boolean = s.askPasskeyMissing) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    if (!s.on) return
    if (s.passkeyMissing) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            BasicText(KeyBackupCopy.PasskeyMissing, style = t.footnote.copy(color = c.inkSecondary))
        }
    } else if (ask) {
        CircasoButton(KeyBackupCopy.AskPasskeyMissing, viewModel::onPasskeyMissing, style = ButtonStyle.Plain, size = ButtonSize.Medium)
    }
}

@Composable
private fun ErrorLines(s: KeyBackupUi) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    s.error?.let { e ->
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(e, style = t.footnote.copy(color = c.critical))
            s.errorRef?.let { BasicText("Ref $it", style = t.caption1.copy(color = c.inkTertiary)) }
        }
    }
}
