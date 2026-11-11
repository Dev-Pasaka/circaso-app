package com.circaso.feature.vault.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.PinDots
import com.circaso.designsystem.components.PinKeypad
import com.circaso.designsystem.components.SecretOnScreen
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.delay

/** Where the person is in backing up: typing the PIN, typing it again, then answers and save. */
internal enum class EditPass { Enter, Again, Review }

internal val VaultUiState.editPass: EditPass
    get() = when {
        pin.length < VaultUiState.PinLength -> EditPass.Enter
        confirmPin.length < VaultUiState.PinLength || !pinsMatch -> EditPass.Again
        else -> EditPass.Review
    }

/** Replacing a vault that is already saved (as opposed to backing up for the first time). */
internal val VaultUiState.updating: Boolean get() = updatedAt != null

/**
 * Back on the form, from the system or the nav bar: one pass at a time (the second PIN or the answers page go back
 * to the first PIN), out of the form from the first, and nothing while the save is running.
 */
internal fun editBack(state: VaultUiState, actions: VaultActions) {
    if (state.busy == VaultBusy.Save) return
    when (state.editPass) {
        EditPass.Enter -> actions.onCancelEdit()
        EditPass.Again, EditPass.Review -> { actions.onConfirmPin(""); actions.onPin("") }
    }
}

/**
 * Backing up, as a PIN entry should feel: the app's own dots and keypad (never the system keyboard, so the digits
 * can't reach a keyboard's suggestions), the PIN once and then again, then a page to add the optional security
 * answers and save. The pass is worked out from the PINs the view model holds, so it survives a rotation. The whole
 * flow is a secret: never captured and never shown to Asha.
 */
@Composable
internal fun EditVault(state: VaultUiState, actions: VaultActions) {
    NoScreenCapture()
    SecretOnScreen()
    val reviewing = state.editPass == EditPass.Review
    val still = CircasoTheme.reduceMotion
    AnimatedContent(
        targetState = reviewing,
        transitionSpec = {
            if (still) fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.ExitMs))
            else (slideInHorizontally(tween(CircasoMotion.BaseMs)) { w -> if (targetState) w / 5 else -w / 5 } + fadeIn(tween(CircasoMotion.BaseMs))) togetherWith
                (slideOutHorizontally(tween(CircasoMotion.QuickMs)) { w -> if (targetState) -w / 5 else w / 5 } + fadeOut(tween(CircasoMotion.QuickMs)))
        },
        label = "vault edit page",
        modifier = Modifier.fillMaxSize(),
    ) { review -> if (review) Review(state, actions) else PinEntry(state, actions) }
}

/**
 * The two PIN passes. The keypad stays put; only the words and dots above it change, so a quick first tap of the
 * second pass lands on the keypad that takes it.
 */
@Composable
private fun PinEntry(state: VaultUiState, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val haptics = LocalHapticFeedback.current
    val len = VaultUiState.PinLength
    // While this page leaves for the answers, it keeps showing the second pass.
    val pass = state.editPass.takeIf { it != EditPass.Review } ?: EditPass.Again
    // Set when the second PIN didn't match: the dots shake, both clear, and the first pass says why.
    var mismatch by rememberSaveable { mutableStateOf(false) }
    val mismatched = state.confirmPin.length == len && !state.pinsMatch
    val shake = remember { Animatable(0f) }
    LaunchedEffect(mismatched) {
        if (!mismatched) return@LaunchedEffect
        mismatch = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        if (!still) shake.animateTo(0f, keyframes { durationMillis = ShakeMs; 0f at 0; -12f at 60; 10f at 120; -6f at 180; 3f at 240; 0f at ShakeMs })
        delay(if (still) MismatchHoldMs else MismatchHoldMs - ShakeMs)
        actions.onConfirmPin("")
        actions.onPin("")
    }
    val onDigit: (Int) -> Unit = { d ->
        when (state.editPass) {
            EditPass.Enter -> { mismatch = false; if (state.pin.length < len) actions.onPin(state.pin + d) }
            EditPass.Again -> if (!mismatched && state.confirmPin.length < len) actions.onConfirmPin(state.confirmPin + d)
            EditPass.Review -> Unit
        }
    }
    val onBackspace: () -> Unit = {
        when (state.editPass) {
            EditPass.Enter -> actions.onPin(state.pin.dropLast(1))
            EditPass.Again -> if (!mismatched) actions.onConfirmPin(state.confirmPin.dropLast(1))
            EditPass.Review -> Unit
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Words and dots at the top, keypad at the foot; with large text the page scrolls instead of clipping.
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            AnimatedContent(
                targetState = pass,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    if (still) fadeIn(tween(CircasoMotion.QuickMs)) togetherWith fadeOut(tween(CircasoMotion.ExitMs))
                    else (slideInHorizontally(tween(CircasoMotion.BaseMs)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(CircasoMotion.BaseMs))) togetherWith
                        (slideOutHorizontally(tween(CircasoMotion.QuickMs)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(CircasoMotion.QuickMs)))
                },
                label = "vault pin pass",
            ) { p ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    val first = p == EditPass.Enter
                    BasicText(if (first) "Step 1 of 3" else "Step 2 of 3", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                    BasicText(
                        if (first) "Enter your wallet PIN" else "Enter it again", Modifier.semantics { heading() },
                        style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center),
                    )
                    // Two lines either way, so the dots sit at the same height on both passes.
                    BasicText(
                        if (first) "The 6-digit PIN you use to approve payments. We can't check it against your wallet, so take care."
                        else "So we know it's right.",
                        style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center), minLines = 2,
                    )
                    PinDots(
                        if (first) state.pin.length else state.confirmPin.length,
                        Modifier.padding(top = CircasoSpacing.s4).graphicsLayer { if (!first) translationX = shake.value * density },
                        error = !first && mismatched,
                    )
                    // Holds its height so the dots don't jump when a line appears under them.
                    Box(Modifier.heightIn(min = CircasoSizes.controlMd).padding(top = CircasoSpacing.s2), contentAlignment = Alignment.TopCenter) {
                        when {
                            !first && mismatched -> BasicText("They didn't match.", style = t.footnote.copy(color = c.critical, textAlign = TextAlign.Center))
                            first && mismatch -> BasicText(
                                "The two PINs didn't match. Try again.", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = t.footnote.copy(color = c.critical, textAlign = TextAlign.Center),
                            )
                            !first -> CircasoButton("Start Over", { actions.onConfirmPin(""); actions.onPin("") }, style = ButtonStyle.Plain, size = ButtonSize.Medium)
                        }
                    }
                }
            }
            PinKeypad(onDigit, onBackspace, Modifier.padding(top = CircasoSpacing.s6, bottom = CircasoSpacing.s2))
        }
    }
}

private const val ShakeMs = 300
private const val MismatchHoldMs = 700L

/**
 * The last page: the PIN is in, the security answers are optional and folded away, then save with a passkey. When
 * a vault is already saved, it says plainly that saving replaces it, answers included.
 */
@Composable
private fun Review(state: VaultUiState, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val idle = state.busy == null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            BasicText("Step 3 of 3", style = t.footnote.copy(color = c.inkSecondary))
            BasicText(if (state.updating) "Reseal your vault" else "Seal your vault", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink))
            BasicText(
                if (state.updating) "Saving replaces what's in your vault now. Add your security answers again to keep them."
                else "Add your security answers if you like, then save with your ${state.unlockInSentence}.",
                style = t.subhead.copy(color = c.inkSecondary),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Row(
                Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface).padding(start = CircasoSpacing.s4, end = CircasoSpacing.s2, top = CircasoSpacing.s3, bottom = CircasoSpacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                IconTile(CircasoIcon.Check)
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                    BasicText("Wallet PIN", style = t.headline.copy(color = c.ink))
                    BasicText("Entered twice", style = t.footnote.copy(color = c.inkSecondary))
                }
                CircasoButton(
                    "Change", { actions.onConfirmPin(""); actions.onPin("") }, style = ButtonStyle.Plain, size = ButtonSize.Medium,
                    enabled = idle, modifier = Modifier.semantics { contentDescription = "Change PIN" },
                )
            }
            Answers(state, actions)
        }
        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            CircasoButton(
                "Save With ${state.unlock}", actions.onSave, size = ButtonSize.Large, block = true, icon = unlockIcon(state.unlock),
                busy = state.busy == VaultBusy.Save, enabled = state.canSave,
            )
            OfflineLine(state, "Backing up needs a connection.")
            ErrorLine(state)
        }
    }
}

/** The optional security answers, folded away until wanted; open when there are some, or when updating. */
@Composable
private fun Answers(state: VaultUiState, actions: VaultActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val still = CircasoTheme.reduceMotion
    val idle = state.busy == null
    var open by rememberSaveable { mutableStateOf(state.answers.isNotEmpty() || state.updating) }
    // A half-filled answer blocks saving: keep it in view.
    LaunchedEffect(state.answers.any { it.incomplete }) { if (state.answers.any { it.incomplete }) open = true }
    val turn by animateFloatAsState(if (open) 180f else 0f, if (still) snap() else tween(CircasoMotion.BaseMs), label = "chevron")
    val filled = state.answers.count { it.question.isNotBlank() && it.answer.isNotBlank() }
    // Typed in plain text so they can be checked, but still secrets: no autocorrect, no learning, no `/asha`.
    val answerKeys = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.Sentences)
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = CircasoSizes.controlMd)
                .pressable(role = Role.Button, pressedScale = 0.99f) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            IconTile(CircasoIcon.Shield)
            Column(Modifier.weight(1f)) {
                BasicText("Security answers", style = t.headline.copy(color = c.ink))
                BasicText(
                    when {
                        filled > 0 -> "$filled added"
                        state.updating -> "None yet. Saved ones will be replaced."
                        else -> "Optional"
                    },
                    style = t.footnote.copy(color = c.inkSecondary),
                )
            }
            CircasoIconImage(CircasoIcon.ChevronDown, c.inkTertiary, Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            open,
            enter = if (still) fadeIn(tween(CircasoMotion.QuickMs)) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs)),
            exit = if (still) fadeOut(tween(CircasoMotion.ExitMs)) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.ExitMs)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = CircasoSpacing.s4, end = CircasoSpacing.s4, bottom = CircasoSpacing.s4),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
            ) {
                BasicText(
                    "The questions and answers you gave when you set up your wallet, so you never need to remember them either.",
                    style = t.footnote.copy(color = c.inkSecondary),
                )
                state.answers.forEachIndexed { index, draft ->
                    key(draft.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                BasicText("Answer ${index + 1}", Modifier.weight(1f).semantics { heading() }, style = t.subhead.copy(color = c.ink))
                                CircasoButton(
                                    "Remove", { actions.onRemoveAnswer(draft.id) }, style = ButtonStyle.Plain, size = ButtonSize.Medium, enabled = idle,
                                    modifier = Modifier.semantics { contentDescription = "Remove answer ${index + 1}" },
                                )
                            }
                            CircasoTextField(
                                draft.question, { actions.onQuestion(draft.id, it) }, label = "Question",
                                placeholder = "What was the name of your first school?", singleLine = false, enabled = idle,
                                keyboardOptions = answerKeys, secret = true, masked = false,
                            )
                            CircasoTextField(
                                draft.answer, { actions.onAnswer(draft.id, it) }, label = "Answer", singleLine = false, enabled = idle,
                                keyboardOptions = answerKeys, secret = true, masked = false,
                                error = if (draft.incomplete) (if (draft.answer.isBlank()) "Add the answer, or clear the question." else "Add the question, or clear the answer.") else null,
                            )
                        }
                    }
                }
                if (state.answers.size < VaultViewModel.MaxAnswers) {
                    CircasoButton(
                        if (state.answers.isEmpty()) "Add an Answer" else "Add Another", actions.onAddAnswer,
                        style = ButtonStyle.Secondary, size = ButtonSize.Medium, icon = CircasoIcon.Plus, enabled = idle,
                    )
                }
            }
        }
    }
}

@Composable
internal fun IconTile(icon: CircasoIcon) {
    val c = CircasoTheme.colors
    Box(Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.sm).background(c.fill), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, c.ink, Modifier.size(20.dp))
    }
}
