package com.circaso.feature.assistant.presentation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.circaso.designsystem.theme.CircasoShapes
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import circaso.shared.generated.resources.Res
import com.circaso.core.common.Diagnostics
import com.circaso.core.platform.RiveAnimation
import com.circaso.core.platform.RiveOneShot
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.assistant.domain.AshaAvatarState
import com.circaso.feature.assistant.domain.AshaReaction
import com.circaso.feature.assistant.domain.AshaScene
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * How `asha.riv` is framed. Both use the one `Asha` artboard and state machine: the Rive exporter drops an artboard that
 * only nests another, so the head crop is a `framing` state inside the stage instead of its own artboard.
 */
enum class AshaAvatarVariant(val artboard: String, val stateMachine: String, val framing: String, val maxFps: Int) {
    /**
     * Head-and-face crop (`framing` = icon, no scene) for headers and rows. Capped at 30 fps: a 28 dp avatar gains
     * nothing from more, and the chat can stay open for minutes.
     */
    Icon("Asha", "Asha", "icon", 30),
    /** 480×460 full stage (`framing` = stage) with scenes, props and the talking mouth. */
    Stage("Asha", "Asha", "stage", 0),
}

/** The stage's own proportions (480×460), so scenes are never cropped. */
const val AshaStageAspect = 480f / 460f

/** The largest the stage is drawn in voice mode; beyond it the scenes' line weights get heavy. */
val AshaStageMaxWidth = 340.dp

/**
 * Asha's animated mascot (asha-next.md part B), drawn from `files/asha.riv` by the platform's Rive runtime and driven by
 * data binding: [state] sets `look`, `scene`, `mood`, `activity` and `voiceLevel`; each item of [reactions] sets
 * `reaction` and fires `react`, which plays once and returns to the mood.
 *
 * - Reduced motion: she settles into the new pose and then holds still; one-shots (including the poke) are skipped,
 *   a deliberate choice: a still pose per state is the spec, and a held mid-reaction frame would read as a glitch.
 * - Battery: nothing is drawn while the app is in the background or this is off screen (the runtimes stop by
 *   themselves; see `RiveAnimation`).
 * - [fallback] (her mark or the orb) shows while the file loads and for good if it can't be drawn.
 * - [pokeable]: a tap plays the poke (asha-mascot-personality.md, "Tapping the mascot"); decorative, so no button role.
 */
@Composable
fun AshaAvatar(
    state: AshaAvatarState,
    variant: AshaAvatarVariant,
    modifier: Modifier = Modifier,
    reactions: Flow<AshaReaction> = emptyFlow(),
    pokeable: Boolean = false,
    background: Color = CircasoTheme.colors.bg,
    fallback: @Composable () -> Unit,
) {
    val file by produceState(AshaRiv.bytes, Unit) { if (value == null && !AshaRiv.failed) value = AshaRiv.load() }
    var failed by remember { mutableStateOf(false) }
    val bytes = file
    val c = CircasoTheme.colors
    val icon = variant == AshaAvatarVariant.Icon
    if (bytes == null || failed) {
        Box(modifier, contentAlignment = Alignment.Center) { fallback() }
        return
    }
    val still = CircasoTheme.reduceMotion
    // Reduced motion: let the blend into a new pose finish (Rive's blends are 200–300 ms), then hold that frame.
    var settling by remember { mutableStateOf(true) }
    val pose = state.copy(voiceLevel = 0f)
    LaunchedEffect(still, pose) {
        if (still) { settling = true; delay(ReducedMotionSettleMs); settling = false }
    }
    val pokes = remember { MutableSharedFlow<AshaReaction>(extraBufferCapacity = 1) }
    val oneShots = remember(reactions, still) {
        merge(reactions, pokes).filter { !still }.map { RiveOneShot("reaction", it.rive, "react") }
    }
    RiveAnimation(
        file = bytes,
        artboard = variant.artboard,
        stateMachine = variant.stateMachine,
        viewModel = "Asha",
        enums = mapOf(
            "framing" to variant.framing,
            // Reduced motion: the file holds a clean rest pose (eyes open, no sway) before the frame is held.
            "motion" to if (still) "still" else "moving",
            // The head crop never shows a scene: at 28 dp it would only be noise behind her face.
            "scene" to if (icon) AshaScene.None.rive else state.scene.rive,
            "look" to state.look.rive,
            "mood" to state.mood.rive,
            "activity" to state.activity.rive,
        ),
        numbers = mapOf("voiceLevel" to if (still) 0f else state.voiceLevel.coerceIn(0f, 1f)),
        oneShots = oneShots,
        playing = !still || settling,
        background = background,
        maxFps = variant.maxFps,
        // The icon sits on a disc like every other avatar, so the navy octopus reads on dark themes too.
        round = icon,
        roundFill = c.fill,
        placeholder = fallback,
        onFailed = { failed = true },
        modifier = modifier
            .then(if (icon) Modifier.clip(CircasoShapes.circle).background(c.fill) else Modifier)
            // The header already says "Asha" beside the icon, so the icon is decorative; the stage is described.
            .then(if (icon) Modifier.clearAndSetSemantics {} else Modifier.semantics { contentDescription = "Asha"; role = Role.Image })
            .then(if (pokeable) Modifier.pointerInput(Unit) { detectTapGestures { pokes.tryEmit(AshaReaction.Poke) } } else Modifier),
    )
}

private const val ReducedMotionSettleMs = 450L

/** The file is read once per process and shared by every avatar on screen. */
private object AshaRiv {
    var bytes: ByteArray? = null
    var failed = false

    suspend fun load(): ByteArray? = try {
        Res.readBytes("files/asha.riv").also { bytes = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Diagnostics.note("asha: mascot file missing (${e::class.simpleName})")
        failed = true
        null
    }
}
