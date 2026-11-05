@file:OptIn(ExperimentalForeignApi::class)

package com.circaso.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.circaso.core.common.Diagnostics
import com.circaso.core.crypto.toNSData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.Foundation.NSData
import platform.UIKit.UIColor
import platform.UIKit.UIView

/**
 * RiveRuntime (Swift Package `rive-app/rive-ios`, pinned in iosApp.xcodeproj) lives on the Swift side
 * (`RiveImpl.swift`), installed at launch like the other bridges. Only strings and numbers cross.
 */
interface PlatformRiveProvider {
    /** A view of [data]'s [artboard] running [stateMachine], bound to [viewModel]'s default instance; null if it can't load. */
    fun make(data: NSData, artboard: String, stateMachine: String, viewModel: String, maxFps: Int, round: Boolean): PlatformRiveHandle?
}

/** One live Rive view. It stops drawing by itself while off screen or while the app is in the background. */
interface PlatformRiveHandle {
    fun view(): UIView
    /** The disc's colour behind the drawing (with `round`); clear otherwise. */
    fun setDiscFill(color: UIColor)
    fun setEnum(name: String, value: String)
    fun setNumber(name: String, value: Float)
    fun fire(enumProperty: String, value: String, trigger: String)
    /** False holds the current frame (reduced motion); property changes still apply. */
    fun setPlaying(playing: Boolean)
    fun dispose()
}

object RiveBridge {
    var provider: PlatformRiveProvider? = null
}

@Composable
actual fun RiveAnimation(
    file: ByteArray,
    artboard: String,
    stateMachine: String,
    viewModel: String,
    enums: Map<String, String>,
    numbers: Map<String, Float>,
    oneShots: Flow<RiveOneShot>,
    playing: Boolean,
    background: Color,
    maxFps: Int,
    round: Boolean,
    roundFill: Color,
    placeholder: @Composable () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val fail by rememberUpdatedState(onFailed)
    val provider = RiveBridge.provider
    val handle = remember(provider, file, artboard, stateMachine, viewModel, maxFps, round) { provider?.make(file.toNSData(), artboard, stateMachine, viewModel, maxFps, round) }
    if (handle == null) {
        androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) { placeholder() }
        LaunchedEffect(Unit) { Diagnostics.note("rive: unavailable"); fail() }
        return
    }
    DisposableEffect(handle) { onDispose { handle.dispose() } }
    val sentEnums = remember(handle) { HashMap<String, String>() }
    val sentNumbers = remember(handle) { HashMap<String, Float>() }
    SideEffect {
        enums.forEach { (k, v) -> if (sentEnums[k] != v) { handle.setEnum(k, v); sentEnums[k] = v } }
        numbers.forEach { (k, v) -> if (sentNumbers[k] != v) { handle.setNumber(k, v); sentNumbers[k] = v } }
        handle.setPlaying(playing)
    }
    LaunchedEffect(handle, oneShots) { oneShots.collect { handle.fire(it.enumProperty, it.value, it.trigger) } }
    // The view sits under Compose (so sheets and scrims still cover it) and Compose leaves a clear hole over it, so the
    // view itself is filled with the colour around it. Touches stay with Compose (the poke).
    val fill = background.toUIColor()
    val disc = if (round) roundFill.toUIColor() else UIColor.clearColor
    UIKitView(
        factory = { handle.view().also { it.backgroundColor = fill; handle.setDiscFill(disc) } },
        modifier = modifier,
        update = { it.backgroundColor = fill; handle.setDiscFill(disc) },
        properties = UIKitInteropProperties(interactionMode = null),
    )
}

private fun Color.toUIColor(): UIColor = UIColor.colorWithRed(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())
