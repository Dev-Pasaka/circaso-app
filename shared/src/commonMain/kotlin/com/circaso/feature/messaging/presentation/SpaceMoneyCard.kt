package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.wallet.presentation.PayUiState

/**
 * SH2e: paying a space you're not in. The same card as the chat's money card, with the amount and the note, but no
 * quote and no fee: the reason, and the way in (Follow to Pay for a public space, Join to Pay for a members-only one)
 * in place of Pay. Spaces phase 3; kept apart from [ChatMoneyCard] so the 2F fee work there isn't touched.
 */
@Composable
internal fun SpacePayBlockedCard(s: PayUiState, reason: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val space = s.payee?.displayName ?: "the space"
    val amount = s.fiatText ?: s.usdcText
    Column(
        modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s3).padding(bottom = CircasoSpacing.s2)
            .shadow(12.dp, CircasoShapes.lg, ambientColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f), spotColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
            .clip(CircasoShapes.lg).background(c.surfaceRaised)
            .then(if (c.isHighContrast) Modifier.border(1.dp, c.separator, CircasoShapes.lg) else Modifier)
            .padding(start = CircasoSpacing.s4, end = CircasoSpacing.s3, top = CircasoSpacing.s3, bottom = CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            SpaceTile(space, s.payee?.avatarUrl, CircasoSizes.avatarMd)
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading(); contentDescription = listOfNotNull("Pay $space", amount, s.usdcText.takeIf { s.fiatText != null }).joinToString(", ") }) {
                BasicText("Pay $space", style = t.subhead.copy(color = c.inkSecondary), maxLines = 1)
                BasicText(amount ?: "Type an amount after #", style = (if (amount != null) t.title2 else t.callout).copy(color = if (amount != null) c.ink else c.inkSecondary, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"))
                s.usdcText?.takeIf { s.fiatText != null }?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary, fontFeatureSettings = "tnum")) }
            }
        }
        s.note.trim().takeIf { it.isNotEmpty() }?.let { note ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                BasicText("For", style = t.footnote.copy(color = c.inkSecondary))
                BasicText(note, Modifier.weight(1f), style = t.footnote.copy(color = c.ink, textAlign = TextAlign.End))
            }
        }
        BasicText(reason, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.inkSecondary))
        CircasoButton(action ?: "Open the Space", onAction, size = ButtonSize.Large, block = true)
    }
}
