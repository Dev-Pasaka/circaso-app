package com.circaso.core.platform

import androidx.compose.runtime.Composable

/**
 * TalkBack or VoiceOver is on (read when first composed). Something that would close by itself (a short "done")
 * stays until it is closed, so the reader can finish it.
 */
@Composable
expect fun rememberScreenReaderOn(): Boolean
