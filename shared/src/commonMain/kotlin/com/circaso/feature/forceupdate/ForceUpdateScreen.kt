package com.circaso.feature.forceupdate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.circaso.core.platform.StoreLauncher
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import org.koin.compose.koinInject

/** Design ID-30. The only screen available once the server rejects this version. */
@Composable
fun ForceUpdateScreen(launcher: StoreLauncher = koinInject()) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(CircasoSpacing.s5),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText("Update Circaso to continue", style = t.title1.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            "This version is no longer supported. The update keeps your money and chats safe.",
            style = t.body.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        CircasoButton("Update", onClick = launcher::openStorePage, size = ButtonSize.Large, block = true)
    }
}
