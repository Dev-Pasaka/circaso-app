package com.circaso.feature.calls.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.adaptive.readableWidth
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.chrome.sheetGroupColor
import com.circaso.designsystem.chrome.sheetGroupEdge
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoSwitch
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.calls.CallPhase
import com.circaso.feature.calls.CallUi

/*
 * The call's controls (Pasaka, 2026-11-10: "the bottom bar is too cramped"). One bar for every call, 1:1 and group,
 * voice and video: money, camera, mic, More, End, left to right, End last where the thumb expects it (FaceTime,
 * WhatsApp). Everything else (share screen, flip, speaker, add people, minimise) is one tap away on the More sheet.
 * This departs from H2's two labelled rows and H4's own row on purpose: one bar, the same everywhere, nothing cramped.
 */

/** What the More sheet lists, in order. */
internal enum class CallMoreItem { ShareScreen, FlipCamera, Speaker, AddPeople, Minimise, AskAsha }

/**
 * The More sheet's rows for [call] as it is now. Only what can be done right now is listed: share screen only where this
 * phone can share ([canShareScreen]; iOS until its broadcast extension ships), flip only while the camera is on and not
 * giving way to a shared screen, Add people only on a live group call with someone to ring. Ask Asha last, where she
 * can be summoned ([canAskAsha]: a full session), never while the call is still ringing in.
 */
internal fun callMoreItems(call: CallUi, canShareScreen: Boolean, canAskAsha: Boolean = false): List<CallMoreItem> = buildList {
    if (canShareScreen) add(CallMoreItem.ShareScreen)
    if (call.camera && !call.screen) add(CallMoreItem.FlipCamera)
    add(CallMoreItem.Speaker)
    if (call.group && call.phase == CallPhase.Active && call.notOnCall.isNotEmpty()) add(CallMoreItem.AddPeople)
    add(CallMoreItem.Minimise)
    if (canAskAsha && call.phase != CallPhase.Incoming && call.phase != CallPhase.Ended) add(CallMoreItem.AskAsha)
}

/** The call as Asha's bubble sees it (2026-11-10): over the full call screen, ringing in, minimised, or none. */
fun ashaCallState(call: CallUi?): com.circaso.feature.assistant.presentation.AshaCallState = when {
    call == null || call.phase == CallPhase.Ended -> com.circaso.feature.assistant.presentation.AshaCallState.None
    call.minimised -> com.circaso.feature.assistant.presentation.AshaCallState.Minimised
    call.phase == CallPhase.Incoming -> com.circaso.feature.assistant.presentation.AshaCallState.Ringing
    else -> com.circaso.feature.assistant.presentation.AshaCallState.OnScreen
}

/**
 * The bar: a compact glass pill, centred, never wider than a hand's width on a tablet or a phone on its side. [onMoney]
 * is the money button (null where no money can go from this call), [moneyLabel] what it says to a screen reader.
 * [voice]: the camera is off by definition, so its button offers to turn it on rather than standing out as off.
 */
@Composable
internal fun CallControlBar(
    call: CallUi,
    actions: CallActions,
    onMoney: (() -> Unit)?,
    moneyLabel: String,
    voice: Boolean,
    moreOpen: Boolean,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.readableWidth(CallBarWidth)) {
        // On a narrow phone the buttons step down a little (never under the 44 dp touch target) so all five fit.
        val count = 4 + (if (onMoney != null) 1 else 0)
        val room = (maxWidth - CircasoSpacing.s3 * 2) / count
        val button = if (room >= CallBarButton) CallBarButton else maxOf(room, CircasoSizes.controlMd)
        Row(
            Modifier.fillMaxWidth().clip(CircasoShapes.pill).background(CallColors.Panel).padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s2),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMoney != null) MoneyButton(CircasoIcon.Money, moneyLabel, onMoney, button)
            if (voice) ControlButton(CircasoIcon.Video, null, "Turn camera on", actions.onCamera, size = button)
            else ControlButton(if (call.camera) CircasoIcon.Video else CircasoIcon.VideoOff, null, if (call.camera) "Turn camera off" else "Turn camera on", actions.onCamera, on = !call.camera, size = button)
            ControlButton(if (call.mic) CircasoIcon.Mic else CircasoIcon.MicOff, null, if (call.mic) "Mute" else "Unmute", actions.onMute, on = !call.mic, size = button)
            ControlButton(CircasoIcon.More, null, "More call options", onMore, toggle = false, size = button)
            ControlButton(CircasoIcon.Phone, null, if (call.group) "Leave call" else "End call", actions.onEnd, tint = Tint.End, size = button)
        }
    }
}

/** Design H4's money moment (H3's coin), on every call: a button of its own on the money card's colours. */
@Composable
private fun MoneyButton(icon: CircasoIcon, description: String, onClick: () -> Unit, size: Dp) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Brush.linearGradient(listOf(c.moneyCardSheen, c.moneyCard)))
            .pressable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(icon, c.onMoney, Modifier.size(24.dp)) }
}

/**
 * The More sheet, dark over the black call in every theme (as the money sheet; high contrast is kept). An action closes
 * it so the person sees what it did; the speaker switch keeps it open so its new state shows.
 */
@Composable
internal fun BoxScope.CallMoreSheet(visible: Boolean, call: CallUi, actions: CallActions, onDismiss: () -> Unit) {
    val highContrast = com.circaso.designsystem.platform.rememberIsHighContrast()
    CompositionLocalProvider(com.circaso.designsystem.theme.LocalCircasoColors provides com.circaso.designsystem.theme.circasoColors(dark = true, highContrast = highContrast)) {
        CircasoSheet(visible, onDismiss, label = "Call options") {
            SheetHead("Call options", onDismiss)
            val items = callMoreItems(call, actions.onShareScreen != null, actions.onAskAsha != null)
            val shape = CircasoShapes.lg
            Column(Modifier.fillMaxWidth().clip(shape).background(sheetGroupColor()).sheetGroupEdge(shape)) {
                items.forEachIndexed { i, item ->
                    if (i > 0) RowDivider(Modifier.padding(start = MoreIconInset))
                    when (item) {
                        CallMoreItem.ShareScreen -> MoreRow(
                            CircasoIcon.ScreenShare,
                            if (call.screen) "Stop sharing" else "Share screen",
                            when {
                                call.screen -> if (call.group) "Everyone on the call can see your screen" else "${them(call)} can see your screen"
                                else -> if (call.group) "Everyone on the call will see your screen" else "${them(call)} will see your screen"
                            },
                        ) { onDismiss(); actions.onShareScreen?.invoke() }
                        CallMoreItem.FlipCamera -> MoreRow(CircasoIcon.FlipCamera, "Flip camera", if (call.frontCamera) "Use the back camera" else "Use the front camera") { onDismiss(); actions.onFlip() }
                        CallMoreItem.Speaker -> SpeakerRow(call.speaker, actions.onSpeaker)
                        CallMoreItem.AddPeople -> MoreRow(CircasoIcon.UserPlus, "Add people", "Ring ${call.notOnCall.joinToString { it.name.substringBefore(' ') }}") { onDismiss(); actions.onAddPeople() }
                        CallMoreItem.Minimise -> MoreRow(CircasoIcon.Minimise, "Minimise call", "Keep talking while you use Circaso") { onDismiss(); actions.onMinimise(true) }
                        // Her bubble opens over the call; she answers in text while the call has the microphone.
                        CallMoreItem.AskAsha -> ListRow(
                            "Ask Asha", sub = "Type a question without leaving the call",
                            leading = { com.circaso.designsystem.components.AshaMark(size = MoreIcon) },
                            onClick = { onDismiss(); actions.onAskAsha?.invoke() },
                        )
                    }
                }
            }
        }
    }
}

/** The other person on a 1:1 call, by first name. */
private fun them(call: CallUi): String = com.circaso.designsystem.components.firstNameWord(call.people.firstOrNull()?.name ?: call.title).ifBlank { call.title }

@Composable
private fun MoreRow(icon: CircasoIcon, title: String, sub: String?, onClick: () -> Unit) {
    ListRow(title, sub = sub, leading = { MoreRowIcon(icon) }, onClick = onClick)
}

/** The whole row is the switch, spoken as "Speaker, on/off". */
@Composable
private fun SpeakerRow(on: Boolean, onToggle: () -> Unit) {
    ListRow(
        "Speaker", Modifier.toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() }),
        leading = { MoreRowIcon(CircasoIcon.Speaker) }, trailing = { CircasoSwitch(on, null) },
    )
}

@Composable
private fun MoreRowIcon(icon: CircasoIcon) = CircasoIconImage(icon, CircasoTheme.colors.ink, Modifier.size(MoreIcon))

/** The More sheet's row icon, and where its dividers start (under the text, past the icon; [ListRow] adds its own inset). */
private val MoreIcon = 24.dp
private val MoreIconInset = MoreIcon + CircasoSpacing.s3

/** The call bar at its widest. */
private val CallBarWidth = 520.dp

/** A control on the call bar (no label under it). */
internal val CallBarButton = 52.dp
