package com.circaso.feature.vault.presentation

import androidx.compose.runtime.Composable

/**
 * iOS has no switch to block screenshots; the vault's PIN hides itself after a minute and on leaving the screen instead.
 * (A follow-up can draw it inside a secure text field's layer, which iOS leaves out of captures.)
 */
@Composable
actual fun NoScreenCapture() = Unit
