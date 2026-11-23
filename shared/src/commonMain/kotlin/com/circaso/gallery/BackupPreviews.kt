package com.circaso.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circaso.feature.keybackup.presentation.BackupPromptActions
import com.circaso.feature.keybackup.presentation.BackupPromptSheet
import com.circaso.feature.keybackup.presentation.BackupPromptUi
import com.circaso.feature.keybackup.presentation.BackupRestoreCardContent
import com.circaso.feature.keybackup.presentation.BackupSheet
import com.circaso.feature.keybackup.presentation.BackupStep
import com.circaso.feature.messaging.presentation.ChatsContent
import com.circaso.feature.messaging.presentation.ChatsUiState

/**
 * The chat backup from the word go (2026-11-23), `-preview=backup-…`: every state of the one sheet over Chats (set up,
 * restore, the passkey not here, the new-backup confirm, repair, done, offline, busy, an error) and Chats' card.
 */
@Composable
internal fun BackupPreview(name: String): Boolean {
    if (!name.startsWith("backup-")) return false
    val setUp = BackupPromptUi(sheet = BackupSheet.SetUp, visible = true)
    val restore = BackupPromptUi(sheet = BackupSheet.Restore, visible = true)
    val repair = BackupPromptUi(sheet = BackupSheet.Repair, visible = true)
    val card = BackupPromptUi(resumeRestore = true)
    val state = when (name) {
        "backup-setup" -> setUp
        "backup-setup-busy" -> setUp.copy(busy = true)
        "backup-setup-offline" -> setUp.copy(online = false)
        "backup-setup-error" -> setUp.copy(error = "This phone doesn't have your Circaso passkey. It may be on another phone, or in another Google or Apple account. Try again and choose another device, or sign in to that account on this phone.")
        "backup-setup-done" -> setUp.copy(step = BackupStep.Done)
        "backup-setup-turned-off" -> setUp.copy(notice = com.circaso.feature.keybackup.presentation.BackupPromptCopy.TurnedOffElsewhere)
        "backup-not-here-again" -> restore.copy(step = BackupStep.PasskeyNotHere, error = com.circaso.feature.keybackup.presentation.BackupPromptCopy.NoOtherDevice)
        "backup-restore" -> restore
        "backup-restore-groups" -> restore.copy(unreadableGroups = 3)
        "backup-restore-busy" -> restore.copy(busy = true)
        "backup-restore-cancelled" -> restore.copy(askNotHere = true)
        "backup-restore-offline" -> restore.copy(online = false)
        "backup-restore-error" -> restore.copy(error = "Something went wrong on our side. Try again in a moment.", errorRef = "req_8Kd2")
        "backup-restored" -> restore.copy(step = BackupStep.Done)
        "backup-restored-left" -> restore.copy(step = BackupStep.Done, leftAfterRestore = 2)
        "backup-not-here" -> restore.copy(step = BackupStep.PasskeyNotHere)
        "backup-not-here-busy" -> restore.copy(step = BackupStep.PasskeyNotHere, busy = true)
        "backup-not-here-offline" -> restore.copy(step = BackupStep.PasskeyNotHere, online = false)
        "backup-confirm-new" -> restore.copy(step = BackupStep.ConfirmNew)
        "backup-confirm-new-busy" -> restore.copy(step = BackupStep.ConfirmNew, busy = true)
        "backup-new-done" -> restore.copy(step = BackupStep.Done, startedNew = true)
        "backup-repair" -> repair
        "backup-repair-offline" -> repair.copy(online = false)
        "backup-repaired" -> repair.copy(step = BackupStep.Done)
        "backup-card" -> card
        "backup-card-groups" -> card.copy(unreadableGroups = 1)
        "backup-card-offline" -> card.copy(online = false)
        else -> return false
    }
    Box(Modifier.fillMaxSize()) {
        ChatsContent(ChatsUiState(chats = chatList, loaded = true, online = state.online), PaddingValues(0.dp), {}, {}, {})
        if (name.startsWith("backup-card")) {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { BackupRestoreCardContent(state, "Face ID", {}, {}, floating = true) }
        } else {
            BackupPromptSheet(state, "Face ID", BackupPromptActions())
        }
    }
    return true
}
