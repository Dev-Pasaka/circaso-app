package com.circaso.core.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import app.rive.RiveFrameRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.rive.runtime.kotlin.core.Rive as RiveRuntime
import androidx.compose.ui.graphics.Color
import app.rive.Fit
import app.rive.Result
import app.rive.Rive
import app.rive.RiveFileSource
import app.rive.RivePointerInputMode
import app.rive.ViewModelSource
import app.rive.rememberArtboardResult
import app.rive.rememberRiveFile
import app.rive.rememberRiveWorkerOrNull
import app.rive.rememberStateMachineResult
import app.rive.rememberViewModelInstanceResult
import com.circaso.core.common.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Android: Rive's Compose runtime (`app.rive.Rive`). It renders on its own worker thread with the GPU and only draws
 * while the screen's lifecycle is RESUMED, so a backgrounded app or a screen left behind costs nothing.
 */
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
    val context = LocalContext.current
    // The native library loads off the main thread, once, the first time a mascot is drawn.
    val natives by produceState(RiveNatives.state, Unit) { if (value == null) value = withContext(Dispatchers.Default) { RiveNatives.load(context) } }
    when (natives) {
        null -> { Holder(modifier, placeholder); return }
        false -> { Holder(modifier, placeholder); LaunchedEffect(Unit) { fail() }; return }
        true -> Unit
    }
    val worker = rememberRiveWorkerOrNull()
    if (worker == null) {
        Holder(modifier, placeholder)
        LaunchedEffect(Unit) { Diagnostics.note("rive: no worker"); fail() }
        return
    }
    val source = remember(file) { RiveFileSource.Bytes(file) }
    val riveFile = when (val r = rememberRiveFile(source, worker)) {
        is Result.Success -> r.value
        is Result.Error -> { Holder(modifier, placeholder); Failed(r.throwable, "file") { fail() }; return }
        Result.Loading -> { Holder(modifier, placeholder); return }
    }
    val board = when (val r = rememberArtboardResult(riveFile, artboard)) {
        is Result.Success -> r.value
        is Result.Error -> { Holder(modifier, placeholder); Failed(r.throwable, "artboard") { fail() }; return }
        Result.Loading -> { Holder(modifier, placeholder); return }
    }
    val machine = when (val r = rememberStateMachineResult(board, stateMachine)) {
        is Result.Success -> r.value
        is Result.Error -> { Holder(modifier, placeholder); Failed(r.throwable, "state machine") { fail() }; return }
        Result.Loading -> { Holder(modifier, placeholder); return }
    }
    val vmSource = remember(viewModel) { ViewModelSource.Named(viewModel).defaultInstance() }
    val instance = when (val r = rememberViewModelInstanceResult(riveFile, vmSource)) {
        is Result.Success -> r.value
        is Result.Error -> { Holder(modifier, placeholder); Failed(r.throwable, "view model") { fail() }; return }
        Result.Loading -> { Holder(modifier, placeholder); return }
    }

    val enumsNow by rememberUpdatedState(enums)
    val numbersNow by rememberUpdatedState(numbers)
    LaunchedEffect(instance) {
        // Only what changed is sent: re-setting an enum to its own value is harmless, but the mouth level changes
        // many times a second and the rest rarely does.
        val sent = HashMap<String, String>()
        snapshotFlow { enumsNow }.collect { map ->
            map.forEach { (k, v) -> if (sent[k] != v) { safely { instance.setEnum(k, v) }; sent[k] = v } }
        }
    }
    LaunchedEffect(instance) {
        val sent = HashMap<String, Float>()
        snapshotFlow { numbersNow }.collect { map ->
            map.forEach { (k, v) -> if (sent[k] != v) { safely { instance.setNumber(k, v) }; sent[k] = v } }
        }
    }
    LaunchedEffect(instance, oneShots) {
        oneShots.collect { shot -> safely { instance.setEnum(shot.enumProperty, shot.value); instance.fireTrigger(shot.trigger) } }
    }

    Rive(
        file = riveFile,
        modifier = modifier,
        playing = playing,
        artboard = board,
        stateMachine = machine,
        viewModelInstance = instance,
        fit = Fit.Contain(),
        // Taps reach the composable around it (a poke), Rive only watches them.
        pointerInputMode = RivePointerInputMode.Observe,
        frameRate = if (maxFps > 0) RiveFrameRate.Capped(maxFps.toFloat()) else RiveFrameRate.Unbounded,
    )
}

/**
 * Rive's library ships its startup initializer switched off, so its native code is loaded here, once, the first time
 * a mascot is drawn (not at app start: most launches never open Asha).
 */
private object RiveNatives {
    @Volatile var state: Boolean? = null
        private set

    @Synchronized fun load(context: Context): Boolean = state ?: try {
        RiveRuntime.init(context.applicationContext)
        true
    } catch (e: Throwable) {
        Diagnostics.note("rive: native load failed (${e::class.simpleName})")
        false
    }.also { state = it }
}

/** What shows while Rive gets ready (or for the frame before a failure is reported): the caller's fallback, never blank. */
@Composable
private fun Holder(modifier: Modifier, placeholder: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { placeholder() }
}

@Composable
private fun Failed(e: Throwable, what: String, onFailed: () -> Unit) {
    LaunchedEffect(Unit) { Diagnostics.note("rive: $what failed (${e::class.simpleName})"); onFailed() }
}

/** A property the file doesn't have, or a closed instance during teardown, must never crash the screen. */
private inline fun safely(block: () -> Unit) {
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Diagnostics.note("rive: property skipped (${e::class.simpleName})") }
}
