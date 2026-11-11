package com.circaso.feature.vault.presentation

import androidx.compose.runtime.Composable

/** While composed, the screen can't be captured (screenshots, recordings, the app switcher's snapshot) where the platform allows. */
@Composable
expect fun NoScreenCapture()
