package com.circaso.feature.messaging.presentation

import com.circaso.designsystem.components.SearchField
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.AvatarBadge
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoSwitch
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.components.LetterHeader
import com.circaso.designsystem.components.ListCaption
import com.circaso.designsystem.components.ListGroup
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.PeopleDividerInset
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.SelectionCheck
import com.circaso.designsystem.components.SkeletonPeopleRows
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.contacts.domain.Contact
import com.circaso.feature.profile.presentation.CameraWords
import com.circaso.feature.profile.presentation.PhotoChoiceSheetContent
import com.circaso.feature.profile.presentation.ProfileCameraLayer

/** What New Group can do; the defaults do nothing (previews). */
class NewGroupActions(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onToggle: (String) -> Unit = {},
    val onNext: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onWalletSwitch: (Boolean) -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onBy: (com.circaso.feature.chama.presentation.GoalDeadline) -> Unit = {},
    val onApprovalPolicy: (com.circaso.feature.chama.domain.ApprovalPolicy) -> Unit = {},
    val onCreate: () -> Unit = {},
    val onSkipPhoto: () -> Unit = {},
    // The photo sheet and camera (the profile photo's pattern).
    val onPhoto: () -> Unit = {},
    val onPhotoDismiss: () -> Unit = {},
    val onUseRecent: (com.circaso.core.platform.LibraryItem) -> Unit = {},
    val onManageRecents: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onChoosePhoto: () -> Unit = {},
    val onRemovePhoto: () -> Unit = {},
    val onShutter: () -> Unit = {},
    val onFlip: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onUseShot: () -> Unit = {},
    val onCloseCamera: () -> Unit = {},
    val onChooseInstead: () -> Unit = {},
)

/**
 * Designs D1 and GW1–GW1d in two steps: the people first (an animated strip of who is in, a searchable list with
 * round checkmarks), then the group itself (its photo, its name with a counter, the "Group wallet" switch when group
 * wallets are on, and the members). Back on the details returns to the people with nothing lost.
 */
@Composable
fun NewGroupScreen(viewModel: NewGroupViewModel, onBack: () -> Unit, onCreated: (chatId: String, plan: com.circaso.feature.chama.presentation.GroupWalletPlan?) -> Unit) {
    val state by viewModel.state.collectAsState()
    val created = { id: String -> onCreated(id, viewModel.walletPlan()) }
    val photos = viewModel.photos
    // The group exists (its photo didn't go up): back is "Continue Without Photo", it opens the group.
    val back = { if (state.photoFailed && !state.creating) viewModel.onSkipPhoto(created) else if (!viewModel.onBack()) onBack() }
    BackOnDetails(state.step == NewGroupStep.Details || state.creating) { back() }
    NewGroupContent(
        state,
        NewGroupActions(
            onBack = back, onQuery = viewModel::onQuery, onToggle = viewModel::onToggle, onNext = viewModel::onNext, onName = viewModel::onName,
            onWalletSwitch = viewModel::onWalletSwitch, onTarget = viewModel::onTarget, onBy = viewModel::onBy, onApprovalPolicy = viewModel::onApprovalPolicy,
            onCreate = { viewModel.onCreate(created) }, onSkipPhoto = { viewModel.onSkipPhoto(created) },
            onPhoto = { photos?.open() }, onPhotoDismiss = { photos?.dismiss() }, onUseRecent = { photos?.useRecent(it) }, onManageRecents = { photos?.manage() },
            onTakePhoto = { photos?.openCamera() }, onChoosePhoto = { photos?.choose() }, onRemovePhoto = viewModel::onRemovePhoto,
            onShutter = { photos?.shutter() }, onFlip = { photos?.flip() }, onRetake = { photos?.retake() }, onUseShot = { photos?.useShot() },
            onCloseCamera = { photos?.closeCamera() }, onChooseInstead = { photos?.chooseInstead() },
        ),
        viewfinder = { m -> photos?.let { com.circaso.core.platform.CameraViewfinder(it.cameraDevice, m) } },
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BackOnDetails(enabled: Boolean, onBack: () -> Unit) = androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)

@Composable
fun NewGroupContent(state: NewGroupUiState, actions: NewGroupActions, viewfinder: @Composable (Modifier) -> Unit = {}) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val details = state.step == NewGroupStep.Details
    val n = state.selected.size
    CircasoScaffold(
        grouped = details,
        topBar = {
            CircasoNavBar(
                title = if (details) "New Group" else "Add Members",
                subtitle = if (details) null else if (n == 0) "Choose who's in" else "$n selected",
                onBack = actions.onBack,
            )
        },
        banner = { ConnectionBanner(showing = if (state.walletOn) "Creating a group and its wallet needs a connection" else "Creating a group needs a connection") },
        bottomBar = { BottomAction(state, actions) },
        overlay = {
            CircasoSheet(visible = state.photoChoice.open, onDismiss = actions.onPhotoDismiss, label = "Group photo") {
                PhotoChoiceSheetContent(
                    state.photoChoice.recents, state.photoChoice.access, hasPhoto = state.photo != null, useAs = "group photo",
                    onUseRecent = actions.onUseRecent, onManage = actions.onManageRecents, onTakePhoto = actions.onTakePhoto,
                    onChoose = actions.onChoosePhoto, onRemove = actions.onRemovePhoto, onCancel = actions.onPhotoDismiss,
                )
            }
            ProfileCameraLayer(
                state.photoChoice.camera, online = true, viewfinder,
                onClose = actions.onCloseCamera, onShutter = actions.onShutter, onFlip = actions.onFlip, onRetake = actions.onRetake,
                onUse = actions.onUseShot, onChooseInstead = actions.onChooseInstead, words = CameraWords.Group,
            )
        },
    ) { padding ->
        val reduce = CircasoTheme.reduceMotion
        AnimatedContent(
            state.step,
            transitionSpec = {
                if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                else {
                    // Forward slides in from the end, back from the start, as a pushed screen does.
                    val forward = targetState == NewGroupStep.Details
                    (slideInHorizontally(tween(CircasoMotion.BaseMs)) { w -> if (forward) w / 3 else -w / 3 } + fadeIn(tween(CircasoMotion.BaseMs))) togetherWith
                        (slideOutHorizontally(tween(CircasoMotion.QuickMs)) { w -> if (forward) -w / 4 else w / 4 } + fadeOut(tween(CircasoMotion.QuickMs)))
                }
            },
            label = "new-group-step",
        ) { step ->
            val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + CircasoSpacing.s4)
            if (step == NewGroupStep.Members) MembersStep(state, actions, bottom) else DetailsStep(state, actions, bottom)
        }
    }
}

/** The bar under both steps: Next with the count, or Create with what it is doing, and the offline reason. */
@Composable
private fun BottomAction(state: NewGroupUiState, actions: NewGroupActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val details = state.step == NewGroupStep.Details
    Column(
        Modifier.fillMaxWidth().background(if (details) c.bgGrouped else c.bg).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        if (!details) {
            val n = state.selected.size
            CircasoButton(if (n == 0) "Next" else "Next · $n", actions.onNext, size = ButtonSize.Large, block = true, enabled = state.canNext)
            return@Column
        }
        val label = when {
            state.stage == CreateStage.Group -> "Creating Group…"
            state.stage == CreateStage.Photo -> "Adding the Photo…"
            state.photoFailed -> "Try the Photo Again"
            state.failed -> "Try Again"
            state.walletOn -> "Create Group and Set Up Wallet"
            else -> "Create Group"
        }
        val enabled = if (state.photoFailed) state.online && !state.creating else state.canCreate
        CircasoButton(label, actions.onCreate, size = ButtonSize.Large, block = true, enabled = enabled, busy = state.creating)
        if (state.photoFailed) CircasoButton("Continue Without Photo", actions.onSkipPhoto, style = ButtonStyle.Plain, size = ButtonSize.Medium, block = true, enabled = !state.creating)
        if (!state.online) BasicText("Needs a connection", style = t.footnote.copy(color = c.inkSecondary))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MembersStep(state: NewGroupUiState, actions: NewGroupActions, padding: PaddingValues) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val haptics = LocalHapticFeedback.current
    val toggle = { id: String -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); actions.onToggle(id) }
    val reduce = CircasoTheme.reduceMotion
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "chosen") {
            AnimatedVisibility(
                state.selectedContacts.isNotEmpty(),
                enter = if (reduce) fadeIn(snap()) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs)),
                exit = if (reduce) fadeOut(snap()) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs)),
            ) {
                ChosenStrip(state.selectedContacts, toggle)
            }
        }
        item(key = "search") {
            SearchField(state.query, actions.onQuery, "Search name or @username", modifier = Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2))
        }
        when {
            !state.loaded -> item(key = "loading") { SkeletonPeopleRows(6) }
            state.contacts.isEmpty() -> item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s8), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("No one to add yet", style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center))
                    BasicText("Groups are made from your contacts on Circaso. Find or invite people from New Chat, then come back.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
                }
            }
            state.shownContacts.isEmpty() -> item(key = "no-match") {
                BasicText(
                    "No one in your contacts matches “${state.query.trim()}”.", Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s8, vertical = CircasoSpacing.s6),
                    style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            }
            else -> {
                val sections = state.shownContacts.groupBy { p -> p.person.displayName.trim().firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#" }
                    .toList().sortedWith(compareBy({ it.first == "#" }, { it.first }))
                sections.forEach { (letter, people) ->
                    stickyHeader(key = "letter-$letter") { LetterHeader(letter) }
                    items(people, key = { it.person.userId }) { contact ->
                        val picked = contact.person.userId in state.selected
                        Column {
                            ListRow(
                                title = contact.person.displayName,
                                sub = contact.person.handle?.let { "@$it" } ?: contact.person.about,
                                leading = { Avatar(contact.person.displayName, imageUrl = contact.person.avatarUrl) },
                                trailing = { SelectionCheck(picked) },
                                onClick = { toggle(contact.person.userId) },
                                modifier = Modifier.semantics {
                                    selected = picked
                                    stateDescription = if (picked) "In the group" else "Not in the group"
                                },
                            )
                            RowDivider(Modifier.padding(start = PeopleDividerInset - CircasoSpacing.s4))
                        }
                    }
                }
            }
        }
    }
}

/** Who is in so far: faces in the order they were picked, each with a small × to take them out again. */
@Composable
private fun ChosenStrip(chosen: List<Contact>, onRemove: (String) -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val reduce = CircasoTheme.reduceMotion
    LazyRow(
        Modifier.fillMaxWidth().padding(top = CircasoSpacing.s2),
        contentPadding = PaddingValues(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        items(chosen, key = { it.person.userId }) { contact ->
            Column(
                Modifier.width(60.dp)
                    .animateItem(fadeInSpec = tween(CircasoMotion.BaseMs), fadeOutSpec = tween(CircasoMotion.QuickMs), placementSpec = if (reduce) null else tween(CircasoMotion.BaseMs))
                    .pressable(role = Role.Button, onClick = { onRemove(contact.person.userId) })
                    .semantics(mergeDescendants = true) { contentDescription = "Remove ${contact.person.displayName}" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box {
                    Avatar(contact.person.displayName, size = 52.dp, imageUrl = contact.person.avatarUrl)
                    Box(
                        Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircasoShapes.circle).background(c.bg).padding(2.dp)
                            .clip(CircasoShapes.circle).background(c.inkTertiary),
                        contentAlignment = Alignment.Center,
                    ) { CircasoIconImage(CircasoIcon.Close, c.bg, Modifier.size(8.dp), strokeWidth = 3f) }
                }
                BasicText(contact.person.displayName.trim().substringBefore(' '), style = t.caption1.copy(color = c.ink, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Step 2. Departure from D1/GW1, decided for the premium bar: the photo is a centred hero (112 dp with "Add Photo")
 * rather than a 64 dp disc beside the name, because the owner asked for the group photo to be obvious; the rest keeps
 * the frames' order (name, members, wallet card).
 */
@Composable
private fun DetailsStep(state: NewGroupUiState, actions: NewGroupActions, padding: PaddingValues) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item(key = "photo") {
            Column(Modifier.fillMaxWidth().padding(top = CircasoSpacing.s5, bottom = CircasoSpacing.s4), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                GroupPhotoButton(
                    name = state.trimmedName, bytes = state.photo?.full, imageUrl = null, busy = state.preparingPhoto,
                    enabled = state.photosAvailable && !state.creating && !state.photoFailed, onClick = actions.onPhoto, available = state.photosAvailable,
                )
                if (state.photosAvailable) {
                    BasicText(
                        if (state.photo == null) "Add Photo" else "Edit Photo",
                        Modifier.clip(CircasoShapes.pill).pressable(enabled = !state.creating && !state.photoFailed, role = Role.Button, onClick = actions.onPhoto)
                            .padding(horizontal = CircasoSpacing.s3, vertical = CircasoSpacing.s1),
                        style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
        item(key = "banners") {
            Column(Modifier.padding(horizontal = CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                if (state.failed) {
                    Banner("The group wasn’t created. Nothing was saved." + (state.reference?.let { " Ref $it" } ?: ""), CircasoIcon.Warning, tone = BannerTone.Critical)
                }
                if (state.photoFailed) {
                    Banner((state.error ?: "The group is made, but its photo didn't upload.") + (state.reference?.let { " Ref $it" } ?: ""), CircasoIcon.Warning, tone = BannerTone.Warning)
                }
                state.error?.takeIf { !state.failed && !state.photoFailed }?.let {
                    BasicText(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = t.footnote.copy(color = c.critical))
                }
            }
        }
        item(key = "name") {
            Column(Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ListGroup { GroupNameField(state.name, actions.onName, enabled = !state.creating && !state.photoFailed) }
                BasicText(
                    // Group invites (2026-11-09): the people chosen are asked, and join when they accept.
                    "The name and photo are end-to-end encrypted: only members and the people you invite can see them. Each person gets an invite and joins when they accept.",
                    Modifier.padding(horizontal = CircasoSpacing.s4), style = t.footnote.copy(color = c.inkSecondary),
                )
            }
        }
        item(key = "members") {
            val people = state.selectedContacts
            ListCaption("${people.size + 1} members")
            LazyRow(contentPadding = PaddingValues(horizontal = CircasoSpacing.s4), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                item(key = "you") { MemberFace("You", null) }
                items(people, key = { it.person.userId }) { MemberFace(it.person.displayName.trim().substringBefore(' '), it.person.avatarUrl, it.person.displayName) }
                item(key = "add") {
                    Column(
                        Modifier.width(56.dp).pressable(enabled = !state.creating && !state.photoFailed, role = Role.Button, onClick = actions.onBack).semantics(mergeDescendants = true) { contentDescription = "Add members" },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(CircasoSizes.avatarMd).clip(CircasoShapes.circle).background(c.fill), contentAlignment = Alignment.Center) {
                            CircasoIconImage(CircasoIcon.Plus, c.ink, Modifier.size(18.dp))
                        }
                        BasicText("Add", style = t.caption1.copy(color = c.inkSecondary))
                    }
                }
            }
        }
        // D1/GW1 order: name, members, then the wallet card.
        if (state.walletsAvailable) {
            item(key = "wallet") { GroupWalletCard(state, actions, Modifier.padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s4)) }
        }
    }
}

@Composable
private fun MemberFace(label: String, avatarUrl: String?, fullName: String = label) {
    val t = CircasoTheme.typography
    Column(Modifier.width(56.dp).semantics(mergeDescendants = true) { contentDescription = fullName }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Avatar(fullName, imageUrl = avatarUrl)
        BasicText(label, style = t.caption1.copy(color = CircasoTheme.colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The group name as an inset row (Settings-style) with a live counter that warns as it nears the limit. */
@Composable
private fun GroupNameField(value: String, onValue: (String) -> Unit, enabled: Boolean) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val max = NewGroupUiState.MaxGroupName
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value, onValueChange = com.circaso.designsystem.components.ashaAware(value, onValue), singleLine = true, enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Group name" },
            )
            if (value.isEmpty()) BasicText("Group name", style = t.body.copy(color = c.inkTertiary))
        }
        val left = max - value.length
        BasicText(
            "${value.length}/$max",
            Modifier.semantics { contentDescription = "$left characters left" },
            style = t.footnote.copy(color = if (left <= 8) c.warning else c.inkTertiary, fontFeatureSettings = "tnum"),
        )
    }
}

/**
 * The large round photo control: the photo when there is one, else a soft disc with a camera; a camera badge on its
 * corner says it can be changed, a spinner covers it while the photo is being prepared or sent.
 */
@Composable
internal fun GroupPhotoButton(name: String, bytes: ByteArray?, imageUrl: String?, busy: Boolean, enabled: Boolean, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = CircasoSizes.avatarHero, available: Boolean = true) {
    val c = CircasoTheme.colors
    val has = bytes != null || imageUrl != null
    Box(
        Modifier.pressable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (has) "Change group photo" else "Add group photo" },
    ) {
        if (has || !available) Avatar(name.ifBlank { "Group" }, size = size, imageUrl = imageUrl, bytes = bytes)
        else Box(Modifier.size(size).clip(CircasoShapes.circle).background(c.fill).border(1.dp, c.separator, CircasoShapes.circle), contentAlignment = Alignment.Center) {
            CircasoIconImage(CircasoIcon.Camera, c.inkSecondary, Modifier.size(size * 0.3f), strokeWidth = 1.6f)
        }
        if (busy) {
            Box(Modifier.matchParentSize().clip(CircasoShapes.circle).background(c.scrim), contentAlignment = Alignment.Center) { Spinner(c.onAction, Modifier.size(24.dp)) }
        }
        if (enabled && has) AvatarBadge(CircasoIcon.Camera, Modifier.align(Alignment.BottomEnd), size = 34.dp)
    }
}

/**
 * GW1's card: the switch, and when it is on the optional target, who approves payouts, and the trust line that says
 * plainly who can move the money and after whose approval.
 */
@Composable
private fun GroupWalletCard(state: NewGroupUiState, actions: NewGroupActions, modifier: Modifier = Modifier) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        // GW1: radius-md, surface, a hairline separator edge.
        modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).border(1.dp, c.separator, CircasoShapes.md)
            .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        val reduce = CircasoTheme.reduceMotion
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Column(Modifier.weight(1f)) {
                BasicText("Group wallet", style = t.headline.copy(color = c.ink))
                BasicText(
                    if (state.walletOn) "Members pay into it. It’s separate from everyone’s personal money, with its own PIN."
                    else "For chamas, savings and small businesses. You can add it later from group info.",
                    style = t.footnote.copy(color = c.inkSecondary),
                )
            }
            CircasoSwitch(state.walletOn, actions.onWalletSwitch, enabled = state.online && !state.creating && !state.photoFailed, modifier = Modifier.semantics { contentDescription = "Group wallet" })
        }
        AnimatedVisibility(
            state.walletOn,
            enter = if (reduce) fadeIn(snap()) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs)),
            exit = if (reduce) fadeOut(snap()) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                CircasoTextField(
                    value = state.targetText, onValueChange = actions.onTarget, label = "Target (optional)",
                    placeholder = if (state.fiat != null) "50,000" else "400",
                    leading = { BasicText(state.fiat?.currency ?: "USDC", style = t.body.copy(color = c.inkSecondary)) },
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                )
                if (state.targetText.isNotBlank()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("By (optional)", style = t.footnote.copy(color = c.inkSecondary))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                            items(com.circaso.feature.chama.presentation.GoalDeadline.entries) { d ->
                                com.circaso.designsystem.components.SuggestionChip(d.label, { actions.onBy(d) }, selected = d == state.by)
                            }
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText("Who approves payouts", style = t.footnote.copy(color = c.inkSecondary))
                    val policies = com.circaso.feature.chama.domain.ApprovalPolicy.entries
                    com.circaso.designsystem.components.SegmentedControl(listOf("2 admins", "Any admin"), policies.indexOf(state.approvalPolicy), { actions.onApprovalPolicy(policies[it]) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircasoIconImage(CircasoIcon.Lock, c.inkSecondary, Modifier.padding(top = 2.dp).size(14.dp))
                    BasicText(
                        if (state.approvalPolicy == com.circaso.feature.chama.domain.ApprovalPolicy.AnyAdmin)
                            "Only you can send from this wallet, and only after an admin approves each payout (an admin’s own request counts). Everyone sees every payment. A daily limit caps what can leave it in 24 hours; you'll see it once the wallet is set up."
                        else "Only you can send from this wallet, and only after 2 admins approve each payout (the admin who asks counts as one). Everyone sees every payment." +
                            " A daily limit caps what can leave it in 24 hours; you'll see it once the wallet is set up.",
                        style = t.footnote.copy(color = c.inkSecondary),
                    )
                }
            }
        }
    }
}
