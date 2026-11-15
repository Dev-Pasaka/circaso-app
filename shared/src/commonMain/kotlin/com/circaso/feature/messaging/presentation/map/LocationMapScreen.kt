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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.border
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.platform.GeoPoint
import com.circaso.core.platform.LocationAccess
import com.circaso.core.platform.LocationProvider
import com.circaso.core.platform.NoLocation
import com.circaso.core.platform.openAppSettings
import com.circaso.core.platform.openDirections
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
import com.circaso.feature.messaging.presentation.LocalMapPicture
import com.circaso.feature.messaging.presentation.ThreadMedia
import com.circaso.feature.messaging.presentation.coordinates
import com.circaso.core.platform.formatLocalTime
import com.circaso.core.platform.LocalTimeStyle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The map behind a location message (WhatsApp-style): everyone's latest live share in this chat, each at their own
 * pin with their photo, plus the place that was tapped when it isn't a live share. Pins move as updates arrive.
 *
 * It also shows where the person holding the phone is, how far each pin is from them and a straight line to the one
 * in focus. That position is read from the phone while this screen is open and stays on the phone: nothing here
 * sends it. (Someone sharing live is already on the map through their share, and that is the position used.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationMapViewModel(
    private val opened: Long,
    chat: Flow<Chat?>,
    messages: Flow<List<Message>>,
    private val media: ThreadMedia?,
    private val location: LocationProvider = NoLocation,
    private val myUserId: String? = null,
    private val now: () -> Long = ::nowMillis,
) : ViewModel() {
    private val focus = MutableStateFlow(opened)
    private val access = MutableStateFlow(location.access())
    /** False while Circaso is in the background: the phone's location isn't read then, by this code, not just by the system. */
    private val visible = MutableStateFlow(true)
    private val thread = combine(chat, messages) { c, m -> c to m }

    /**
     * The phone's own position, only while it's wanted: access granted, someone to measure to, and no live share of
     * my own running (that already says where I am). Collected with [state], so it stops when the screen closes.
     */
    private val fix: Flow<GeoPoint?> = combine(access, thread, visible) { a, (chat, messages), shown ->
        val s = locationMapState(chat, messages, opened, opened, null, a, myUserId, now())
        shown && a == LocationAccess.Granted && s.canMeasure && !s.sharingLive
    }.distinctUntilChanged().flatMapLatest { wanted ->
        if (wanted) location.updates().map<GeoPoint, GeoPoint?> { it }.onStart { emit(null) } else flowOf(null)
    }

    val state: StateFlow<LocationMapUiState> = combine(thread, focus, access, fix) { (chat, messages), focused, a, point ->
        locationMapState(chat, messages, opened, focused, point, a, myUserId, now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LocationMapUiState(focus = opened, access = access.value))

    /** The server's map picture, for when this build has no native map. */
    val mapLoader: com.circaso.feature.messaging.presentation.MapPictureLoader? = media?.let { m -> { lat, lng, w, h, zoom, palette -> m.mapPicture(lat, lng, w, h, zoom, palette) } }

    fun onStop(entry: LocationEntry) {
        val m = media ?: return
        viewModelScope.launch { m.stopLive(entry.message.localId) }
    }

    /** A pin or row was tapped: the line, the framing and the directions note follow it. My own share can't be it. */
    fun onFocus(entry: LocationEntry) {
        if (!entry.mine) focus.value = entry.id
    }

    /** "Turn on": the phone's own question, asked because the person tapped, once. */
    fun onAllowLocation() {
        viewModelScope.launch { access.value = location.requestAccess() }
    }

    /**
     * Back on screen (perhaps from Settings): picks up a change of mind. A refusal just given isn't forgotten:
     * Android can't tell "refused" from "never asked" afterwards, and the panel must not go back to asking.
     */
    fun onResume() {
        val a = location.access()
        if (!(access.value == LocationAccess.Denied && a == LocationAccess.NotAsked)) access.value = a
        visible.value = true
    }

    /** Circaso left the screen: reading the phone's location stops until it is back. */
    fun onPause() {
        visible.value = false
    }
}

@Composable
fun LocationMapScreen(viewModel: LocationMapViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    androidx.lifecycle.compose.LifecycleResumeEffect(viewModel) { viewModel.onResume(); onPauseOrDispose { viewModel.onPause() } }
    androidx.compose.runtime.CompositionLocalProvider(LocalMapPicture provides viewModel.mapLoader) {
        LocationMapContent(
            state, onBack, viewModel::onStop, onFocus = viewModel::onFocus, onAllowLocation = viewModel::onAllowLocation,
            onSettings = ::openAppSettings, onDirections = { openDirections(it.point.latitude, it.point.longitude) },
        )
    }
}

/** The pin of the person holding the phone, when it isn't one of the shares. */
private const val MePin = "me"

/** The panel takes at most this share of the screen's height: the map stays the subject. */
private const val PanelShare = 0.45f

/**
 * The map is told how tall the bar and panel are, and shifts to keep clear of them. A status line wrapping, or a
 * pixel of rounding, isn't worth a shift: only a change of a few points is passed on.
 */
private fun settled(current: androidx.compose.ui.unit.Dp, measured: androidx.compose.ui.unit.Dp) = if (kotlin.math.abs((measured - current).value) < 8f) current else measured

@Composable
fun LocationMapContent(
    state: LocationMapUiState,
    onBack: () -> Unit,
    onStop: (LocationEntry) -> Unit,
    onFocus: (LocationEntry) -> Unit = {},
    onAllowLocation: () -> Unit = {},
    onSettings: () -> Unit = {},
    onDirections: (LocationEntry) -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val density = LocalDensity.current
    val camera = remember { MapCamera() }
    val now by produceState(nowMillis()) { while (true) { kotlinx.coroutines.delay(15_000); value = nowMillis() } }
    val me = state.me
    val focused = state.focused
    val shares = state.entries.map { e ->
        val live = e.message.details.liveNow(now)
        MapPin(e.id.toString(), e.point.latitude, e.point.longitude, e.initialsName, e.avatarUrl, live = live, place = e.place, me = e.mine && live, mine = e.mine)
    }
    // Me last, so my pin is on top where two overlap. While I share live, my share's pin is me.
    val pins = if (me != null && !state.sharingLive) shares + MapPin(MePin, me.latitude, me.longitude, state.myName, state.myAvatarUrl, live = false, me = true) else shares
    // Opened on someone's live share: the first framing takes me in too, so both of us are in view. Opened on a
    // place, the map stays on the place (as before) and "Show you both" is one tap away.
    val openedLive = state.entries.firstOrNull()?.place == false
    val framing = if (openedLive) pins else shares
    // Frame everyone once, when the map first has them; after that the person pans freely.
    val framed = remember { mutableSetOf<String>() }
    LaunchedEffect(framing.map { it.id }, camera.projector != null) {
        if (camera.projector != null && framing.isNotEmpty() && framed.add(framing.joinToString { it.id })) {
            // The first framing jumps (the map already opened on the tapped place); a new person joining glides.
            camera.fit(framing.map { it.latitude to it.longitude }, animate = framed.size > 1)
        }
    }
    fun select(entry: LocationEntry) {
        onFocus(entry)
        if (camera.canMove) camera.moveTo(entry.point.latitude, entry.point.longitude, 16f)
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(c.bgGrouped).semantics { contentDescription = "Map" }) {
        val panelMax = maxHeight * PanelShare
        if (state.loaded) CircasoMap(
            pins, camera, Modifier.fillMaxSize(), line = state.line, framed = framing,
            onPinTap = { pin ->
                val entry = state.entries.firstOrNull { it.id.toString() == pin.id }
                if (entry != null) select(entry) else if (camera.canMove) camera.moveTo(pin.latitude, pin.longitude, 16f)
            },
        )
        Row(
            Modifier.fillMaxWidth().onSizeChanged { camera.topInset = settled(camera.topInset, with(density) { it.height.toDp() }) }
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            CircasoIconButton(CircasoIcon.Back, "Back", onBack, Modifier.glass(CircasoShapes.circle, floating = true))
            BasicText(
                if (state.entries.any { !it.place }) "Live location" else "Location",
                style = t.headline.copy(color = c.ink),
                modifier = Modifier.glass(CircasoShapes.pill, floating = true).padding(horizontal = CircasoSpacing.s3, vertical = 6.dp),
            )
            Box(Modifier.weight(1f))
            if (camera.canMove) {
                // One meaning per icon. The frame: me and whoever is in focus, together.
                if (me != null && focused != null) CircasoIconButton(
                    CircasoIcon.Scan, if (focused.place) "Show you and the place" else "Show you and ${focused.name}",
                    { camera.fit(listOf(me.latitude to me.longitude, focused.point.latitude to focused.point.longitude)) },
                    Modifier.glass(CircasoShapes.circle, floating = true),
                )
                // The people: everyone. With me on the map this is a different framing only when there are others too.
                if (pins.size > (if (me != null && focused != null) 2 else 1)) CircasoIconButton(
                    CircasoIcon.People, "Show everyone",
                    { camera.fit(pins.map { it.latitude to it.longitude }) }, Modifier.glass(CircasoShapes.circle, floating = true),
                )
            }
        }
        if (state.loaded) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { camera.bottomInset = settled(camera.bottomInset, with(density) { it.height.toDp() }) }) {
            LocationPanel(state, now, maxHeight = panelMax, canMove = camera.canMove, onSelect = ::select, onStop = onStop, onDirections = onDirections, onAllowLocation = onAllowLocation, onSettings = onSettings)
        }
    }
}

/** The glass panel over the map's foot: a row per person or place with its distance, then the note about my own location. */
@Composable
internal fun LocationPanel(
    state: LocationMapUiState,
    now: Long,
    maxHeight: androidx.compose.ui.unit.Dp,
    canMove: Boolean,
    onSelect: (LocationEntry) -> Unit,
    onStop: (LocationEntry) -> Unit,
    onDirections: (LocationEntry) -> Unit,
    onAllowLocation: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val focused = state.focused
    // The focus is only worth marking when there is more than one thing it could be.
    val markFocus = state.entries.count { !it.mine } > 1
    Column(
        Modifier.fillMaxWidth().padding(CircasoSpacing.s3).windowInsetsPadding(WindowInsets.navigationBars)
            .glass(CircasoShapes.lg, floating = true).heightIn(max = maxHeight).padding(CircasoSpacing.s2),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
    ) {
      // A big group can have many people sharing: the rows scroll rather than covering the map, and the note and
      // "Turn on" under them stay in view.
      Column(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
        if (state.entries.isEmpty()) BasicText("Nobody is sharing a location here.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(CircasoSpacing.s1))
        state.entries.forEach { e ->
            val d = e.message.details
            val moving = d.liveNow(now)
            val status = entryStatus(e, now)
            val away = e.distanceMeters?.let { distanceAway(it, state.roughness) }
            val inFocus = markFocus && e.id == focused?.id
            Row(
                Modifier.fillMaxWidth().clip(CircasoShapes.md).background(if (inFocus) c.fill else androidx.compose.ui.graphics.Color.Transparent)
                    .then(if (inFocus && c.isHighContrast) Modifier.border(1.dp, c.ink, CircasoShapes.md) else Modifier)
                    .then(if (canMove || !e.mine) Modifier.pressable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show on map", onClick = { onSelect(e) }) else Modifier)
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull(if (e.place) "Shared place" else e.name, away, status).joinToString(", ")
                        if (markFocus) selected = inFocus
                    }
                    .padding(CircasoSpacing.s1),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                if (e.place) {
                    Box(Modifier.size(com.circaso.designsystem.theme.CircasoSizes.iconButton).glass(CircasoShapes.circle), contentAlignment = Alignment.Center) { CircasoIconImage(CircasoIcon.Pin, c.ink, Modifier.size(18.dp)) }
                } else {
                    Avatar(e.initialsName, size = com.circaso.designsystem.theme.CircasoSizes.iconButton, imageUrl = e.avatarUrl)
                }
                Column(Modifier.weight(1f)) {
                    BasicText(if (e.place) "Shared place" else e.name, style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                    // The distance leads, in ink: it is what the person opened the map to find out.
                    BasicText(
                        buildAnnotatedString {
                            if (away != null) {
                                withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold)) { append(away) }
                                append(" · ")
                            }
                            append(status)
                        },
                        style = t.footnote.copy(color = if (moving) c.ink else c.inkSecondary),
                    )
                }
                if (e.mine && moving) {
                    CircasoButton("Stop", { onStop(e) }, style = ButtonStyle.Destructive, size = ButtonSize.Small)
                } else if (!e.mine) {
                    CircasoButton(
                        // On the focused row's tint a Secondary button (the same fill) would vanish.
                        "Directions", { onDirections(e) }, style = if (inFocus) ButtonStyle.Primary else ButtonStyle.Secondary, size = ButtonSize.Small,
                        modifier = Modifier.semantics { contentDescription = if (moving) "Directions to where ${e.name} is now, in your maps app" else "Directions, in your maps app" },
                    )
                }
            }
        }
      }
        // What "this" is, in the person's own terms.
        val what = state.entries.filter { !it.mine }.let { others ->
            when {
                others.size > 1 -> "everyone is"
                others.firstOrNull()?.place == true -> "this place is"
                else -> (others.firstOrNull()?.name?.substringBefore(' ') ?: "this") + " is"
            }
        }
        val notes = listOfNotNull(
            // Directions are to a point, and a person sharing live keeps moving.
            focused?.takeIf { it.message.details.liveNow(now) }?.let { "Directions lead to where ${it.name.substringBefore(' ')} is now." },
            when (val note = locationNote(state)) {
                is LocationNote.Known -> if (note.private) "Distance is in a straight line. This map doesn't share where you are." else "Distance is in a straight line."
                LocationNote.Finding -> "Finding where you are…"
                LocationNote.PhoneOff -> "Location is off on this phone. Turn it on to see how far away $what."
                else -> null
            },
        )
        if (notes.isNotEmpty()) BasicText(notes.joinToString(" "), style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s1, vertical = CircasoSpacing.s1))
        when (locationNote(state)) {
            LocationNote.Ask -> LocationAsk("See how far away $what", "Uses your location on this screen only. This map doesn't share it.", "Turn on", onAllowLocation)
            LocationNote.Refused -> LocationAsk("Location is off for Circaso", "Turn it on in Settings to see how far away $what.", "Settings", onSettings)
            else -> Unit
        }
    }
}

/** The panel's last row when my location isn't available: why it would be used, and the one button that changes it. */
@Composable
private fun LocationAsk(title: String, reason: String, action: String, onAction: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(CircasoSpacing.s1),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        Box(Modifier.size(com.circaso.designsystem.theme.CircasoSizes.iconButton).glass(CircasoShapes.circle), contentAlignment = Alignment.Center) { CircasoIconImage(CircasoIcon.Target, c.ink, Modifier.size(18.dp)) }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            BasicText(title, style = t.callout.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
            BasicText(reason, style = t.footnote.copy(color = c.inkSecondary))
        }
        CircasoButton(action, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
}

/** A row's second line, after the distance: the place's label, or how fresh the person's position is. */
internal fun entryStatus(e: LocationEntry, now: Long): String {
    val d = e.message.details
    return when {
        e.place -> d.label ?: coordinates(d.latitude!!, d.longitude!!)
        d.liveNow(now) -> "Live until ${formatLocalTime(d.liveUntil ?: 0, LocalTimeStyle.Clock)} · " + updated(d.liveAt, now)
        else -> "Ended · last seen " + (d.liveAt?.let { formatLocalTime(it, LocalTimeStyle.Clock) } ?: "earlier")
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
