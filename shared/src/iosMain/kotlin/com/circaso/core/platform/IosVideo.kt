@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.play
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

/** AVKit's player, with its own controls. */
@Composable
actual fun VideoPlayer(path: String, modifier: Modifier) {
    UIKitViewController(
        factory = {
            AVPlayerViewController().apply {
                player = AVPlayer(uRL = NSURL.fileURLWithPath(path))
                player?.play()
            }
        },
        modifier = modifier,
    )
}
