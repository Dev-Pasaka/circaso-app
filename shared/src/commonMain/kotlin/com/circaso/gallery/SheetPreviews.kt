package com.circaso.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.feature.account.domain.ActiveDevice
import com.circaso.feature.account.domain.LinkedProvider
import com.circaso.feature.account.domain.SecurityOverview
import com.circaso.feature.account.presentation.SecurityConfirm
import com.circaso.feature.account.presentation.SecurityContent
import com.circaso.feature.account.presentation.SecurityUiState
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.contacts.presentation.ContactAction
import com.circaso.feature.contacts.presentation.ContactsActions
import com.circaso.feature.contacts.presentation.ContactsContent
import com.circaso.feature.contacts.presentation.ContactsUiState
import com.circaso.feature.home.presentation.YouActions
import com.circaso.feature.home.presentation.YouContent
import com.circaso.feature.home.presentation.YouUiState
import com.circaso.feature.wallet.presentation.ComingFlow
import com.circaso.feature.wallet.presentation.HoldSheet
import com.circaso.feature.wallet.presentation.WalletContent
import com.circaso.feature.wallet.presentation.WalletSheets
import com.circaso.feature.wallet.presentation.WalletUiState

/**
 * The message and confirm sheets (`-preview sheet-…`, 2026-11-13 polish pass), each open over the screen that shows it:
 * sign out, the account and security confirms, the wallet's coming and hold sheets, and the contacts confirms.
 */
@Composable
internal fun SheetsPreview(name: String): Boolean {
    if (!name.startsWith("sheet-")) return false
    val walletBehind: @Composable () -> Unit = {
        WalletContent(WalletUiState(wallet = sampleWallet()), PaddingValues(bottom = 90.dp), noWalletActions, {}, {}, {}, {})
    }
    when (name) {
        "sheet-signout" -> YouContent(
            YouUiState(profile = previewProfile, maskedPhone = "+254 7•• ••• 678", confirmSignOut = true),
            YouActions({}, {}, {}, {}, {}), "1.0.0", PaddingValues(bottom = 90.dp), {}, {}, {},
        )
        "sheet-remove-phone", "sheet-delete-account", "sheet-delete-blocked", "sheet-unlink" -> SecurityContent(
            SecurityUiState(
                overview = SecurityOverview(
                    "+254 7•• ••• 678",
                    listOf(LinkedProvider("google", "a•••@gmail.com", "2026-10-01T00:00:00Z")),
                    ActiveDevice("Pixel 8", "2026-10-12T00:00:00Z"), "VERIFIED",
                ),
                confirm = when (name) {
                    "sheet-remove-phone" -> SecurityConfirm.RemovePhone
                    "sheet-delete-account" -> SecurityConfirm.DeleteAccount
                    "sheet-unlink" -> SecurityConfirm.Unlink(SocialProvider.Google)
                    else -> null
                },
                deleteText = if (name == "sheet-delete-account") "DEL" else "",
                deleteBlocked = name == "sheet-delete-blocked", walletBalance = "KES 3,420",
            ),
            {}, {}, {}, {}, {}, {}, {},
        )
        "sheet-cash-out", "sheet-add-money" -> CircasoScaffold(
            overlay = {
                val coming = if (name == "sheet-cash-out") ComingFlow.CashOut else ComingFlow.AddMoney
                WalletSheets(WalletUiState(wallet = sampleWallet(), coming = coming), noWalletActions, {}, {})
            },
        ) { _ -> walletBehind() }
        "sheet-hold" -> CircasoScaffold(overlay = { HoldSheet(true, 41, {}, {}) }) { _ -> walletBehind() }
        "sheet-leave" -> CircasoScaffold(
            overlay = {
                ConfirmSheet(
                    true, "Leave Circaso?", "Messages and money keep arriving while you're away; you'll get a notification.", "Leave", {}, {},
                    cancelLabel = "Stay", icon = com.circaso.designsystem.components.CircasoIcon.Bell,
                )
            },
        ) { _ -> walletBehind() }
        "sheet-contact-remove", "sheet-contact-block" -> ContactsContent(
            ContactsUiState(
                contacts = peopleContacts, loaded = true,
                pendingAction = if (name == "sheet-contact-remove") ContactAction.Remove(peopleContacts[0]) else ContactAction.Block(peopleContacts[0]),
            ),
            ContactsActions(),
        )
        "sheet-add-username" -> ContactsContent(ContactsUiState(contacts = peopleContacts, loaded = true, adding = true, connectInput = "kevo", online = false), ContactsActions())
        else -> return false
    }
    return true
}
