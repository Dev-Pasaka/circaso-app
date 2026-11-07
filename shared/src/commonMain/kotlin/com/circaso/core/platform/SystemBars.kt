package com.circaso.core.platform

import androidx.compose.runtime.Composable

/**
 * While this is on screen, the status bar's icons are [light] (for a screen that is dark in every theme: the media
 * composer's stage, the album viewer) or dark (a light screen over such a stage: the composer's gallery in a light
 * theme). Dark icons on a dark stage would vanish. What was there before comes back when it leaves.
 */
@Composable
expect fun StatusBarIcons(light: Boolean)
