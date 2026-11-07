package com.circaso.core.platform

/**
 * Plays a video file full size, with the platform's own controls (ExoPlayer on Android, AVKit on iOS). The chat's old
 * gallery that lived here (its own picker and a 720p re-encode) is gone: chats use the shared media composer
 * (MediaCompose) and the moment encoding (MomentVideoEncoding) since 2026-11-07.
 */
@androidx.compose.runtime.Composable
expect fun VideoPlayer(path: String, modifier: androidx.compose.ui.Modifier)
