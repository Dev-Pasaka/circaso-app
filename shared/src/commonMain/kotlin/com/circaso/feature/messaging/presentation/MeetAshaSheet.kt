package com.circaso.feature.messaging.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.components.AshaMark
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.MoneyCard
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Design A10: the first time the Chats tab opens, Asha introduces the two tricks worth knowing. */
@Composable
fun BoxScope.MeetAshaSheet(visible: Boolean, firstName: String?, onTry: () -> Unit, onVoice: () -> Unit, onDismiss: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    CircasoSheet(visible, onDismiss, label = "Meet Asha") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AshaMark(size = CircasoSizes.avatarMd)
            BasicText("Asha", style = t.callout.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
            CircasoIconButton(CircasoIcon.Close, "Close", onDismiss)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(if (firstName != null) "Habari, $firstName. I'm Asha." else "Habari. I'm Asha.", style = t.title2.copy(color = c.ink))
            BasicText("Two tricks worth knowing.", style = t.body.copy(color = c.inkSecondary))
        }
        Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.bgGrouped).padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicText("1 · Send money as a message", style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
            MoneyCard(head = "Sent", amount = "KES 500", note = "for lunch", foot = "To Mum · 3.88 USDC", outgoing = true)
            BasicText("Type #500 in any chat and the money goes with your note.", style = t.footnote.copy(color = c.inkSecondary))
            BasicText("2 · Ask by voice", style = t.footnote.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold))
            BasicText("Tap the waveform in my chat and just talk. I read your activity; I never move money.", style = t.footnote.copy(color = c.inkSecondary))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircasoButton("Try It", { onDismiss(); onTry() }, size = ButtonSize.Large, modifier = Modifier.weight(1f))
            CircasoButton("Skip", onDismiss, style = ButtonStyle.Plain, size = ButtonSize.Large)
            CircasoIconButton(CircasoIcon.Waveform, "Ask by voice", { onDismiss(); onVoice() })
        }
    }
}
