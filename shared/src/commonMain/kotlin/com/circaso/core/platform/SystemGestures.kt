package com.circaso.core.platform

import androidx.compose.ui.Modifier

/**
 * Keeps the phone's own edge swipes (Android's back gesture) off this element, so a drag that starts near the screen
 * edge reaches it: the moments trimmer's handles (Pasaka, 2026-11-07). iOS has no such API; there the element must
 * stay clear of the left edge on its own.
 */
expect fun Modifier.excludeSystemGestures(): Modifier
