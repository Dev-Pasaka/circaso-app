package com.circaso.feature.share.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.circaso.core.platform.shareText
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoSwitch
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.share.domain.LinkEnds
import com.circaso.feature.share.domain.ShareKind
import com.circaso.feature.share.domain.ShareTarget

/**
 * How a screen shares: `LocalShareLinks.current?.share(ShareTarget.Receipt(…))`. Provided once over the whole app by
 * the navigation (null in previews and while signed out, where nothing can be shared).
 */
fun interface ShareLinkLauncher {
    fun share(target: ShareTarget)
}

val LocalShareLinks = staticCompositionLocalOf<ShareLinkLauncher?> { null }

/**
 * What a share button needs to look right before and during the tap: [busy] is the kind whose link is being made
 * without a sheet (pay me, an invite), so its button can spin; [online] disables buttons that would only fail.
 */
data class ShareLinksStatus(val busy: ShareKind? = null, val online: Boolean = true)

val LocalShareLinksStatus = androidx.compose.runtime.compositionLocalOf { ShareLinksStatus() }

/**
 * The app-wide share layer: the options sheet over everything, the system share sheet once the link exists, and a
 * toast (through [onNotice]) when a share without a sheet failed. Lives in the navigation's top box.
 */
@Composable
fun BoxScope.ShareLinkLayer(viewModel: ShareLinkViewModel, onNotice: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.share) { state.share?.let { shareText(it.text, it.subject); viewModel.onShared() } }
    LaunchedEffect(state.notice) { state.notice?.let { onNotice(it); viewModel.onNoticeShown() } }
    ShareLinkSheet(
        state = state,
        onShowNames = viewModel::onShowNames,
        onEnds = viewModel::onEnds,
        onConfirm = viewModel::onConfirm,
        onDismiss = viewModel::onDismiss,
    )
}

/** The options before a receipt, statement or address link is made: what the page shows, names, when it ends. */
@Composable
fun BoxScope.ShareLinkSheet(
    state: ShareLinkUiState,
    onShowNames: (Boolean) -> Unit,
    onEnds: (LinkEnds) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val target = state.target
    val title = when (target?.kind) {
        ShareKind.Receipt -> "Share receipt"
        ShareKind.Statement -> "Share statement"
        ShareKind.Address -> "Share address"
        else -> "Share link"
    }
    CircasoSheet(visible = state.sheetOpen && target != null, onDismiss = onDismiss, label = title) {
        SheetHead(title, onDismiss)
        if (target != null) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
                BasicText(target.preview, style = t.headline.copy(color = c.ink))
                BasicText(
                    "Anyone with the link sees a Circaso page with this, and nothing else. You can turn it off any time from You → Shared links.",
                    style = t.subhead.copy(color = c.inkSecondary),
                )
            }
            ListGroup {
                val namesSub = when (target.kind) {
                    ShareKind.Statement -> "Members' names and the notes on payouts appear on the page"
                    ShareKind.Address -> "Your name appears above the address"
                    else -> "Who paid whom appears on the page"
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3)
                        .toggleable(value = state.showNames, enabled = !state.creating, role = Role.Switch, onValueChange = onShowNames),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
                ) {
                    Column(Modifier.weight(1f)) {
                        BasicText("Show names", style = t.body.copy(color = c.ink))
                        BasicText(if (state.showNames) namesSub else "Names are left off the page", style = t.subhead.copy(color = c.inkSecondary))
                    }
                    CircasoSwitch(state.showNames, onShowNames, enabled = !state.creating)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                BasicText("Link ends", style = t.subhead.copy(color = c.inkSecondary))
                SegmentedControl(
                    options = LinkEnds.entries.map { it.label },
                    selected = state.ends.ordinal,
                    onSelect = { i -> if (!state.creating) onEnds(LinkEnds.entries[i]) },
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                state.error?.let {
                    BasicText(it, style = t.footnote.copy(color = c.critical), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                if (!state.online && state.error == null) {
                    BasicText("You're offline. Making a link needs a connection.", style = t.footnote.copy(color = c.inkSecondary))
                }
                CircasoButton("Share Link", onConfirm, size = ButtonSize.Large, block = true, busy = state.creating, enabled = state.online)
            }
        }
    }
}
