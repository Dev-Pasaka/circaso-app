package com.circaso.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Small grey label above a group of rows (design `cap`). */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier.padding(start = CircasoSpacing.s4, bottom = CircasoSpacing.s2).semantics { heading() },
        style = CircasoTheme.typography.footnote.copy(color = CircasoTheme.colors.inkSecondary),
    )
}

/** A titled group of rows on the grouped background. */
@Composable
fun Section(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        if (title != null) SectionHeader(title)
        ListGroup { content() }
    }
}

/** The arrow at the end of a row that opens another screen. */
@Composable
fun RowChevron() {
    CircasoIconImage(CircasoIcon.ChevronRight, CircasoTheme.colors.inkTertiary, Modifier.size(16.dp))
}

/** Text at the end of a row (a value such as "KES" or "English"). */
@Composable
fun RowValue(text: String) {
    BasicText(text, style = CircasoTheme.typography.body.copy(color = CircasoTheme.colors.inkSecondary))
}

/** A row's trailing value and chevron together. */
@Composable
fun RowValueChevron(value: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (value != null) RowValue(value)
        RowChevron()
    }
}
