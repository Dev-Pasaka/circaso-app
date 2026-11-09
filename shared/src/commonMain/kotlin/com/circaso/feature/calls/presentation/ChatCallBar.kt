package com.circaso.feature.calls.presentation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.circaso.designsystem.theme.CircasoSizes
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.calls.LiveChatCall
import com.circaso.feature.calls.whoIsOn

/** What tapping a chat's call bar does. */
enum class CallBarAction {
    /** I'm not on it: join it now (CL3), no second confirmation (Pasaka, 2026-11-09). */
    Join,
    /** I'm on a call in another chat: the tap ends that one and joins this one, so the pill says "Switch". */
    Switch,
    /** This phone is on it, minimised: back to the call screen. */
    Return,
    /** Shown, not tappable: I'm on it from another phone ([ChatCallBar.note]), offline, or this phone can't call. */
    None,
}

/** Someone's face in the bar. */
data class CallBarFace(val name: String, val photoUrl: String?)

/**
 * The thread's "call in progress" bar (2026-11-09; no frame for it, the decisions are recorded here). [who] is "Amina
 * and 2 others" in a group, the person's name in a direct chat; [sinceMillis] starts the running time; [note] replaces
 * "Voice call" when the bar says something else ("Calling…", "On your other phone", "Needs a connection").
 */
data class ChatCallBar(
    val video: Boolean,
    val who: String,
    val faces: List<CallBarFace>,
    val sinceMillis: Long?,
    val action: CallBarAction,
    val note: String? = null,
    /** The tap is on its way (a Switch reads the call before hanging up mine): the pill spins and the bar waits. */
    val busy: Boolean = false,
)

/**
 * The bar for [live] in a chat, or null when nothing should show: [mine] is this phone's own call. My own call on its
 * full screen needs no bar; minimised it returns to it. A call I'm on from another phone shows but can't be joined
 * here: the server would move my session to this phone, the other phone would hear nothing, hang up after its 20 s
 * stall, and that hang-up would end a direct call for both. Joining needs the server: offline ([online] false) the bar
 * stays (the call is real) but says "Needs a connection" instead of offering a tap that would fail (offline-first.md);
 * on a phone that can't call ([canCall] false) it only shows. Names and photos come from this phone ([face]) first.
 */
fun chatCallBar(
    chatId: String,
    live: LiveChatCall?,
    mine: com.circaso.feature.calls.CallUi?,
    me: String?,
    online: Boolean = true,
    canCall: Boolean = true,
    face: (userId: String, fallbackName: String) -> CallBarFace,
): ChatCallBar? {
    val busy = mine?.takeIf { it.phase != com.circaso.feature.calls.CallPhase.Ended }
    val myHere = busy?.takeIf { it.chatId == chatId }
    if (myHere != null) {
        // ringing me (H1), or the call's own screen showing: that screen says it all
        if (!myHere.minimised || myHere.phase == com.circaso.feature.calls.CallPhase.Incoming) return null
        val same = live?.takeIf { it.callId == myHere.callId }
        val on = same?.joined ?: myHere.joined.map { com.circaso.feature.calls.LiveCallPerson(it.userId, it.name) }
        val others = on.filter { it.userId != me }
        val active = myHere.phase == com.circaso.feature.calls.CallPhase.Active
        return ChatCallBar(
            video = myHere.video || myHere.camera,
            who = if (others.isEmpty()) myHere.title.ifBlank { "Call" } else whoIsOn(on, me) { face(it.userId, it.name).name },
            faces = others.ifEmpty { listOfNotNull(myHere.people.firstOrNull()?.let { com.circaso.feature.calls.LiveCallPerson(it.userId, it.name) }) }
                .take(3).map { face(it.userId, it.name) },
            // the clock runs once someone is really heard, as on the call screen
            sinceMillis = if (active) myHere.activeSince ?: same?.sinceMillis else null,
            action = CallBarAction.Return,
            note = when (myHere.phase) {
                com.circaso.feature.calls.CallPhase.Ringing -> "Calling…"
                com.circaso.feature.calls.CallPhase.Connecting -> "Connecting…"
                com.circaso.feature.calls.CallPhase.Reconnecting -> "Reconnecting…"
                else -> null
            },
        )
    }
    if (live == null) return null
    val others = live.joined.filter { it.userId != me }
    if (others.isEmpty() && !live.meOn) return null
    return ChatCallBar(
        video = live.video,
        who = whoIsOn(live.joined, me) { face(it.userId, it.name).name },
        faces = others.ifEmpty { live.joined }.take(3).map { face(it.userId, it.name) },
        sinceMillis = live.sinceMillis,
        action = when {
            live.meOn || !canCall || !online -> CallBarAction.None
            busy != null -> CallBarAction.Switch
            else -> CallBarAction.Join
        },
        note = when {
            live.meOn -> "On your other phone"
            !online && canCall -> "Needs a connection"
            else -> null
        },
    )
}

/**
 * Full width under the title bar, ink on the money role (the colours of the call pill it stands in for here): up to
 * three faces, who is on it, voice or video and its running time, and a pill that says what a tap does. The whole bar
 * is the one target (at least 56 dp tall). No green: the palette is monochrome, and the call screen's own Accept is
 * white for the same reason; the live dot breathes instead (still with reduced motion). Only the clock ticks: the
 * bar itself recomposes when the call changes, and the spoken label changes once a minute.
 */
@Composable
fun ChatCallBarView(bar: ChatCallBar, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val kind = if (bar.video) "Video call" else "Voice call"
    val label = bar.note ?: kind
    val actionLabel = when (bar.action) {
        CallBarAction.Join -> "Join"; CallBarAction.Switch -> "Switch"; CallBarAction.Return -> "Return"; CallBarAction.None -> null
    }
    val minutes = rememberWholeMinutes(bar.sinceMillis)
    val spoken = buildString {
        append("$kind in progress. ${bar.who}")
        bar.note?.let { append(". $it") }
        if (minutes != null) append(if (minutes < 1) ". Just started" else if (minutes == 1L) ". 1 minute" else ". $minutes minutes")
    }
    val clickLabel = when (bar.action) {
        CallBarAction.Join -> "Join the call"
        CallBarAction.Switch -> "End your current call and join this one"
        CallBarAction.Return -> "Return to the call"
        CallBarAction.None -> null
    }
    Row(
        modifier.fillMaxWidth().background(c.money)
            .then(if (clickLabel != null && !bar.busy) Modifier.pressable(role = Role.Button, onClick = onTap) else Modifier)
            .clearAndSetSemantics {
                contentDescription = spoken
                if (clickLabel != null && !bar.busy) { role = Role.Button; onClick(label = clickLabel) { onTap(); true } }
            }
            .defaultMinSize(minHeight = CircasoSpacing.s14)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically,
    ) {
        Faces(bar.faces, bar.video)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 / 2)) {
            BasicText(bar.who, style = t.subhead.copy(color = c.onMoney, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The label gives way at large text, never the clock (the live part).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 + CircasoSpacing.s1 / 2)) {
                LiveDot()
                BasicText(label, Modifier.weight(1f, fill = false), style = t.footnote.copy(color = c.onMoney), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bar.sinceMillis != null) {
                    BasicText("·", style = t.footnote.copy(color = c.onMoney))
                    CallClockText(bar.sinceMillis, t.footnote.copy(color = c.onMoney, fontFeatureSettings = "tnum"))
                }
            }
        }
        if (actionLabel != null) {
            Box(
                Modifier.clip(CircasoShapes.pill).background(c.onMoney).defaultMinSize(minHeight = CircasoSpacing.s8)
                    .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s1 + CircasoSpacing.s1 / 2),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s1 + CircasoSpacing.s1 / 2)) {
                    if (bar.busy) com.circaso.designsystem.components.Spinner(c.money, Modifier.size(14.dp))
                    else CircasoIconImage(if (bar.video) CircasoIcon.Video else CircasoIcon.Phone, c.money, Modifier.size(14.dp))
                    BasicText(actionLabel, style = t.footnote.copy(color = c.money, fontWeight = FontWeight.SemiBold), maxLines = 1)
                }
            }
        }
    }
}

/** Only this text recomposes each second. */
@Composable
private fun CallClockText(since: Long, style: androidx.compose.ui.text.TextStyle) {
    BasicText(elapsedText(since), style = style, maxLines = 1)
}

/** Whole minutes since [since], updated once a minute (for the spoken label); null without a start. */
@Composable
private fun rememberWholeMinutes(since: Long?): Long? {
    if (since == null) return null
    var minutes by androidx.compose.runtime.remember(since) { androidx.compose.runtime.mutableLongStateOf((com.circaso.core.sync.nowMillis() - since) / 60_000) }
    androidx.compose.runtime.LaunchedEffect(since) {
        while (true) {
            val elapsed = com.circaso.core.sync.nowMillis() - since
            minutes = elapsed / 60_000
            kotlinx.coroutines.delay(60_000 - elapsed % 60_000)
        }
    }
    return minutes
}

/** Up to three faces, overlapped by a third and ringed in the bar's colour; none known shows the call's icon. */
@Composable
private fun Faces(faces: List<CallBarFace>, video: Boolean) {
    val c = CircasoTheme.colors
    val size = CircasoSizes.avatarSm
    if (faces.isEmpty()) {
        Box(Modifier.size(size).clip(CircasoShapes.circle).background(c.onMoney), contentAlignment = Alignment.Center) {
            CircasoIconImage(if (video) CircasoIcon.Video else CircasoIcon.Phone, c.money, Modifier.size(16.dp))
        }
        return
    }
    val step = size * 2 / 3
    Box(Modifier.size(width = size + step * (faces.size - 1), height = size)) {
        // the first-named face on top
        faces.forEachIndexed { i, f ->
            Avatar(
                f.name,
                Modifier.offset(x = step * i).zIndex((faces.size - i).toFloat()).border(1.5.dp, c.money, CircasoShapes.circle),
                size = size, imageUrl = f.photoUrl,
            )
        }
    }
}

/** A small dot that breathes while the call goes on; steady with reduced motion. Drawn in the layer: no recomposition per frame. */
@Composable
internal fun LiveDot(color: androidx.compose.ui.graphics.Color = CircasoTheme.colors.onMoney, size: androidx.compose.ui.unit.Dp = 6.dp) {
    val dot = Modifier.size(size).clip(CircasoShapes.circle).background(color)
    if (CircasoTheme.reduceMotion) { Box(dot); return }
    val a = rememberInfiniteTransition(label = "live").animateFloat(1f, 0.35f, infiniteRepeatable(tween(com.circaso.designsystem.theme.CircasoMotion.MomentMs), RepeatMode.Reverse), label = "live-dot")
    Box(Modifier.graphicsLayer { alpha = a.value }.then(dot))
}
