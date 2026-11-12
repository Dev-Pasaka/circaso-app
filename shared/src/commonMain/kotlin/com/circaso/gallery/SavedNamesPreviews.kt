package com.circaso.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.presentation.EditNameRow
import com.circaso.feature.messaging.presentation.PeerNameLines
import com.circaso.feature.contacts.presentation.ContactsActions
import com.circaso.feature.contacts.presentation.ContactsContent
import com.circaso.feature.contacts.presentation.ContactsUiState
import com.circaso.feature.names.presentation.EditName

/** [peopleContacts] with one I renamed: Wanjiru Kamau saved as "Shiru (cousin)". */
private val namedContacts get() = peopleContacts.map { c ->
    if (c.person.userId == "8") c.copy(person = c.person.copy(displayName = "Shiru (cousin)", profileName = "Wanjiru Kamau")) else c
}.sortedBy { it.person.displayName.lowercase() }

/**
 * Saved names (2026-11-12), `-preview=`: a contact I renamed with their own name under mine ("~ Wanjiru Kamau"), and
 * the Edit name sheet, fresh (prefilled with their name) and over a saved name (with "Use their name").
 */
@Composable
internal fun SavedNamesPreview(name: String): Boolean {
    when (name) {
        "contact-saved-name" -> ContactsContent(ContactsUiState(contacts = namedContacts, selected = namedContacts.first { it.person.userId == "8" }, loaded = true), ContactsActions())
        "edit-name" -> ContactsContent(
            ContactsUiState(contacts = namedContacts, loaded = true), ContactsActions(),
            editing = EditName("4", "Juma Kariuki", "Juma Kariuki", hasSaved = false, draft = "Juma (football)"),
        )
        "edit-name-saved" -> ContactsContent(
            ContactsUiState(contacts = namedContacts, loaded = true), ContactsActions(),
            editing = EditName("8", "Wanjiru Kamau", "Shiru (cousin)", hasSaved = true),
        )
        "chat-info-saved-name" -> ContactInfoHeaderPreview()
        "avatars-emoji" -> EmojiAvatarsPreview()
        else -> return false
    }
    return true
}

/** Contact Info's header for someone I renamed, with the screen's own name lines and Edit name row. */
@Composable
private fun ContactInfoHeaderPreview() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val peer = ChatMember("8", "Shiru (cousin)", "wanjiru", null, MemberRole.Member, "", profileName = "Wanjiru Kamau")
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Contact Info", onBack = {}) }) { _ ->
        Column {
            Column(Modifier.fillMaxWidth().padding(vertical = CircasoSpacing.s6), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Avatar(peer.displayName, size = CircasoSizes.avatarXl)
                BasicText(peer.displayName, style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
                PeerNameLines(peer)
            }
            EditNameRow {}
        }
    }
}

/** Avatars for names with emoji (2026-11-12), at every size: letters skip the emoji; emoji-only names show it whole. */
@Composable
private fun EmojiAvatarsPreview() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val names = listOf("🌸 Wanjiru Kamau", "Kevin 🔥", "🔥🔥", "👩🏽‍💻", "🇰🇪", "Shiru (cousin)", "𝓙𝓪𝓷𝓮")
    val sizes = listOf(CircasoSizes.avatarSm, CircasoSizes.avatarMd, CircasoSizes.avatarLg, CircasoSizes.avatarXl)
    CircasoScaffold(topBar = { CircasoNavBar(title = "Avatars", onBack = {}) }) { _ ->
        Column(Modifier.padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            names.forEach { n ->
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                    sizes.forEach { Avatar(n, size = it) }
                    BasicText(n, style = t.footnote.copy(color = c.inkSecondary))
                }
            }
        }
    }
}
