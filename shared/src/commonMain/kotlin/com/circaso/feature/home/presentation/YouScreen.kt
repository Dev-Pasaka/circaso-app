package com.circaso.feature.home.presentation

import androidx.compose.foundation.layout.size
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.SwitchRow
import com.circaso.designsystem.components.CircasoIconImage
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import com.circaso.designsystem.components.CircasoIconButton
import androidx.compose.foundation.layout.statusBars
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import coil3.request.crossfade
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.ConfirmSheet
import com.circaso.designsystem.coins.AcknowledgementsSheetContent
import com.circaso.core.sync.FailedChange
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Where each row on the You tab leads. */
data class YouActions(
    val onEditProfile: () -> Unit,
    val onContacts: () -> Unit,
    val onBlocked: () -> Unit,
    val onSecurity: () -> Unit,
    val onSecretMenu: () -> Unit,
    val onRetry: (FailedChange) -> Unit = {},
    val onDiscard: (FailedChange) -> Unit = {},
    val onNotifications: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onNeedsAttention: () -> Unit = {},
    val onCurrency: () -> Unit = {},
    val onMoneySound: (Boolean) -> Unit = {},
    /** Notification privacy on or off. */
    val onNotificationPrivacy: (Boolean) -> Unit = {},
    /** Who sees my moments (P1). */
    val onMomentsPrivacy: () -> Unit = {},
    /** The share links I made, each one can be turned off (SH2, SH3). */
    val onSharedLinks: () -> Unit = {},
)

@Composable
fun YouScreen(viewModel: YouViewModel, actions: YouActions, appVersion: String, bottomPadding: androidx.compose.foundation.layout.PaddingValues) {
    val state by viewModel.state.collectAsState()
    YouContent(state, actions.copy(onRetry = viewModel::onRetry, onDiscard = viewModel::onDiscard), appVersion, bottomPadding, viewModel::onSignOutRequested, viewModel::onSignOutCancelled, viewModel::onSignOutConfirmed)
}

/** Design F2. Stateless so it can be shown with sample data. Money rows (currency, limits, verification) arrive with the wallet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun YouContent(
    state: YouUiState,
    actions: YouActions,
    appVersion: String,
    bottomPadding: androidx.compose.foundation.layout.PaddingValues,
    onSignOutRequested: () -> Unit,
    onSignOutCancelled: () -> Unit,
    onSignOutConfirmed: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val scroll = rememberScrollState()
    // Where the hero ends, measured from the top of the screen, so the glow behind it knows how far to reach.
    var heroBottom by remember { mutableStateOf(0) }
    val short = com.circaso.designsystem.adaptive.LocalWindowLayout.current.short
    // Tapping the photo opens it large; it springs out of the circle and back into it.
    var viewingPhoto by remember { mutableStateOf(false) }
    // The open-source credits (the Web3 Icons licence) open in a sheet over the tab.
    var showCredits by remember { mutableStateOf(false) }
    var photoBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val photoOpen = remember { androidx.compose.animation.core.Animatable(0f) }
    CircasoScaffold(
        grouped = true,
        // On its side the profile and the settings sit side by side: a wider frame, still not the whole screen.
        maxContentWidth = if (short) com.circaso.designsystem.adaptive.WindowLayout.WideContentWidth else com.circaso.designsystem.adaptive.WindowLayout.ReadableWidth,
        backdrop = {
            ProfileGlow(
                photo = state.profile?.avatarUrl,
                // Drawn by the outermost scaffold from the top of the screen (under the status bar), edge to edge.
                modifier = if (short) {
                    // On its side the glow belongs to the profile column alone: full height, fading out sideways
                    // before the settings, and still while they scroll.
                    Modifier.fillMaxWidth(0.5f).fillMaxHeight()
                        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(0.6f to androidx.compose.ui.graphics.Color.Black, 1f to androidx.compose.ui.graphics.Color.Transparent),
                                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                            )
                        }
                } else {
                    Modifier.fillMaxWidth().height(with(LocalDensity.current) { heroBottom.toDp() })
                        .graphicsLayer { translationY = -scroll.value.toFloat() }
                },
            )
        },
        // No title on the tabs (Pasaka, 2026-10-30): the tab bar says where you are.
        banner = { ConnectionBanner(showing = "Showing what is saved on this phone") },
        overlay = {
            state.profile?.avatarUrl?.let { photo ->
                PhotoViewer(photo, state.profile.displayName, photoBounds, viewingPhoto, photoOpen, onClose = { viewingPhoto = false })
            }
            ConfirmSheet(
                visible = state.confirmSignOut,
                title = "Sign out of Circaso?",
                message = "You can sign back in with Google or Apple. Your chats and money stay safe, and this phone stays yours. Anything not yet sent from this phone is removed.",
                confirmLabel = "Sign Out",
                onConfirm = onSignOutConfirmed,
                onDismiss = onSignOutCancelled,
            )
            CircasoSheet(showCredits, { showCredits = false }, label = "Acknowledgements") {
                AcknowledgementsSheetContent(onClose = { showCredits = false })
            }
        },
    ) { padding ->
        val hero: @Composable (Modifier) -> Unit = { m ->
                ProfileHero(
                    name = state.profile?.let { it.displayName.ifBlank { "Your profile" } },
                    handle = state.profile?.handle,
                    phone = state.maskedPhone?.maskedForDisplay(),
                    photo = state.profile?.avatarUrl,
                    waiting = state.profile?.pendingSync == true,
                    onEdit = actions.onEditProfile,
                    onPhoto = { viewingPhoto = true },
                    onPhotoBounds = { photoBounds = it },
                    photoHidden = photoOpen.value > 0.001f,
                    // Its bottom edge in the window, plus how far the page has scrolled: the glow's height from the top.
                    modifier = m.onGloballyPositioned { heroBottom = (it.boundsInWindow().bottom + if (short) 0 else scroll.value).toInt().coerceAtLeast(0) },
                )
        }
        val settings: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
                // Changes that couldn't be saved get their own screen (Pasaka, 2026-10-30); here, one row that says how many.
                if (state.failed.isNotEmpty()) {
                    ListGroup {
                        ListRow(
                            title = "Needs attention",
                            sub = if (state.failed.size == 1) "1 change wasn't saved" else "${state.failed.size} changes weren't saved",
                            leading = { CircasoIconImage(CircasoIcon.Warning, c.critical, Modifier.size(22.dp)) },
                            trailing = { RowChevron() },
                            onClick = actions.onNeedsAttention,
                        )
                    }
                }
                Section("People") {
                    ListRow("Contacts", trailing = { RowChevron() }, onClick = actions.onContacts)
                    RowDivider()
                    ListRow("Blocked people", trailing = { RowChevron() }, onClick = actions.onBlocked)
                }
                Section("Security") {
                    ListRow("Security and Privacy", trailing = { RowChevron() }, onClick = actions.onSecurity)
                    RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    ListRow("Moments", sub = "Who can see your moments", trailing = { RowChevron() }, onClick = actions.onMomentsPrivacy)
                    RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    ListRow("Shared links", sub = "Links to receipts, statements, your address and invites", trailing = { RowChevron() }, onClick = actions.onSharedLinks)
                    RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    ListRow("Notifications", trailing = { RowChevron() }, onClick = actions.onNotifications)
                    if (com.circaso.core.notifications.notificationPrivacySupported) {
                        RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                        SwitchRow(
                            "Notification privacy", state.notificationPrivacy, actions.onNotificationPrivacy,
                            sub = "Money, chats and calls say only that something arrived",
                        )
                    }
                }
                Section("Money") {
                    ListRow("Currency", sub = "How amounts are shown in your wallet and chats", trailing = { RowChevron() }, onClick = actions.onCurrency)
                    RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    SwitchRow("Money sound", state.moneySound, actions.onMoneySound, sub = "Coins dropping into your wallet when money arrives")
                }
                // Asha's settings live in her own menu, inside her chat (Pasaka, 2026-11-07); the You tab keeps Help.
                Section(null) {
                    ListRow("Help", sub = "Chat with a person at Circaso", trailing = { RowChevron() }, onClick = actions.onHelp)
                    RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    ListRow("Acknowledgements", sub = "Coin and network icons", trailing = { RowChevron() }, onClick = { showCredits = true })
                }
                Section(null) {
                    ListRow("Sign out", destructive = true, onClick = onSignOutRequested)
                }
                BasicText(
                    "Circaso $appVersion",
                    Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = actions.onSecretMenu),
                    style = t.footnote.copy(color = c.inkTertiary, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                )
        }
        if (short) {
            // A phone on its side: the profile holds still on the left, the settings scroll beside it.
            Row(Modifier.fillMaxSize().padding(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s5)) {
                Box(
                    Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = CircasoSpacing.s6 + bottomPadding.calculateBottomPadding() + padding.calculateBottomPadding()),
                    contentAlignment = Alignment.Center,
                ) { hero(Modifier) }
                Column(
                    Modifier.weight(0.58f).fillMaxHeight().verticalScroll(scroll)
                        .padding(top = CircasoSpacing.s4, bottom = CircasoSpacing.s6 + bottomPadding.calculateBottomPadding() + padding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
                    content = settings,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll)
                    .padding(horizontal = CircasoSpacing.s4)
                    .padding(top = CircasoSpacing.s2, bottom = CircasoSpacing.s6 + bottomPadding.calculateBottomPadding() + padding.calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
            ) {
                hero(Modifier)
                settings()
            }
        }
    }
}

/** Changes that couldn't be saved, each with the whole reason and Retry or Discard; opened from the You tab. */
@Composable
fun NeedsAttentionScreen(viewModel: YouViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Nothing left to fix: go back rather than show an empty screen.
    // (only once the list has really been read: a new screen starts empty before the outbox answers)
    androidx.compose.runtime.LaunchedEffect(state.failedLoaded, state.failed.isEmpty()) { if (state.failedLoaded && state.failed.isEmpty()) onBack() }
    com.circaso.designsystem.chrome.CircasoScaffold(
        grouped = true,
        topBar = { com.circaso.designsystem.chrome.CircasoNavBar(title = "Needs Attention", onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(top = CircasoSpacing.s4, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s4),
        ) {
            BasicText("These didn't reach the server. Try again, or discard them to keep what was there.", style = t.footnote.copy(color = c.inkSecondary), modifier = Modifier.padding(horizontal = CircasoSpacing.s4 + 4.dp))
            ListGroup(Modifier.padding(horizontal = CircasoSpacing.s4)) {
                state.failed.forEachIndexed { index, change ->
                    if (index > 0) RowDivider()
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                    ) {
                        BasicText(change.title, style = t.body.copy(color = c.ink))
                        BasicText(change.reason, style = t.subhead.copy(color = c.inkSecondary)) // the whole reason, never cut short
                        Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            CircasoButton("Retry", { viewModel.onRetry(change) }, style = ButtonStyle.Secondary, size = ButtonSize.Small)
                            CircasoButton("Discard", { viewModel.onDiscard(change) }, style = ButtonStyle.Destructive, size = ButtonSize.Small)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The You tab's top: the photo large and centred, glowing in its own colours (Pasaka, 2026-10-30, in place of F2's
 * grouped profile row). The glow ([ProfileGlow]) sits behind the whole screen from under the status bar down to the
 * hero's end, and scrolls away with it.
 */
@Composable
private fun ProfileHero(
    name: String?,
    handle: String?,
    phone: String?,
    photo: String?,
    waiting: Boolean,
    onEdit: () -> Unit,
    onPhoto: () -> Unit,
    onPhotoBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    photoHidden: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        modifier.fillMaxWidth().padding(top = CircasoSpacing.s4, bottom = CircasoSpacing.s2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        if (name == null) {
            // Loading: shapes only, so "Your profile" never flashes before the real name.
            Box(Modifier.size(CircasoSizes.avatarHero).clip(CircasoShapes.circle).background(c.fillStrong))
            Box(Modifier.padding(top = CircasoSpacing.s1).size(160.dp, 24.dp).clip(CircasoShapes.sm).background(c.fill))
            Box(Modifier.size(120.dp, 16.dp).clip(CircasoShapes.sm).background(c.fill))
        } else {
            // The ring in the page colour cuts the photo out of its own glow. On arrival it comes into focus a beat
            // after the backdrop starts melting: a small spring in scale and a fade, never a bounce in layout.
            val stillHero = CircasoTheme.reduceMotion
            val focus = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (stillHero) 1f else 0f) }
            val heroArrival = LocalTabArrival.current
            androidx.compose.runtime.LaunchedEffect(heroArrival) {
                if (heroArrival < 0) return@LaunchedEffect
                if (stillHero) focus.snapTo(1f) else {
                    focus.snapTo(0f)
                    kotlinx.coroutines.delay(AvatarFocusDelayMs)
                    focus.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 260f))
                }
            }
            Avatar(
                name, size = CircasoSizes.avatarHero, imageUrl = photo,
                modifier = Modifier.onGloballyPositioned { onPhotoBounds(it.boundsInWindow()) }
                    // While it is open large, the circle is where it came from: empty, so the photo reads as lifted out.
                    .graphicsLayer {
                        alpha = if (photoHidden) 0f else focus.value.coerceIn(0f, 1f)
                        val z = 0.92f + 0.08f * focus.value; scaleX = z; scaleY = z
                    }
                    .shadow(AvatarShadow, CircasoShapes.circle).border(AvatarRing, c.bgGrouped, CircasoShapes.circle)
                    .let { m -> if (photo != null) m.pressable(role = androidx.compose.ui.semantics.Role.Image, onClick = onPhoto).semantics { contentDescription = "Your photo. Open it large" } else m },
            )
            Column(
                Modifier.padding(top = CircasoSpacing.s1).semantics(mergeDescendants = true) {},
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1),
            ) {
                BasicText(name, style = t.title2.copy(color = c.ink, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                listOfNotNull(handle?.let { "@$it" }, phone).joinToString("  ·  ").takeIf { it.isNotEmpty() }?.let {
                    BasicText(it, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
                if (waiting) BasicText("Waiting to send", style = t.caption1.copy(color = c.warning))
            }
            CircasoButton("Edit Profile", onEdit, modifier = Modifier.padding(top = CircasoSpacing.s1).heightIn(min = CircasoSizes.controlMd), style = ButtonStyle.Secondary, size = ButtonSize.Small)
        }
    }
}

/**
 * The photo's own colours as a wash: the picture decoded tiny and stretched, so it is soft on every Android version
 * (a blur effect alone needs Android 12). Strongest under the status bar, it rises from the top of the screen and
 * fades into the page by the end of the hero. Without a photo, or in high contrast, a quiet grey.
 */
@Composable
private fun ProfileGlow(photo: String?, modifier: Modifier) {
    val c = CircasoTheme.colors
    val page = c.bgGrouped
    // Arriving on the tab (Pasaka, 2026-11-07): the photo starts sharp and melts into its soft wash while the page
    // settles from a touch of zoom, so the eye lands on the round photo rather than the backdrop just appearing.
    val still = CircasoTheme.reduceMotion
    val settle = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (still) 1f else 0f) }
    val arrival = LocalTabArrival.current
    androidx.compose.runtime.LaunchedEffect(photo, arrival) {
        if (arrival < 0) return@LaunchedEffect
        if (still) settle.snapTo(1f)
        else {
            settle.snapTo(0f)
            settle.animateTo(1f, androidx.compose.animation.core.tween(GlowSettleMs, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    Box(modifier.clipToBounds().graphicsLayer { val z = 1f + (1f - settle.value) * GlowZoom; scaleX = z; scaleY = z }) {
        if (photo != null && !c.isHighContrast) {
            val key = photo.substringBefore('?')
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(photo)
                    .size(GlowDecodePx).memoryCacheKey("$key#glow").diskCacheKey(key).crossfade(true).build(),
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(GlowBlur).graphicsLayer { alpha = GlowAlpha * settle.value },
            )
            // The sharp photo over it, only while it settles: blurring (Android 12+, iOS) and fading into the wash.
            if (settle.value < 1f) coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(photo)
                    .memoryCacheKey("$key#full").diskCacheKey(key).build(),
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(GlowBlur * settle.value).graphicsLayer { alpha = GlowAlpha * (1f - settle.value) },
            )
        } else {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.fillStrong, page))))
        }
        // A light veil at the top keeps the status bar readable; then denser behind the name, and gone at the end.
        Box(
            Modifier.fillMaxSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to page.copy(alpha = 0.25f), 0.35f to page.copy(alpha = 0.15f), 0.6f to page.copy(alpha = 0.6f),
                    0.8f to page.copy(alpha = 0.92f), 1f to page,
                ),
            ),
        )
    }
}

/** Decoded this small, the picture stretches into a soft wash of its colours; cheap to load and to draw. */
private const val GlowDecodePx = 24
/** Extra smoothing where blur is supported (Android 12+, iOS); harmless where it isn't. */
private val GlowBlur = 24.dp
private const val GlowAlpha = 0.8f
/** The backdrop melting from the sharp photo into its wash on arrival. */
private const val GlowSettleMs = 650
/** How far the backdrop is zoomed as it starts to settle (4%). */
private const val GlowZoom = 0.04f
private val AvatarShadow = 18.dp
/** The round photo comes into focus this long after the backdrop starts to settle. */
private const val AvatarFocusDelayMs = 120L
private val AvatarRing = 3.dp

/**
 * The profile photo, large: it grows out of the hero's circle into a full-width square on a dark backdrop with a
 * spring that overshoots a little, and springs back into the circle on a tap, a swipe down or Back. The full-size
 * picture loads behind the avatar's cached one, so it never shows empty.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PhotoViewer(
    photo: String,
    name: String,
    from: androidx.compose.ui.geometry.Rect,
    open: Boolean,
    progress: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    onClose: () -> Unit,
) {
    val drag = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(open) {
        if (open) drag.snapTo(0f)
        progress.animateTo(if (open) 1f else 0f, if (open) PhotoOpenSpring else PhotoCloseSpring)
    }
    androidx.compose.ui.backhandler.BackHandler(enabled = open, onBack = onClose)
    if (!open && progress.value <= 0.001f) return
    var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val key = photo.substringBefore('?')
    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow(); box = it.size }
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag.value > PhotoDismissPx) onClose() else scope.launch { drag.animateTo(0f, PhotoCloseSpring) } },
                    onVerticalDrag = { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } },
                )
            },
    ) {
        if (box == androidx.compose.ui.unit.IntSize.Zero) return@Box
        val p = progress.value
        // The backdrop fades with the open state and lightens as the photo is pulled down.
        val fade = p.coerceIn(0f, 1f) * (1f - (drag.value / (box.height / 2f)).coerceIn(0f, 1f))
        // Around the photo, its own colours: the glow's tiny decode stretched over the screen, with a dark veil for depth.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = fade }.background(androidx.compose.ui.graphics.Color.Black)) {
            coil3.compose.AsyncImage(
                model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(photo)
                    .size(GlowDecodePx).memoryCacheKey("$key#glow").diskCacheKey(key).build(),
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(GlowBlur).graphicsLayer { scaleX = 1.2f; scaleY = 1.2f },
            )
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = ViewerVeil)))
        }
        val side = box.width.toFloat()
        val start = from.translate(-origin.x, -origin.y)
        val endLeft = 0f
        val endTop = (box.height - side) / 2f
        val left = start.left + (endLeft - start.left) * p
        val top = start.top + (endTop - start.top) * p + drag.value
        val size = start.width + (side - start.width) * p
        val radius = (size / 2f) * (1f - p).coerceIn(0f, 1f)
        val density = LocalDensity.current
        coil3.compose.AsyncImage(
            model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(photo)
                // Decoded for the full width it opens to: sized by the layout, it would decode at the small circle it
                // starts from and stretch that across the screen (the photo looked blurry).
                .size(side.toInt().coerceAtLeast(1))
                .memoryCacheKey("$key#full").diskCacheKey(key).placeholderMemoryCacheKey("$key@${CircasoSizes.avatarHero.value.toInt()}").crossfade(true).build(),
            contentDescription = "$name's photo",
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()) }
                .size(with(density) { size.coerceAtLeast(1f).toDp() })
                .graphicsLayer { shape = androidx.compose.foundation.shape.RoundedCornerShape(radius); clip = true },
        )
        CircasoIconButton(
            CircasoIcon.Back, "Back", onClose,
            modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)
                .padding(start = CircasoSpacing.s4, top = CircasoSpacing.s2).graphicsLayer { alpha = fade },
        )
    }
}

/** Opening overshoots a touch, like a spring let go; closing settles without a bounce back into the circle. */
private val PhotoOpenSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
private val PhotoCloseSpring = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.86f, stiffness = 520f)
/** How far a downward pull has to go before letting go closes the photo. */
private const val PhotoDismissPx = 220f
/** Darkens the photo's colours around it so the picture stays the brightest thing on screen. */
private const val ViewerVeil = 0.35f
