package com.circaso.feature.keybackup.presentation

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Chats, after signing in again (2026-11-11): group names this phone can't read yet, and a backup that can bring them
 * back with one passkey answer. Gone once restored, or when there's nothing to restore.
 */
@Composable
fun RestoreGroupNamesCard(viewModel: KeyBackupViewModel, unlock: String) {
    val s by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    AnimatedVisibility(s.offerRestore, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)
                .clip(CircasoShapes.lg).background(c.surfaceRaised).padding(CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(36.dp).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.Lock, c.ink, Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    BasicText("Restore your group names", style = t.headline.copy(color = c.ink))
                    BasicText(
                        (if (s.unreadableGroups == 1) "1 group shows" else "${s.unreadableGroups} groups show") +
                            " members' names instead of its own. Your backup brings them back.",
                        style = t.footnote.copy(color = c.inkSecondary),
                    )
                }
            }
            CircasoButton("Restore With $unlock", viewModel::onRestore, style = ButtonStyle.Secondary, size = ButtonSize.Medium, block = true, busy = s.busy, enabled = s.online)
            s.error?.let { BasicText(it, style = t.footnote.copy(color = c.critical)) }
        }
    }
}

/** Security: the group key backup, on or off; hidden on phones without PRF passkeys. */
@Composable
fun GroupBackupSection(viewModel: KeyBackupViewModel) {
    val s by viewModel.state.collectAsState()
    if (!s.supported) return
    val t = CircasoTheme.typography
    val c = CircasoTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Section("Chats") {
            val on = s.onServer == true
            ListRow(
                "Group names backup",
                sub = when {
                    on && s.onPhone -> "On · group names come back after you sign in again"
                    on -> "On · restore it here with your passkey"
                    !s.online -> "Turning this on needs a connection"
                    else -> "Keep group names when you sign out or change phones"
                },
                meta = if (on && s.onPhone) "On" else null,
                trailing = {
                    when {
                        on && s.onPhone -> CircasoButton("Turn Off", viewModel::onTurnOff, style = ButtonStyle.Plain, size = ButtonSize.Small, busy = s.busy, enabled = s.online)
                        on -> CircasoButton("Restore", viewModel::onRestore, style = ButtonStyle.Secondary, size = ButtonSize.Small, busy = s.busy, enabled = s.online)
                        else -> CircasoButton("Turn On", viewModel::onTurnOn, style = ButtonStyle.Secondary, size = ButtonSize.Small, busy = s.busy, enabled = s.online)
                    }
                },
            )
        }
        s.error?.let { BasicText(it, Modifier.padding(horizontal = CircasoSpacing.s4), style = t.footnote.copy(color = c.critical)) }
    }
}
