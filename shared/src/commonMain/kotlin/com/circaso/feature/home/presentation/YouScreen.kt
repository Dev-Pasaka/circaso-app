package com.circaso.feature.home.presentation

import androidx.compose.foundation.layout.size
import com.circaso.designsystem.components.CircasoIcon
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
import coil3.request.crossfade
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.circaso.core.common.maskedForDisplay
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.ConfirmSheet
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
    val onAsha: () -> Unit,
    val onSecretMenu: () -> Unit,
    val onRetry: (FailedChange) -> Unit = {},
    val onDiscard: (FailedChange) -> Unit = {},
    val onNotifications: () -> Unit = {},
    val onHelp: () -> Unit = {},
    val onNeedsAttention: () -> Unit = {},
    val onCurrency: () -> Unit = {},
    val onAshaVoice: () -> Unit = {},
    val onMoneySound: (Boolean) -> Unit = {},
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
    CircasoScaffold(
        grouped = true,
        // No title on the tabs (Pasaka, 2026-10-30): the tab bar says where you are.
        banner = { ConnectionBanner(showing = "Showing what is saved on this phone") },
        overlay = {
            ConfirmSheet(
                visible = state.confirmSignOut,
                title = "Sign out of Circaso?",
                message = "You can sign back in with Google or Apple. Your chats and money stay safe, and this phone stays yours. Anything not yet sent from this phone is removed.",
                confirmLabel = "Sign Out",
                onConfirm = onSignOutConfirmed,
                onDismiss = onSignOutCancelled,
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = CircasoSpacing.s4)
                .padding(top = CircasoSpacing.s2, bottom = CircasoSpacing.s6 + bottomPadding.calculateBottomPadding() + padding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
        ) {
            ProfileHero(
                name = state.profile?.let { it.displayName.ifBlank { "Your profile" } },
                handle = state.profile?.handle,
                phone = state.maskedPhone?.maskedForDisplay(),
                photo = state.profile?.avatarUrl,
                waiting = state.profile?.pendingSync == true,
                onEdit = actions.onEditProfile,
            )
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
                ListRow("Notifications", trailing = { RowChevron() }, onClick = actions.onNotifications)
            }
            Section("Money") {
                ListRow("Currency", sub = "How amounts are shown in your wallet and chats", trailing = { RowChevron() }, onClick = actions.onCurrency)
                RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                ListRow(
                    "Money sound", sub = "Coins dropping into your wallet when money arrives",
                    trailing = { com.circaso.designsystem.components.CircasoSwitch(state.moneySound, actions.onMoneySound) },
                    onClick = { actions.onMoneySound(!state.moneySound) },
                )
            }
            Section("Assistant") {
                ListRow("Asha", sub = "Ask about your money by text or voice", trailing = { RowChevron() }, onClick = actions.onAsha)
                RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                ListRow("Asha's voice", sub = "Pick how she sounds", trailing = { RowChevron() }, onClick = actions.onAshaVoice)
                RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                ListRow("Help", sub = "Chat with a person at Circaso", trailing = { RowChevron() }, onClick = actions.onHelp)
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
 * The You tab's top: the photo large and centred, glowing in its own colours (Pasaka, 2026-10-31, in place of F2's
 * grouped profile row). The glow is the same picture decoded tiny and stretched, so it is a soft wash of its colours
 * on every Android version (a blur effect alone needs Android 12); it rises out of the page and fades back into it.
 * Without a photo, or in high contrast, the wash is a quiet grey.
 */
@Composable
private fun ProfileHero(name: String?, handle: String?, phone: String?, photo: String?, waiting: Boolean, onEdit: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val page = c.bgGrouped
    Box(
        // Reaches the screen edges (the column has side padding) so the glow isn't boxed in.
        Modifier.layout { measurable, constraints ->
            val bleed = (HeroBleed * 2).roundToPx()
            val wide = constraints.maxWidth + bleed
            val placeable = measurable.measure(constraints.copy(minWidth = wide, maxWidth = wide))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed / 2, 0) }
        }.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.matchParentSize().clipToBounds()) {
            if (photo != null && !c.isHighContrast) {
                val key = photo.substringBefore('?')
                coil3.compose.AsyncImage(
                    model = coil3.request.ImageRequest.Builder(coil3.compose.LocalPlatformContext.current).data(photo)
                        .size(GlowDecodePx).memoryCacheKey("$key#glow").diskCacheKey(key).crossfade(true).build(),
                    contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(GlowBlur).graphicsLayer { alpha = GlowAlpha },
                )
            } else {
                Box(
                    Modifier.fillMaxSize().drawBehind {
                        val avatarCentre = CircasoSpacing.s6.toPx() + CircasoSizes.avatarHero.toPx() / 2
                        drawRect(androidx.compose.ui.graphics.Brush.radialGradient(listOf(c.fillStrong, page), center = androidx.compose.ui.geometry.Offset(size.width / 2, avatarCentre), radius = size.width * 0.75f))
                    },
                )
            }
            // Soft at the very top so the colour rises out of the page, strongest around the photo, gone by the
            // bottom; dense enough behind the name for its text to keep its contrast.
            Box(
                Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to page.copy(alpha = 0.6f), 0.18f to page.copy(alpha = 0.1f), 0.38f to page.copy(alpha = 0.55f),
                        0.6f to page.copy(alpha = 0.9f), 1f to page,
                    ),
                ),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(top = CircasoSpacing.s6, bottom = CircasoSpacing.s5, start = CircasoSpacing.s4, end = CircasoSpacing.s4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
        ) {
            if (name == null) {
                // Loading: shapes only, so "Your profile" never flashes before the real name.
                Box(Modifier.size(CircasoSizes.avatarHero).clip(CircasoShapes.circle).background(c.fillStrong))
                Box(Modifier.padding(top = CircasoSpacing.s1).size(160.dp, 24.dp).clip(CircasoShapes.sm).background(c.fill))
                Box(Modifier.size(120.dp, 16.dp).clip(CircasoShapes.sm).background(c.fill))
            } else {
            // The ring in the page colour cuts the photo out of its own glow.
            Avatar(name, size = CircasoSizes.avatarHero, imageUrl = photo, modifier = Modifier.shadow(AvatarShadow, CircasoShapes.circle).border(AvatarRing, page, CircasoShapes.circle))
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
}

/** Matches the scrolling column's side padding, which the hero reaches past. */
private val HeroBleed = CircasoSpacing.s4
/** Decoded this small, the picture stretches into a soft wash of its colours; cheap to load and to draw. */
private const val GlowDecodePx = 24
/** Extra smoothing where blur is supported (Android 12+, iOS); harmless where it isn't. */
private val GlowBlur = 24.dp
private const val GlowAlpha = 0.8f
private val AvatarShadow = 18.dp
private val AvatarRing = 3.dp
