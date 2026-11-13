package com.circaso.feature.keybackup.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Chats, after signing in again (2026-11-11): group names this phone can't read yet, and a backup that can bring them
 * back with one passkey answer. After a restore it says plainly what the backup didn't have, then can be closed.
 */
@Composable
fun RestoreGroupNamesCard(viewModel: KeyBackupViewModel, unlock: String) {
    val s by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val reduce = CircasoTheme.reduceMotion
    val show = s.offerRestore || s.leftAfterRestore > 0
    AnimatedVisibility(
        show,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(CircasoMotion.BaseMs)) + expandVertically(tween(CircasoMotion.BaseMs)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(CircasoMotion.ExitMs)) + shrinkVertically(tween(CircasoMotion.QuickMs)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)
                .glass(CircasoShapes.lg).padding(CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                    if (s.offerRestore) {
                        BasicText("Restore your group names", Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
                        BasicText(
                            (if (s.unreadableGroups == 1) "1 group shows" else "${s.unreadableGroups} groups show") +
                                " members' names instead of its own. Your backup brings them back.",
                            style = t.footnote.copy(color = c.inkSecondary),
                        )
                    } else {
                        BasicText("Group names restored", Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
                        BasicText(
                            (if (s.leftAfterRestore == 1) "1 group wasn't" else "${s.leftAfterRestore} groups weren't") +
                                " in your backup. Their names come back when a member's phone is online.",
                            style = t.footnote.copy(color = c.inkSecondary),
                        )
                    }
                }
            }
            if (s.offerRestore) {
                Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    CircasoButton("Not Now", viewModel::onNotNow, Modifier.weight(1f), style = ButtonStyle.Plain, size = ButtonSize.Medium)
                    CircasoButton("Restore With $unlock", viewModel::onRestore, Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Medium, busy = s.busy, enabled = s.online)
                }
                if (!s.online) BasicText("Restoring needs a connection.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkTertiary))
            } else {
                CircasoButton("OK", viewModel::onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
            }
            ErrorLines(s)
        }
    }
}

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
                            s.on && s.unreadableGroups > 0 -> "Some group names are missing here · restore with $unlock"
                            s.on -> "Group names come back after you sign out or change phones"
                            !s.online -> "Turning this on needs a connection"
                            else -> "Keep group names when you sign out or change phones. Saved with your Circaso passkey."
                        },
                        meta = if (s.on) "On" else null,
                        trailing = {
                            when {
                                s.unknown -> Unit
                                s.on && s.unreadableGroups > 0 -> CircasoButton("Restore", viewModel::onRestore, style = ButtonStyle.Secondary, size = ButtonSize.Medium, busy = s.busy, enabled = s.online)
                                s.on -> CircasoButton("Turn Off", viewModel::onTurnOffRequested, style = ButtonStyle.Destructive, size = ButtonSize.Medium, busy = s.busy, enabled = s.online && !s.busy)
                                else -> CircasoButton("Turn On", viewModel::onTurnOn, style = ButtonStyle.Secondary, size = ButtonSize.Medium, busy = s.busy, enabled = s.online)
                            }
                        },
                    )
                }
                Box(Modifier.padding(horizontal = CircasoSpacing.s4)) { Column { ErrorLines(s) } }
            }
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
