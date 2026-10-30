package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Row of [length] dots; [filled] of them are set. [error] colours them critical. Announces progress, never the digits. */
@Composable
fun PinDots(filled: Int, modifier: Modifier = Modifier, length: Int = 6, error: Boolean = false) {
    val c = CircasoTheme.colors
    Row(
        modifier.semantics { contentDescription = "$filled of $length digits entered" },
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
    ) {
        repeat(length) { i ->
            val on = i < filled
            Box(
                Modifier.size(14.dp).clip(CircasoShapes.circle)
                    .background(if (error) c.critical else if (on) c.ink else c.fillStrong),
            )
        }
    }
}

/** 3x4 numeric keypad. [bottomStart] is the free slot (biometric button); empty by default. */
@Composable
fun PinKeypad(
    onDigit: (Int) -> Unit,
    onBackspace: () -> Unit,
    modifier: Modifier = Modifier,
    bottomStart: (@Composable () -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s6)) {
                row.forEach { d -> PinKey(onClick = { onDigit(d) }) { KeyLabel(d.toString()) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s6)) {
            Box(Modifier.size(KeySize), contentAlignment = Alignment.Center) { bottomStart?.invoke() }
            PinKey(onClick = { onDigit(0) }) { KeyLabel("0") }
            PinKey(onClick = onBackspace, description = "Delete", filled = false) {
                CircasoIconImage(CircasoIcon.Backspace, CircasoTheme.colors.ink, Modifier.size(26.dp))
            }
        }
    }
}

private val KeySize = 76.dp

@Composable
private fun PinKey(onClick: () -> Unit, description: String? = null, filled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(KeySize).clip(CircasoShapes.circle)
            .background(if (filled) CircasoTheme.colors.fill else androidx.compose.ui.graphics.Color.Transparent)
            .pressable(onClick = onClick)
            .semantics { if (description != null) contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun KeyLabel(text: String) =
    BasicText(text, style = CircasoTheme.typography.title1.copy(color = CircasoTheme.colors.ink))
