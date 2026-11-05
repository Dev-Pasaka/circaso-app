package com.circaso.feature.profile.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.ImagePicker
import com.circaso.core.platform.LibraryAccess
import com.circaso.core.platform.LibraryItem
import com.circaso.core.platform.LibraryKind
import com.circaso.core.platform.MediaLibrary
import com.circaso.core.platform.MomentCamera
import com.circaso.core.platform.PickedImage
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the camera stage says, so the same stage serves the profile photo and a group's photo. */
class CameraWords(val title: String, val stage: String, val shot: String, val denied: String, val offline: String) {
    companion object {
        val Profile = CameraWords(
            "Profile Photo", "Camera for your profile photo", "Your new profile photo",
            "Allow the camera to take a profile photo", "Changing your photo needs a connection.",
        )
        val Group = CameraWords(
            "Group Photo", "Camera for the group photo", "The new group photo",
            "Allow the camera to take a group photo", "Changing the group photo needs a connection.",
        )
    }
}

/** The photo sheet's state: the recents strip (empty without access) and the camera while it is open. */
data class PhotoChoiceState(
    val open: Boolean = false,
    val recents: List<LibraryItem> = emptyList(),
    /** Null until the sheet has asked. Denied hides the strip; Limited adds Manage. */
    val access: LibraryAccess? = null,
    val camera: ProfileCamera? = null,
)

/** Where a photo can come from: the phone's own picker, the recent photos, the in-app camera. */
class PhotoSources(val picker: ImagePicker, val library: MediaLibrary, val camera: MomentCamera)

/**
 * The photo sheet and camera behaviour shared by the profile photo's pattern and group photos: the strip is filled (and
 * the library permission asked, the first time) only when the sheet opens; Take Photo asks for the camera alone; a
 * picked or shot photo goes to [onPicked]. A screen's ViewModel owns one and exposes [state].
 */
class PhotoChooser(
    private val scope: CoroutineScope,
    private val sources: PhotoSources,
    private val onPicked: (PickedImage?, unreadable: String?) -> Unit,
) {
    private val _state = MutableStateFlow(PhotoChoiceState())
    val state: StateFlow<PhotoChoiceState> = _state.asStateFlow()
    private var recentsJob: Job? = null

    /** The in-app camera, for the viewfinder. */
    val cameraDevice: MomentCamera get() = sources.camera

    fun open() { _state.update { it.copy(open = true) }; loadRecents(ask = true) }
    fun dismiss() = _state.update { it.copy(open = false) }

    fun choose() { dismiss(); load(null) { sources.picker.pick() } }
    fun useRecent(item: LibraryItem) { dismiss(); load("Couldn't open that photo. Try another one.") { sources.library.loadAvatar(item) } }

    fun manage() { scope.launch { sources.library.manage(LibraryKind.Photo); loadRecents(ask = false) } }

    fun openCamera() {
        dismiss()
        if (_state.value.camera != null) return
        _state.update { it.copy(camera = ProfileCamera()) }
        scope.launch {
            val start = try { sources.camera.openForPhoto() } catch (e: CancellationException) { throw e } catch (e: Exception) { CameraStart.Unavailable }
            _state.update { s -> s.copy(camera = s.camera?.copy(start = start)) }
        }
    }

    fun shutter(onFailed: () -> Unit = {}) {
        val cam = _state.value.camera ?: return
        if (cam.start != CameraStart.Started || cam.shooting || cam.shot != null) return
        _state.update { s -> s.copy(camera = s.camera?.copy(shooting = true)) }
        scope.launch {
            val shot = try { sources.camera.takeProfilePhoto() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { s -> s.copy(camera = s.camera?.copy(shooting = false, shot = shot)) }
            if (shot == null) onFailed()
        }
    }

    fun retake() = _state.update { s -> s.copy(camera = s.camera?.copy(shot = null)) }
    fun flip() { if (_state.value.camera?.start == CameraStart.Started) sources.camera.flip() }

    fun useShot() {
        val shot = _state.value.camera?.shot ?: return
        closeCamera()
        onPicked(shot, null)
    }

    fun closeCamera() {
        if (_state.value.camera == null) return
        sources.camera.close()
        _state.update { it.copy(camera = null) }
    }

    /** Camera to library: close the camera, then the system picker. */
    fun chooseInstead() { closeCamera(); load(null) { sources.picker.pick() } }

    private fun load(unreadable: String?, read: suspend () -> PickedImage?) {
        scope.launch {
            val picked = try { read() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            onPicked(picked, if (picked == null) unreadable else null)
        }
    }

    private fun loadRecents(ask: Boolean) {
        recentsJob?.cancel()
        recentsJob = scope.launch {
            val r = try { sources.library.recents(LibraryKind.Photo, RecentsLimit, ask) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (r != null) _state.update { it.copy(recents = r.items, access = r.access) }
        }
    }

    private companion object { const val RecentsLimit = 24 }
}

/**
 * The photo sheet: the newest photos on the phone in a strip (tap one to use it), then Take Photo, Choose Photo for
 * everything else, Remove Photo when there is one, and Cancel. Without library access the strip simply isn't there and
 * the buttons carry on; with limited access a Manage tile leads the strip. [useAs] finishes each tile's spoken label.
 */
@Composable
fun PhotoChoiceSheetContent(
    recents: List<LibraryItem>,
    access: LibraryAccess?,
    hasPhoto: Boolean,
    useAs: String,
    onUseRecent: (LibraryItem) -> Unit,
    onManage: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoose: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val limited = access == LibraryAccess.Limited
    val reduce = CircasoTheme.reduceMotion
    // The strip arrives a moment after the sheet: it opens downwards rather than jumping in (at once under reduced motion).
    AnimatedVisibility(
        recents.isNotEmpty() || limited,
        enter = if (reduce) fadeIn(snap()) else expandVertically(tween(CircasoMotion.BaseMs)) + fadeIn(tween(CircasoMotion.BaseMs)),
        exit = if (reduce) fadeOut(snap()) else shrinkVertically(tween(CircasoMotion.QuickMs)) + fadeOut(tween(CircasoMotion.QuickMs)),
    ) {
        RecentsStrip(recents, limited, useAs, onUseRecent, onManage)
    }
    Column(verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        CircasoButton("Take Photo", onTakePhoto, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.Camera)
        CircasoButton("Choose Photo", onChoose, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true, icon = CircasoIcon.Photo)
        if (hasPhoto) CircasoButton("Remove Photo", onRemove, style = ButtonStyle.Destructive, size = ButtonSize.Large, block = true)
        CircasoButton("Cancel", onCancel, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
    }
}

/** Square photo tiles, edge to edge across the sheet so the row reads as scrollable; Manage first when access is limited. */
@Composable
private fun RecentsStrip(recents: List<LibraryItem>, limited: Boolean, useAs: String, onUse: (LibraryItem) -> Unit, onManage: () -> Unit) {
    val c = CircasoTheme.colors
    val bleed = SheetSidePadding
    LazyRow(
        Modifier.fillMaxWidth().layout { measurable, constraints ->
            // Past the sheet's side padding, to its edges; the content padding brings the first tile back in line.
            val extra = bleed.roundToPx() * 2
            val wide = if (constraints.hasBoundedWidth) constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra) else constraints
            val placeable = measurable.measure(wide)
            layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
        }.semantics { contentDescription = "Recent photos" },
        contentPadding = PaddingValues(horizontal = bleed),
        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        if (limited) item(key = "manage") {
            Column(
                Modifier.size(RecentTile).clip(CircasoShapes.md).background(c.fill)
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
                Modifier.size(RecentTile).clip(CircasoShapes.md).background(c.fill)
                    .pressable(onClick = { onUse(item) })
                    .semantics { contentDescription = "Recent photo ${i + 1} of ${recents.size}. Use as $useAs" },
            ) { com.circaso.core.platform.LibraryThumbnail(item, Modifier.fillMaxSize()) }
        }
    }
}

/** A recent photo's tile in the sheet: big enough to recognise a face, four and a bit across a phone. */
private val RecentTile = 80.dp

/** CircasoSheet's own side padding, which the strip runs past. */
private val SheetSidePadding = 20.dp
