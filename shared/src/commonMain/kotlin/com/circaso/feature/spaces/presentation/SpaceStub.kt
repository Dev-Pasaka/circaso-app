package com.circaso.feature.spaces.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.theme.CircasoTheme

/** Scaffolding while an area is being built; every use goes before phase 3 is called done. */
@Composable
internal fun SpaceStub(title: String, onBack: () -> Unit) {
    CircasoScaffold(topBar = { CircasoNavBar(title, onBack) }) { _ ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText(title, style = CircasoTheme.typography.body.copy(color = CircasoTheme.colors.inkSecondary))
        }
    }
}
