package com.circaso.designsystem.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.circaso.designsystem.theme.CircasoTheme

enum class AmountSize { Hero, Large, Medium, Small }

/** Money text in the amount styles (tabular figures). Pass the already formatted string. */
@Composable
fun AmountText(text: String, modifier: Modifier = Modifier, size: AmountSize = AmountSize.Medium, color: Color = CircasoTheme.colors.money) {
    val t = CircasoTheme.typography
    val style: TextStyle = when (size) {
        AmountSize.Hero -> t.amountHero
        AmountSize.Large -> t.amountLarge
        AmountSize.Medium -> t.amountMedium
        AmountSize.Small -> t.amountSmall
    }
    BasicText(text, modifier, style = style.copy(color = color))
}
