package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow

/** A one-shot for a Rive view model: set [enumProperty] to [value], then fire [trigger]. */
class RiveOneShot(val enumProperty: String, val value: String, val trigger: String)

/**
 * A Rive file drawn by the platform runtime (Android `app.rive:rive-android`, iOS `RiveRuntime` through
 * `RiveBridge`), bound to its view model [viewModel] with data binding (not state machine inputs). Only strings and
 * numbers cross, so this knows nothing about Asha; `AshaAvatar` maps her state onto it.
 *
 * [playing] false holds the current frame (reduced motion), while property changes still apply. Drawing also stops on
 * its own while the app is in the background or the view is off screen. [onFailed] is called once if the file, its
 * artboard or its view model can't be loaded, or the runtime is missing; the caller then shows its fallback.
 */
@Composable
expect fun RiveAnimation(
    file: ByteArray,
    artboard: String,
    stateMachine: String,
    viewModel: String,
    enums: Map<String, String>,
    numbers: Map<String, Float>,
    oneShots: Flow<RiveOneShot>,
    playing: Boolean,
    /** The colour behind it. iOS draws the view under Compose, so its empty corners are filled with this; Android stays transparent. */
    background: Color,
    /** A frame-rate cap (a small header icon needs no more than 30); 0 follows the display. */
    maxFps: Int,
    /** Drawn as a disc (an avatar). Android clips with the modifier; iOS rounds its own view, which Compose can't clip. */
    round: Boolean,
    /** The disc's colour behind the drawing when [round]. */
    roundFill: Color,
    /** Shown while the runtime and file get ready, so the slot is never blank. */
    placeholder: @Composable () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
)
