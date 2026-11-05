package com.circaso.feature.spaces.presentation.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.chatListTime
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CountBadge
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.spaces.domain.MySpace
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.SpaceState
import com.circaso.feature.spaces.domain.SpaceSummary
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.Suggestion
import com.circaso.feature.spaces.domain.SuggestionReason
import com.circaso.feature.spaces.presentation.SkeletonBlock
import com.circaso.feature.spaces.presentation.SpaceTile
import com.circaso.feature.spaces.presentation.countText
import com.circaso.feature.spaces.presentation.priceRowText
import com.circaso.feature.spaces.presentation.shortDate
import com.circaso.feature.wallet.domain.FiatValue

/** What the spaces sections of the Spaces tab can do. */
class SpacesHomeActions(
    val onOpen: (MySpace) -> Unit,
    val onOpenSuggestion: (Suggestion) -> Unit,
    val onFollow: (spaceId: String) -> Unit,
    val onJoin: (spaceId: String) -> Unit,
    val onSeeAllSuggested: () -> Unit,
    val onCreate: () -> Unit,
    val onRetry: () -> Unit,
)

/** How many of my spaces show before "See All" opens the rest in place (a gap: the frame lists three). */
private const val MineShown = 5

/**
 * Design SD1 (with SD1b–SD1e): the spaces sections under the Moments row, replacing phase 1's "Spaces are coming" card.
 * Your spaces first (from the phone), then Suggested for you (from the server; offline it says so instead of showing
 * stale suggestions).
 */
fun LazyListScope.spacesSections(state: SpacesHomeUiState, actions: SpacesHomeActions) {
    item(key = "spaces-mine-head") {
        var all by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(top = 14.dp)) {
            SectionHead("Your spaces", link = if (state.mine.size > MineShown && !all) "See All" else null, onLink = { all = true }, top = 0.dp)
            when {
                state.loading -> repeat(2) { SkeletonRow() }
                state.noSpaces -> FindYourPeople(actions.onCreate)
                else -> (if (all) state.mine else state.mine.take(MineShown)).forEach { m -> MySpaceRow(m, state.now, state.fiat) { actions.onOpen(m) } }
            }
        }
    }
    item(key = "spaces-suggested-head") {
        SectionHead("Suggested for you", link = if (state.online && state.shownSuggestions.isNotEmpty()) "See All" else null, onLink = actions.onSeeAllSuggested)
    }
    when {
        !state.online -> item(key = "spaces-suggested-offline") {
            BasicText(
                "Suggestions show when you’re back online.", Modifier.padding(horizontal = CircasoSpacing.s5, vertical = CircasoSpacing.s2),
                style = CircasoTheme.typography.subhead.copy(color = CircasoTheme.colors.inkSecondary),
            )
        }
        state.suggestionsFailed -> item(key = "spaces-suggested-error") {
            ErrorBox("Suggestions didn’t load", "Something went wrong on our side.", state.requestId, actions.onRetry)
        }
        !state.suggestionsLoaded -> items(3) { SkeletonRow() }
        else -> items(state.shownSuggestions.take(6), key = { "sugg-" + it.space.spaceId }) { s ->
            SuggestionRow(s, state.follow[s.space.spaceId] ?: FollowState.Idle, state.fiat, state.online,
                onOpen = { actions.onOpenSuggestion(s) }, onFollow = { actions.onFollow(s.space.spaceId) }, onJoin = { actions.onJoin(s.space.spaceId) })
        }
    }
}

/** The canvas's `sec-row`: a quiet label and an optional link on the right. */
@Composable
internal fun SectionHead(text: String, link: String? = null, onLink: () -> Unit = {}, top: androidx.compose.ui.unit.Dp = CircasoSpacing.s3) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().padding(start = CircasoSpacing.s5, end = CircasoSpacing.s5, top = top, bottom = CircasoSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text, Modifier.weight(1f).semantics { heading() }, style = t.footnote.copy(color = c.inkSecondary))
        if (link != null) BasicText(link, Modifier.pressable(onClick = onLink).padding(vertical = 6.dp), style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
    }
}

/** A space row (canvas `sp_row`): the 52 dp tile, the name with an optional badge, one sub line, the end column. */
@Composable
internal fun SpaceListRow(
    space: SpaceSummary,
    sub: AnnotatedString,
    label: String,
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null,
    end: @Composable () -> Unit = {},
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label }
            .defaultMinSize(minHeight = 72.dp).padding(horizontal = CircasoSpacing.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        SpaceTile(space, 52.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                BasicText(space.name, Modifier.weight(1f, fill = false), style = t.body.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                badge?.invoke()
            }
            BasicText(sub, style = t.subhead.copy(color = c.inkSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        end()
    }
}

/** SD1: one of my spaces. Unread counts as in Chats; Renew in amber when the membership ends soon. */
@Composable
internal fun MySpaceRow(m: MySpace, now: Long, fiat: FiatValue?, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val renew = m.me.renewOpen(now)
    val ending = m.me.membership?.let { it.paidUntil ?: it.graceUntil }
    val last = m.lastPost?.let { p ->
        when {
            p.removed -> "${p.authorName}: Removed post"
            p.text.isNullOrBlank() -> "${p.authorName}: " + when (p.kind) { "IMAGE" -> "Photo"; "VIDEO" -> "Video"; "VOICE" -> "Voice note"; else -> "Post" }
            else -> "${p.authorName}: ${p.text}"
        }
    }
    val sub = buildList {
        when {
            m.space.state == SpaceState.SettingUp -> add("Setting up · only you can see it")
            m.me.relation == Relation.Ended -> add("Membership ended · Rejoin")
            renew && ending != null -> add("Renew by ${shortDate(ending)}")
        }
        add(last ?: m.space.about?.takeIf { it.isNotBlank() } ?: kindSub(m.space, fiat))
    }.joinToString(" · ")
    val label = listOfNotNull(m.space.name, if (renew) "renew soon" else null, sub, if (m.unreadCount > 0) "${m.unreadCount} unread" else null).joinToString(", ")
    SpaceListRow(
        m.space, AnnotatedString(sub), label, onClick,
        badge = if (renew) ({ RenewBadge() }) else null,
        end = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.activityAt > 0) BasicText(chatListTime(m.activityAt, now), style = t.footnote.copy(color = c.inkSecondary))
                if (m.unreadCount > 0) CountBadge(m.unreadCount)
            }
        },
    )
}

/** The amber Renew badge (canvas `cx-badge-warning` with a clock): the only warning colour on the tab. */
@Composable
internal fun RenewBadge() {
    val c = CircasoTheme.colors
    Row(
        Modifier.clip(CircasoShapes.pill).background(c.warning.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CircasoIconImage(CircasoIcon.Clock, c.warning, Modifier.size(13.dp))
        BasicText("Renew", style = CircasoTheme.typography.caption1.copy(color = c.warning, fontWeight = FontWeight.SemiBold))
    }
}

/** "Public · Farming · 4.8k" or "Members only · 1 USDC (KES 129) a month". */
internal fun kindSub(space: SpaceSummary, fiat: FiatValue?): String = when {
    space.visibility == SpaceVisibility.MembersOnly && space.price != null -> "Members only · ${priceRowText(space.price, fiat)}"
    else -> "${space.visibility.label} · ${space.topic.label} · ${countText(space.memberCount)}"
}

/** Why a space is suggested (SD3): a contact follows it, or it is popular near you. */
internal fun reasonText(s: Suggestion): String? = when (s.reason) {
    SuggestionReason.Contacts -> s.known.phrase()?.let { p -> if (s.known.count == 1 && s.known.names.size == 1) "$p follows" else if (s.known.names.isEmpty()) "${s.known.count} contacts" else "$p follow" }
        ?: "${s.known.count} contacts".takeIf { s.known.count > 0 }
    SuggestionReason.PopularInCountry -> "Popular in ${countryName()}"
    SuggestionReason.Popular -> null
}

/** The phone's country as the frames say it ("Popular in Kenya"); the region's currency is all the phone tells us. */
private fun countryName(): String = when (com.circaso.core.platform.localCurrencyCode()) {
    "KES" -> "Kenya"; "UGX" -> "Uganda"; "TZS" -> "Tanzania"; "RWF" -> "Rwanda"; "NGN" -> "Nigeria"; "GHS" -> "Ghana"; "ZAR" -> "South Africa"
    else -> "your country"
}

/** SD1/SD3: a suggested space with Follow (public), Join (members only, opens SM1) or Following in place. */
@Composable
internal fun SuggestionRow(s: Suggestion, follow: FollowState, fiat: FiatValue?, online: Boolean, onOpen: () -> Unit, onFollow: () -> Unit, onJoin: () -> Unit) {
    val sub = listOfNotNull(kindSub(s.space, fiat), reasonText(s).takeIf { s.space.visibility == SpaceVisibility.Public }).joinToString(" · ")
    SpaceListRow(s.space, AnnotatedString(sub), "${s.space.name}, $sub", onOpen, end = { FollowButton(s.space, follow, online, onFollow, onJoin) })
}

/** Follow / Following / Join for a space row; never offered offline (SD1d), disabled with the reason. */
@Composable
internal fun FollowButton(space: SpaceSummary, follow: FollowState, online: Boolean, onFollow: () -> Unit, onJoin: () -> Unit) {
    when {
        follow == FollowState.Following -> CircasoButton("Following", {}, style = ButtonStyle.Plain, size = ButtonSize.Small, icon = CircasoIcon.Check,
            modifier = Modifier.semantics { contentDescription = "Following ${space.name}" })
        space.visibility == SpaceVisibility.MembersOnly -> CircasoButton("Join", onJoin, style = ButtonStyle.Secondary, size = ButtonSize.Small,
            modifier = Modifier.semantics { contentDescription = "Join ${space.name}" })
        else -> CircasoButton("Follow", onFollow, style = ButtonStyle.Secondary, size = ButtonSize.Small, enabled = online, busy = follow == FollowState.Busy,
            modifier = Modifier.semantics { contentDescription = if (online) "Follow ${space.name}" else "Follow ${space.name}, needs a connection" })
    }
}

/** SD1b: not in any space yet. Create a Space opens SN1 (the same as + → Space). */
@Composable
private fun FindYourPeople(onCreate: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.padding(horizontal = CircasoSpacing.s4, vertical = 6.dp).fillMaxWidth().clip(CircasoShapes.md).background(c.fill).padding(CircasoSpacing.s4),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText("Find your people", Modifier.semantics { heading() }, style = t.headline.copy(color = c.ink))
        BasicText("Follow a public space, join a members-only one, or start your own for your shop, church, chama or club.", style = t.subhead.copy(color = c.inkSecondary))
        CircasoButton("Create a Space", onCreate, size = ButtonSize.Small)
    }
}

/** SD1c: a row's shape in skeletons, no spinner. */
@Composable
internal fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = 10.dp).semantics { contentDescription = "Loading spaces" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        SkeletonBlock(52.dp, 52.dp, shape = CircasoShapes.md)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
            Box(Modifier.fillMaxWidth(0.6f)) { SkeletonBlock(400.dp, 14.dp) }
            Box(Modifier.fillMaxWidth(0.85f)) { SkeletonBlock(400.dp, 12.dp) }
        }
    }
}

/** The canvas's `err_box`: plain words, Try Again, the reference. */
@Composable
internal fun ErrorBox(title: String, text: String, requestId: String?, onRetry: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp).semantics(mergeDescendants = false) { },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircasoIconImage(CircasoIcon.Warning, c.inkSecondary, Modifier.size(32.dp), strokeWidth = 1.8f)
        BasicText(title, style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(text, style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        CircasoButton("Try Again", onRetry, style = ButtonStyle.Secondary, size = ButtonSize.Small)
        requestId?.let { BasicText("Ref $it", style = t.caption2.copy(color = c.inkTertiary)) }
    }
}
