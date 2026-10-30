package com.circaso.designsystem.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme

/** `.cx-goal`: "KES 32,400 of 50,000 · 65%", an 8 dp track filled in the money colour, a meta line under it. */
@Composable
fun GoalProgress(raised: String, of: String, progress: Float, modifier: Modifier = Modifier, meta: String? = null, label: String = "Goal progress") {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(if (CircasoTheme.reduceMotion) 0 else CircasoMotion.BaseMs), label = "goal")
    val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(com.circaso.designsystem.theme.CircasoSpacing.s2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            BasicText(raised, style = t.amountSmall.copy(color = c.ink))
            BasicText("of $of", style = t.subhead.copy(color = c.inkSecondary), modifier = Modifier.weight(1f))
            BasicText("$percent%", style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
        }
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(CircasoShapes.pill).background(c.fillStrong)
                .semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(percent.toFloat(), 0f..100f) },
        ) {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircasoShapes.pill).background(c.money))
        }
        if (meta != null) BasicText(meta, style = t.footnote.copy(color = c.inkSecondary))
    }
}
