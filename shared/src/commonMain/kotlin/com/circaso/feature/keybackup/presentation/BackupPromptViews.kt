package com.circaso.feature.keybackup.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetActions
import com.circaso.designsystem.chrome.SheetMessage
import com.circaso.designsystem.chrome.SheetTone
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** What the backup sheet's buttons do. */
class BackupPromptActions(
    val onPrimary: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onNotHere: () -> Unit = {},
    val onStartNew: () -> Unit = {},
    val onStartNewCancelled: () -> Unit = {},
)

/**
 * The backup sheet's layer (2026-11-23), in the Chats tab's overlay. Whether the screen is calm is told to the view
 * model by Home ([BackupPromptViewModel.onCalm]), which stays composed when the tab changes.
 */
@Composable
fun BoxScope.BackupPromptLayer(viewModel: BackupPromptViewModel, unlock: String) {
    val reader = com.circaso.core.platform.rememberScreenReaderOn()
    LaunchedEffect(reader) { viewModel.onScreenReader(reader) }
    val s by viewModel.state.collectAsState()
    BackupPromptSheet(
        s, unlock,
        BackupPromptActions(viewModel::onPrimary, viewModel::onClose, viewModel::onNotHere, viewModel::onStartNewRequested, viewModel::onStartNewCancelled),
    )
}

/** The words a sheet state shows; see [backupPromptLook]. */
data class BackupPromptLook(
    val title: String,
    val message: String,
    val icon: CircasoIcon,
    val tone: SheetTone,
    val primary: String,
    val destructive: Boolean = false,
    /** The plain button under the main one; null when there is none. */
    val secondary: String? = BackupPromptCopy.NotNow,
)

/** Every word on the backup sheets, in one place for the sheet, the previews and the tests. */
object BackupPromptCopy {
    const val NotNow = "Not Now"
    const val SetUpTitle = "Back up your chats"
    const val SetUpMessage = "Keeps your group names and the names you save, so they come back on a new phone. It's locked with your Circaso passkey, so only you can open it."
    const val SetUpDoneTitle = "Your group names are backed up"
    const val SetUpDoneMessage = "Group names and saved names come back when you change phones or sign in again."
    const val RestoreTitle = "Restore your chats"
    const val RestoreMessage = "Your backup brings back your group names and the names you saved. Open it with your Circaso passkey."
    const val RestoredTitle = "Your group names are back"
    const val RestoredMessage = "Group names and saved names are on this phone again."
    const val NotHereTitle = "Your passkey isn't on this phone"
    const val NotHereMessage = "Your backup is locked with a passkey on another phone, or in another Google or Apple account. " +
        "Open it from that device, or start a new backup with this phone."
    const val UseAnotherDevice = "Use Another Device"
    const val NewBackupDoneTitle = "Your new backup is on"
    const val NewBackupDoneMessage = "Group names on this phone are backed up again. New groups are backed up as you join them."
    const val RepairTitle = "Finish securing your backup"
    const val RepairMessage = "Your backup is locked with a passkey you no longer use, so a new phone couldn't open it. Your passkey fixes it in one step."
    const val RepairedTitle = "Your backup is secure"
    const val RepairedMessage = "A new phone can restore it with your Circaso passkey."
    const val Done = "Done"
    const val Offline = "Needs a connection. You can carry on when you're back online."
    const val CardTitle = "Restore your chats"
    const val CardMessage = "Your backup is ready. Restore it to bring back group names and the names you saved."

    const val NoOtherDevice = "This phone couldn't offer another device. On the phone that has your passkey, open Circaso, then Security, and tap Repair. Or start a new backup."
    const val TurnedOffElsewhere = "Your backup was turned off on another phone. Turn it on again to keep your group names."

    fun groupsMissing(n: Int) = if (n == 1) "1 group shows members' names instead of its own until you restore."
        else "$n groups show members' names instead of their own until you restore."
    fun groupsLeft(n: Int) = (if (n == 1) "1 group wasn't" else "$n groups weren't") + " in your backup. Their names come back when a member's phone is online."
}

/** The look of [s]'s sheet and step; [unlock] is "Face ID", "Fingerprint" or "Screen Lock". */
fun backupPromptLook(s: BackupPromptUi, unlock: String): BackupPromptLook? {
    val sheet = s.sheet ?: return null
    return when (s.step) {
        BackupStep.Done -> when {
            sheet == BackupSheet.SetUp -> BackupPromptLook(BackupPromptCopy.SetUpDoneTitle, BackupPromptCopy.SetUpDoneMessage, CircasoIcon.Check, SheetTone.Neutral, BackupPromptCopy.Done, secondary = null)
            sheet == BackupSheet.Repair -> BackupPromptLook(BackupPromptCopy.RepairedTitle, BackupPromptCopy.RepairedMessage, CircasoIcon.Check, SheetTone.Neutral, BackupPromptCopy.Done, secondary = null)
            s.startedNew -> BackupPromptLook(BackupPromptCopy.NewBackupDoneTitle, BackupPromptCopy.NewBackupDoneMessage, CircasoIcon.Check, SheetTone.Neutral, BackupPromptCopy.Done, secondary = null)
            else -> BackupPromptLook(
                BackupPromptCopy.RestoredTitle,
                if (s.leftAfterRestore > 0) BackupPromptCopy.groupsLeft(s.leftAfterRestore) else BackupPromptCopy.RestoredMessage,
                CircasoIcon.Check, SheetTone.Neutral, BackupPromptCopy.Done, secondary = null,
            )
        }
        BackupStep.PasskeyNotHere -> BackupPromptLook(BackupPromptCopy.NotHereTitle, BackupPromptCopy.NotHereMessage, CircasoIcon.Warning, SheetTone.Warning, BackupPromptCopy.UseAnotherDevice)
        BackupStep.ConfirmNew -> BackupPromptLook(KeyBackupCopy.NewBackupTitle, KeyBackupCopy.NewBackupMessage, CircasoIcon.Shield, SheetTone.Critical, KeyBackupCopy.NewBackup, destructive = true, secondary = "Cancel")
        BackupStep.Ask -> when (sheet) {
            BackupSheet.SetUp -> BackupPromptLook(BackupPromptCopy.SetUpTitle, BackupPromptCopy.SetUpMessage, CircasoIcon.Lock, SheetTone.Neutral, "Turn On With $unlock")
            BackupSheet.Restore -> BackupPromptLook(
                BackupPromptCopy.RestoreTitle,
                BackupPromptCopy.RestoreMessage + if (s.unreadableGroups > 0) " " + BackupPromptCopy.groupsMissing(s.unreadableGroups) else "",
                CircasoIcon.Lock, SheetTone.Neutral, "Restore With $unlock",
            )
            BackupSheet.Repair -> BackupPromptLook(BackupPromptCopy.RepairTitle, BackupPromptCopy.RepairMessage, CircasoIcon.Shield, SheetTone.Neutral, "Repair With $unlock")
        }
    }
}

/**
 * The one backup sheet: set up, restore (with the passkey-not-here choice and the new-backup confirm inside it) or
 * repair, then a short "done". Stateless, for the layer and the previews. Each step crossfades in place (a cut with
 * Reduce Motion); the last look is held while the sheet slides away, so it never empties on the way out.
 */
@Composable
fun BoxScope.BackupPromptSheet(s: BackupPromptUi, unlock: String, actions: BackupPromptActions) {
    val now = backupPromptLook(s, unlock)
    val held = remember { mutableStateOf<Pair<BackupPromptUi, BackupPromptLook>?>(null) }
    if (s.visible && now != null) held.value = s to now
    val reduce = CircasoTheme.reduceMotion
    // Always composed, so its first rise animates (an AnimatedVisibility that starts visible doesn't).
    CircasoSheet(
        visible = s.visible && held.value != null,
        // A drag, the scrim or Back always mean "not now", on every step; Cancel on the confirm goes back a step.
        onDismiss = actions.onClose,
        label = held.value?.second?.title ?: "",
        dismissible = held.value?.first?.busy != true,
        // It waits for every other sheet; it doesn't count itself.
        counted = false,
    ) {
        val (shown, look) = held.value ?: return@CircasoSheet
        AnimatedContent(
            shown to look,
            transitionSpec = {
                if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                else fadeIn(tween(CircasoMotion.BaseMs)) togetherWith fadeOut(tween(CircasoMotion.QuickMs))
            },
            // A new step crossfades; the same step only updates (busy, offline, an error line).
            contentKey = { (st, _) -> st.sheet to st.step },
            label = "backup-step",
        ) { (st, lk) ->
            Column(
                Modifier.animateContentSize(if (reduce) snap() else tween(CircasoMotion.BaseMs)),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
            ) {
                // Every step after the first is announced: it changes after the system's passkey sheet closes.
                Box(Modifier.semantics { if (st.step != BackupStep.Ask) liveRegion = LiveRegionMode.Polite }) {
                    SheetMessage(lk.title, lk.message, lk.icon, tone = lk.tone)
                }
                BackupSheetButtons(st, lk, actions, st.step == BackupStep.ConfirmNew)
            }
        }
    }
}

@Composable
private fun BackupSheetButtons(s: BackupPromptUi, look: BackupPromptLook, actions: BackupPromptActions, confirm: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val done = s.step == BackupStep.Done
    // Offline, every step but "done" waits: the button can't be tapped and says why, and it is live again once back.
    // A problem is said right under the button that met it; a plain notice (why the sheet changed) likewise.
    val note = s.error ?: (if (!s.online && !done) BackupPromptCopy.Offline else s.notice)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        SheetActions(
            look.primary, actions.onPrimary,
            // The passkey-not-here step has two ways forward before "Not Now": its own row below.
            secondaryLabel = if (s.step == BackupStep.PasskeyNotHere) null else look.secondary,
            onSecondary = if (confirm) actions.onStartNewCancelled else actions.onClose,
            destructive = look.destructive, enabled = done || s.online, busy = s.busy, note = note, noteIsError = s.error != null,
        )
        s.errorRef?.takeIf { s.error != null }?.let {
            BasicText("Ref $it", Modifier.fillMaxWidth(), style = t.caption1.copy(color = c.inkTertiary, textAlign = TextAlign.Center))
        }
        if (s.step == BackupStep.PasskeyNotHere) {
            // Not red here: the confirm that follows is the dangerous step, and says so.
            CircasoButton(KeyBackupCopy.NewBackup, actions.onStartNew, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, enabled = s.online && !s.busy)
            CircasoButton(BackupPromptCopy.NotNow, actions.onClose, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true, enabled = !s.busy)
        }
        if (s.step == BackupStep.Ask && s.askNotHere && s.sheet == BackupSheet.Restore) {
            CircasoButton(KeyBackupCopy.AskPasskeyMissing, actions.onNotHere, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = !s.busy)
        }
    }
}

/**
 * Chats' quiet card while a backup waits to be restored and its sheet was put away (a cancel, "Not Now", or the
 * passkey wasn't here). Restore runs the restore at once in the sheet, so its outcomes show where they always do.
 */
@Composable
fun BackupRestoreCard(viewModel: BackupPromptViewModel, unlock: String, floating: Boolean = false) {
    val s by viewModel.state.collectAsState()
    BackupRestoreCardContent(s, unlock, onRestore = viewModel::onResume, onNotNow = viewModel::onCardDismissed, floating = floating)
}

@Composable
fun BackupRestoreCardContent(s: BackupPromptUi, unlock: String, onRestore: () -> Unit, onNotNow: () -> Unit, floating: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val reduce = CircasoTheme.reduceMotion
    AnimatedVisibility(
        s.resumeRestore,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(CircasoMotion.BaseMs)) + expandVertically(tween(CircasoMotion.BaseMs)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(CircasoMotion.ExitMs)) + shrinkVertically(tween(CircasoMotion.QuickMs)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2)
                .glass(CircasoShapes.lg, floating = floating).padding(CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.Top) {
                Box(Modifier.size(CircasoSizes.noticeBadge).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                    BasicText(BackupPromptCopy.CardTitle, Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
                    BasicText(
                        if (s.unreadableGroups > 0) BackupPromptCopy.groupsMissing(s.unreadableGroups) else BackupPromptCopy.CardMessage,
                        style = t.footnote.copy(color = c.inkSecondary),
                    )
                }
            }
            // Large text: the buttons stack, Restore first, so "Restore With Fingerprint" is never cut off.
            if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) {
                Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    CircasoButton("Restore With $unlock", onRestore, style = ButtonStyle.Secondary, size = ButtonSize.Medium, block = true, enabled = s.online)
                    CircasoButton(BackupPromptCopy.NotNow, onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true)
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                // "Not Now" takes only what it needs, so "Restore With Fingerprint" is never cut off.
                CircasoButton(BackupPromptCopy.NotNow, onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Medium)
                CircasoButton("Restore With $unlock", onRestore, Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Medium, enabled = s.online)
            }
            if (!s.online) BasicText("Restoring needs a connection.", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkTertiary))
        }
    }
}
