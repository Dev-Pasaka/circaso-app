package com.circaso.feature.profile.presentation

import com.circaso.designsystem.chrome.ConfirmSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.background
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.ListGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.circaso.core.sync.ConnectionBanner
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

@Composable
fun EditProfileScreen(viewModel: EditProfileViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    EditProfileContent(
        state, onBack, viewModel::onName, viewModel::onAbout, viewModel::onSave, viewModel::onHandle, viewModel::onClaimHandle, viewModel::onChangePhoto, viewModel::onRemovePhoto,
        onSaveAll = viewModel::onSaveAll, onMessageShown = viewModel::onMessageShown,
        photo = PhotoActions(
            onSheetShown = viewModel::onPhotoSheetShown, onTakePhoto = viewModel::onOpenCamera, onUseRecent = viewModel::onUseRecent,
            onManageRecents = viewModel::onManageRecents, onShutter = viewModel::onShutter, onFlip = viewModel::onFlipCamera,
            onRetake = viewModel::onRetake, onUsePhoto = viewModel::onUsePhoto, onCloseCamera = viewModel::onCloseCamera,
        ),
        viewfinder = { m -> com.circaso.core.platform.CameraViewfinder(viewModel.cameraDevice, m) },
    )
}

/** What the photo sheet and the profile camera do; the defaults do nothing (previews). */
class PhotoActions(
    val onSheetShown: () -> Unit = {},
    val onTakePhoto: () -> Unit = {},
    val onUseRecent: (com.circaso.core.platform.LibraryItem) -> Unit = {},
    val onManageRecents: () -> Unit = {},
    val onShutter: () -> Unit = {},
    val onFlip: () -> Unit = {},
    val onRetake: () -> Unit = {},
    val onUsePhoto: () -> Unit = {},
    val onCloseCamera: () -> Unit = {},
)

/** Profile editing. Stateless so it can be previewed. */
@Composable
fun EditProfileContent(
    state: EditProfileUiState,
    onBack: () -> Unit,
    onName: (String) -> Unit,
    onAbout: (String) -> Unit,
    onSave: () -> Unit,
    onHandle: (String) -> Unit,
    onClaimHandle: () -> Unit,
    onChangePhoto: () -> Unit = {},
    onRemovePhoto: () -> Unit = {},
    onSaveAll: (() -> Unit)? = null,
    onMessageShown: () -> Unit = {},
    photo: PhotoActions = PhotoActions(),
    viewfinder: @Composable (Modifier) -> Unit = {},
    /** Previews only: draw the photo sheet open. */
    photoSheetOpen: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var photoChoices by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(photoSheetOpen) }
    // The strip is filled (and the library permission asked, the first time) when the sheet opens, never before.
    LaunchedEffect(photoChoices) { if (photoChoices) photo.onSheetShown() }
    var confirmDiscard by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val handleChanged = state.handle.isNotBlank() && state.handle != state.profile?.handle
    // Leaving with edits that weren't saved asks first, rather than dropping them silently.
    val dirty = state.canSave || handleChanged
    val leave = { if (dirty) confirmDiscard = true else onBack() }
    BackHandlerFor(dirty) { confirmDiscard = true }
    val toast = com.circaso.designsystem.chrome.rememberToastState()
    LaunchedEffect(state.message) { state.message?.let { toast.show(it); onMessageShown() } }
    // One Save for the whole page: name and about, and the handle when it changed and is free.
    val canSaveAll = state.canSave || (handleChanged && state.canClaim)
    CircasoScaffold(
        grouped = true,
        topBar = {
            CircasoNavBar(title = "Profile", onBack = leave, trailing = {
                BasicText(
                    "Save",
                    style = t.body.copy(color = if (canSaveAll) c.action else c.inkTertiary, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    modifier = Modifier.pressable(enabled = canSaveAll && !state.claiming, role = androidx.compose.ui.semantics.Role.Button, onClick = {
                        if (onSaveAll != null) onSaveAll()
                        else {
                            if (state.canSave) onSave()
                            if (handleChanged && state.canClaim) onClaimHandle()
                        }
                    }).minimumInteractiveComponentSize().padding(CircasoSpacing.s2),
                )
            })
        },
        banner = { ConnectionBanner() },
        toast = { com.circaso.designsystem.chrome.ToastHost(toast, Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) },
        overlay = {
            ConfirmSheet(
                visible = confirmDiscard, title = "Discard changes?", message = "What you changed here hasn't been saved.",
                confirmLabel = "Discard", destructive = true, cancelLabel = "Keep Editing",
                onConfirm = { confirmDiscard = false; onBack() }, onDismiss = { confirmDiscard = false },
            )
            CircasoSheet(visible = photoChoices, onDismiss = { photoChoices = false }, label = "Profile photo") {
                PhotoSheetContent(
                    state,
                    onUseRecent = { item -> photoChoices = false; photo.onUseRecent(item) },
                    onManage = photo.onManageRecents,
                    onTakePhoto = { photoChoices = false; photo.onTakePhoto() },
                    onChoose = { photoChoices = false; onChangePhoto() },
                    onRemove = { photoChoices = false; onRemovePhoto() },
                    onCancel = { photoChoices = false },
                )
            }
            ProfileCameraLayer(
                state.camera, state.online, viewfinder,
                onClose = photo.onCloseCamera, onShutter = photo.onShutter, onFlip = photo.onFlip, onRetake = photo.onRetake, onUse = photo.onUsePhoto,
                onChooseInstead = { photo.onCloseCamera(); onChangePhoto() },
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = CircasoSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s6),
        ) {
            // The photo with a camera badge at its corner, as on any phone: no words needed.
            Box(Modifier.align(Alignment.CenterHorizontally)) {
                val hasPhoto = state.profile?.avatarUrl != null
                Box(
                    Modifier.pressable(enabled = state.canChangeAvatar, role = androidx.compose.ui.semantics.Role.Button, onClick = { photoChoices = true })
                        .semantics { contentDescription = if (hasPhoto) "Change profile photo" else "Add profile photo" },
                ) {
                    Avatar(state.name.ifBlank { "?" }, size = 120.dp, imageUrl = state.profile?.avatarUrl)
                    if (state.avatarBusy) {
                        Box(Modifier.matchParentSize().clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.scrim), contentAlignment = Alignment.Center) {
                            com.circaso.designsystem.components.Spinner(c.onAction, Modifier.size(24.dp))
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomEnd).size(36.dp).clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(c.bgGrouped).padding(3.dp)
                        .clip(com.circaso.designsystem.theme.CircasoShapes.circle).background(if (state.canChangeAvatar) c.action else c.fillStrong),
                    contentAlignment = Alignment.Center,
                ) { CircasoIconImage(CircasoIcon.Camera, c.onAction, Modifier.size(16.dp)) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                ListGroup {
                    InlineField("Name", state.name, onName, "Your name", capitalize = true)
                    RowDivider()
                    InlineField("About", state.about, onAbout, "A line about you", singleLine = false)
                }
                if (state.profile?.pendingSync == true) Footnote("Waiting to send. Your changes are saved on this phone.", c.warning)
            }

            Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                Footnote("USERNAME", c.inkSecondary)
                ListGroup {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = CircasoSpacing.s4), verticalAlignment = Alignment.CenterVertically) {
                        BasicText("@", style = t.body.copy(color = c.inkSecondary))
                        Box(Modifier.weight(1f).padding(start = 2.dp), contentAlignment = Alignment.CenterStart) {
                            androidx.compose.foundation.text.BasicTextField(
                                value = state.handle, onValueChange = onHandle, singleLine = true, enabled = state.handleLockedUntil == null,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None, keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii),
                                textStyle = t.body.copy(color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink), modifier = Modifier.fillMaxWidth(),
                            )
                            if (state.handle.isEmpty()) BasicText("yourname", style = t.body.copy(color = c.inkTertiary))
                        }
                        // Green for free, red for taken, as the person types.
                        when (state.handleCheck) {
                            HandleCheck.Checking -> com.circaso.designsystem.components.Spinner(c.inkSecondary, Modifier.size(18.dp))
                            HandleCheck.Available -> if (handleChanged) CircasoIconImage(CircasoIcon.Check, HandleGreen, Modifier.size(20.dp))
                            is HandleCheck.Taken, HandleCheck.Invalid -> CircasoIconImage(CircasoIcon.Close, c.critical, Modifier.size(18.dp))
                            else -> Unit
                        }
                    }
                }
                val (note, tone) = when (val check = state.handleCheck) {
                    HandleCheck.Available -> (if (handleChanged) "@${state.handle} is available" else "This is your username") to (if (handleChanged) HandleGreen else c.inkSecondary)
                    is HandleCheck.Taken -> (check.suggestion?.let { "@${state.handle} is taken. Try @$it" } ?: "@${state.handle} is taken") to c.critical
                    HandleCheck.Invalid -> "3 or more letters, numbers, dots or underscores" to c.critical
                    HandleCheck.Unavailable -> (if (state.online) "Couldn't check just now" else "Checking needs a connection") to c.inkSecondary
                    is HandleCheck.TooManyChecks -> "Lots of checks. Try again in a little while" to c.inkSecondary
                    else -> (state.handleLockedUntil?.let { "You can change it again after ${it.substringBefore('T')}" } ?: "People can find and pay you by your username") to c.inkSecondary
                }
                Footnote(note, tone)
            }

        }
    }
}

/**
 * The photo sheet: the newest photos on the phone in a strip (tap one to use it), then Take Photo, Choose Photo for
 * everything else, Remove Photo when there is one, and Cancel. Without library access the strip simply isn't there and
 * the buttons carry on; with limited access a Manage tile leads the strip.
 */
@Composable
private fun PhotoSheetContent(
    state: EditProfileUiState,
    onUseRecent: (com.circaso.core.platform.LibraryItem) -> Unit,
    onManage: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val limited = state.recentsAccess == com.circaso.core.platform.LibraryAccess.Limited
    val reduce = CircasoTheme.reduceMotion
    // The strip arrives a moment after the sheet: it opens downwards rather than jumping in (at once under reduced motion).
    androidx.compose.animation.AnimatedVisibility(
        state.recents.isNotEmpty() || limited,
        enter = if (reduce) androidx.compose.animation.fadeIn(androidx.compose.animation.core.snap())
        else androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)) +
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)),
        exit = if (reduce) androidx.compose.animation.fadeOut(androidx.compose.animation.core.snap())
        else androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)) +
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)),
    ) {
        RecentsStrip(state.recents, limited, onUseRecent, onManage)
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Take Photo", onTakePhoto, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.Camera)
        CircasoButton("Choose Photo", onChoose, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.Photo)
        if (state.profile?.avatarUrl != null) {
            CircasoButton("Remove Photo", onRemove, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true)
        }
        CircasoButton("Cancel", onCancel, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
    }
}

/** Square photo tiles, edge to edge across the sheet so the row reads as scrollable; Manage first when access is limited. */
@Composable
private fun RecentsStrip(
    recents: List<com.circaso.core.platform.LibraryItem>,
    limited: Boolean,
    onUse: (com.circaso.core.platform.LibraryItem) -> Unit,
    onManage: () -> Unit,
) {
    val c = CircasoTheme.colors
    val bleed = SheetSidePadding
    androidx.compose.foundation.lazy.LazyRow(
        Modifier.fillMaxWidth().layout { measurable, constraints ->
            // Past the sheet's side padding, to its edges; the content padding brings the first tile back in line.
            val extra = bleed.roundToPx() * 2
            val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
            val placeable = measurable.measure(wide)
            layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
        }.semantics { contentDescription = "Recent photos" },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        if (limited) item(key = "manage") {
            Column(
                Modifier.size(RecentTile).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill)
                    .pressable(onClick = onManage)
                    .semantics(mergeDescendants = true) { contentDescription = "Circaso can see only the photos you chose. Choose more" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1, Alignment.CenterVertically),
            ) {
                CircasoIconImage(CircasoIcon.Plus, c.ink, Modifier.size(20.dp))
                BasicText("Manage", style = CircasoTheme.typography.caption1.copy(color = c.ink), maxLines = 1)
            }
        }
        items(recents.size, key = { recents[it].id }) { i ->
            val item = recents[i]
            Box(
                Modifier.size(RecentTile).clip(com.circaso.designsystem.theme.CircasoShapes.md).background(c.fill)
                    .pressable(onClick = { onUse(item) })
                    .semantics { contentDescription = "Recent photo ${i + 1} of ${recents.size}. Use as profile photo" },
            ) { com.circaso.core.platform.LibraryThumbnail(item, Modifier.fillMaxSize()) }
        }
    }
}

/** A recent photo's tile in the sheet: big enough to recognise a face, four and a bit across a phone. */
private val RecentTile = 80.dp

/** CircasoSheet's own side padding, which the strip runs past. */
private val SheetSidePadding = 20.dp

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BackHandlerFor(enabled: Boolean, onBack: () -> Unit) = androidx.compose.ui.backhandler.BackHandler(enabled = enabled, onBack = onBack)

/** iOS's system green, readable on both themes; only for "this username is free". */
private val HandleGreen = androidx.compose.ui.graphics.Color(0xFF30A14E)

@Composable
private fun Footnote(text: String, color: androidx.compose.ui.graphics.Color) =
    BasicText(text, style = CircasoTheme.typography.footnote.copy(color = color), modifier = Modifier.padding(horizontal = CircasoSpacing.s5))

/** A grouped-list row with its label on the left and the text field filling the rest (Settings-style). */
@Composable
private fun InlineField(label: String, value: String, onValue: (String) -> Unit, placeholder: String, singleLine: Boolean = true, capitalize: Boolean = false) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = t.body.copy(color = c.ink), modifier = Modifier.widthIn(min = 84.dp).padding(end = CircasoSpacing.s2))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onValue, singleLine = singleLine, maxLines = if (singleLine) 1 else 4,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = if (capitalize) androidx.compose.ui.text.input.KeyboardCapitalization.Words else androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                textStyle = t.body.copy(color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.ink), modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkTertiary))
        }
    }
}
