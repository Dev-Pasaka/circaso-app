package com.circaso.feature.messaging.presentation.map

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.openMap
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.glass
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconButton
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.ObserveThreadUseCase
import com.circaso.feature.messaging.presentation.LocalMapPicture
import com.circaso.feature.messaging.presentation.ThreadMedia
import com.circaso.feature.messaging.presentation.coordinates
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.LocalTimeStyle
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the map's panel: a person sharing (or who shared) live, or the place that was tapped. */
data class LocationEntry(val message: Message.Media, val name: String, val avatarUrl: String?, val place: Boolean, val initialsName: String = name)

/** [loaded] false until the thread has been read once: nothing is drawn before then (no map at 0,0, no "nobody"). */
data class LocationMapUiState(val chat: Chat? = null, val entries: List<LocationEntry> = emptyList(), val focus: Long? = null, val loaded: Boolean = false)

/**
 * The map behind a location message (WhatsApp-style): everyone's latest live share in this chat, each at their own
 * pin with their photo, plus the place that was tapped when it isn't a live share. Pins move as updates arrive.
 */
class LocationMapViewModel(
    private val chatId: String,
    private val focus: Long,
    observeChat: ObserveChatUseCase,
    observeThread: ObserveThreadUseCase,
    private val media: ThreadMedia?,
) : ViewModel() {
    val state: StateFlow<LocationMapUiState> = combine(observeChat(chatId), observeThread(chatId)) { chat, messages ->
        val located = messages.filterIsInstance<Message.Media>().filter { it.details.latitude != null && it.details.longitude != null }
        // One pin per person: their newest live share.
        val live = located.filter { it.details.isLive }.groupBy { it.senderId }.values.map { it.last() }
        val tapped = located.firstOrNull { it.localId == focus }?.takeIf { !it.details.isLive }
        // The tapped one first: the map opens on it.
        val ordered = (listOfNotNull(tapped) + live).sortedByDescending { it.localId == focus }
        val entries = ordered.map { m ->
            val member = chat?.members?.firstOrNull { it.userId == m.senderId }
            val real = member?.displayName ?: m.senderName
            LocationEntry(m, if (m.outgoing) "You" else real, member?.avatarUrl, place = !m.details.isLive, initialsName = real)
        }
        LocationMapUiState(chat, entries, focus, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationMapUiState(focus = focus))

    /** The server's map picture, for when this build has no native map. */
    val mapLoader: com.circaso.feature.messaging.presentation.MapPictureLoader? = media?.let { m -> { lat, lng, w, h, zoom, palette -> m.mapPicture(lat, lng, w, h, zoom, palette) } }

    fun onStop(entry: LocationEntry) {
        val m = media ?: return
        viewModelScope.launch { m.stopLive(entry.message.localId) }
    }
}

@Composable
fun LocationMapScreen(viewModel: LocationMapViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.CompositionLocalProvider(LocalMapPicture provides viewModel.mapLoader) {
        LocationMapContent(state, onBack, viewModel::onStop)
    }
}

@Composable
fun LocationMapContent(state: LocationMapUiState, onBack: () -> Unit, onStop: (LocationEntry) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val camera = remember { MapCamera() }
    val now by produceState(nowMillis()) { while (true) { kotlinx.coroutines.delay(15_000); value = nowMillis() } }
    val pins = state.entries.map { e ->
        MapPin(e.message.localId.toString(), e.message.details.latitude!!, e.message.details.longitude!!, e.initialsName, e.avatarUrl, live = e.message.details.liveNow(now), place = e.place)
    }
    // Frame everyone once, when the map first has them; after that the person pans freely.
    val framed = remember { mutableSetOf<String>() }
    LaunchedEffect(pins.map { it.id }, camera.projector != null) {
        if (camera.projector != null && pins.isNotEmpty() && framed.add(pins.joinToString { it.id })) {
            // The first framing jumps (the map already opened on the tapped place); a new person joining glides.
            camera.fit(pins.map { it.latitude to it.longitude }, animate = framed.size > 1)
        }
    }
    Box(Modifier.fillMaxSize().background(c.bgGrouped).semantics { contentDescription = "Map" }) {
        if (state.loaded) CircasoMap(pins, camera, Modifier.fillMaxSize(), onPinTap = { if (camera.canMove) camera.moveTo(it.latitude, it.longitude, 16f) })
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            CircasoIconButton(CircasoIcon.Back, "Back", onBack, Modifier.glass(CircasoShapes.circle, floating = true))
            BasicText(
                if (state.entries.any { !it.place }) "Live location" else "Location",
                style = t.headline.copy(color = c.ink),
                modifier = Modifier.glass(CircasoShapes.pill, floating = true).padding(horizontal = CircasoSpacing.s3, vertical = 6.dp),
            )
            Box(Modifier.weight(1f))
            if (pins.size > 1 && camera.canMove) CircasoIconButton(CircasoIcon.Scan, "Show everyone", { camera.fit(pins.map { it.latitude to it.longitude }) }, Modifier.glass(CircasoShapes.circle, floating = true))
        }
        if (state.loaded) Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(CircasoSpacing.s3).windowInsetsPadding(WindowInsets.navigationBars)
                .glass(CircasoShapes.lg, floating = true)
                // A big group can have many people sharing: the panel scrolls rather than covering the map.
                .heightIn(max = 280.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(CircasoSpacing.s3),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
        ) {
            if (state.entries.isEmpty()) BasicText("Nobody is sharing a location here.", style = t.footnote.copy(color = c.inkSecondary))
            state.entries.forEach { e ->
                val d = e.message.details
                val moving = d.liveNow(now)
                val status = when {
                    e.place -> d.label ?: coordinates(d.latitude!!, d.longitude!!)
                    moving -> "Live until ${formatLocalTime(d.liveUntil ?: 0, LocalTimeStyle.Clock)} · " + updated(d.liveAt, now)
                    else -> "Ended · last seen " + (d.liveAt?.let { formatLocalTime(it, LocalTimeStyle.Clock) } ?: "earlier")
                }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (camera.canMove) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClick = { camera.moveTo(d.latitude!!, d.longitude!!, 16f) }) else Modifier)
                        .semantics(mergeDescendants = true) { contentDescription = "${e.name}, $status" + if (camera.canMove) ". Show on map" else "" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
                ) {
                    if (e.place) {
                        Box(Modifier.size(com.circaso.designsystem.theme.CircasoSizes.iconButton).glass(CircasoShapes.circle), contentAlignment = Alignment.Center) { CircasoIconImage(CircasoIcon.Pin, c.ink, Modifier.size(18.dp)) }
                    } else {
                        Avatar(e.initialsName, size = com.circaso.designsystem.theme.CircasoSizes.iconButton, imageUrl = e.avatarUrl)
                    }
                    Column(Modifier.weight(1f)) {
                        BasicText(if (e.place) "Shared place" else e.name, style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                        BasicText(status, style = t.footnote.copy(color = if (moving) c.ink else c.inkSecondary))
                    }
                    if (e.message.outgoing && moving) {
                        CircasoButton("Stop", { onStop(e) }, style = ButtonStyle.Destructive, size = ButtonSize.Small)
                    } else if (!e.message.outgoing) {
                        CircasoButton("Directions", { openMap(d.latitude!!, d.longitude!!, d.label) }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
                    }
                }
            }
        }
    }
}

private fun updated(at: Long?, now: Long): String {
    val minutes = ((now - (at ?: now)) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "updated just now"
        minutes == 1L -> "updated 1 min ago"
        minutes < 60 -> "updated $minutes min ago"
        else -> "updated ${minutes / 60} h ago"
    }
}
