package com.circaso.designsystem.chrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme

enum class BannerTone { Neutral, Warning, Critical }

/** Inline glass card (design `cx-banner`): icon and one line of text, announced politely to screen readers. */
@Composable
fun Banner(text: String, icon: CircasoIcon, modifier: Modifier = Modifier, tone: BannerTone = BannerTone.Neutral) {
    val c = CircasoTheme.colors
    val iconTint = when (tone) {
        BannerTone.Neutral -> c.inkSecondary
        BannerTone.Warning -> c.warning
        BannerTone.Critical -> c.critical
    }
    Row(
        modifier
            .fillMaxWidth()
            .glass(CircasoShapes.md)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconImage(icon, iconTint, Modifier.size(18.dp))
        BasicText(text, Modifier.weight(1f), style = CircasoTheme.typography.subhead.copy(color = c.ink))
    }
}
