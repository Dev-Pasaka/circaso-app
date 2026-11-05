package com.circaso.feature.profile.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.CameraStart
import com.circaso.core.platform.openAppSettings
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Take Photo on Edit Profile: a full-screen camera over the form, on the dark stage in every theme (like the Moments
 * camera, design M1). The viewfinder is a square with a round guide in it: the shot is cropped to that square, so the
 * circle shows exactly what the profile photo will be. After the shutter the photo waits on the same stage for Use
 * Photo or Retake; nothing is uploaded until then. In with a short fade and scale, out faster with a fade; under reduced
 * motion it simply appears and goes.
 */
@Composable
fun BoxScope.ProfileCameraLayer(
    camera: ProfileCamera?,
    online: Boolean,
    viewfinder: @Composable (Modifier) -> Unit,
    onClose: () -> Unit,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
    onRetake: () -> Unit,
    onUse: () -> Unit,
    onChooseInstead: () -> Unit,
) {
    val reduce = CircasoTheme.reduceMotion
    // The last camera draws the exit, so the stage fades out with what it showed rather than going blank first.
    var last by remember { mutableStateOf(camera) }
    if (camera != null) last = camera
    AnimatedVisibility(
        camera != null, Modifier.matchParentSize(),
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(CircasoMotion.BaseMs)) + scaleIn(tween(CircasoMotion.BaseMs), initialScale = 0.96f),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(CircasoMotion.QuickMs)),
    ) {
        val shown = last ?: return@AnimatedVisibility
        CircasoTheme(darkTheme = true, highContrast = CircasoTheme.colors.isHighContrast) {
            ProfileCameraContent(shown, online, viewfinder, onClose, onShutter, onFlip, onRetake, onUse, onChooseInstead)
        }
    }
}

/** The camera stage itself, stateless so it can be previewed (`-preview=profile-camera-denied`). */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ProfileCameraContent(
    camera: ProfileCamera,
    online: Boolean,
    viewfinder: @Composable (Modifier) -> Unit,
    onClose: () -> Unit,
    onShutter: () -> Unit,
    onFlip: () -> Unit,
    onRetake: () -> Unit,
    onUse: () -> Unit,
    onChooseInstead: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // Back closes the camera, as the close button does.
    androidx.compose.ui.backhandler.BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().background(c.bg)
            // Swallows taps so nothing reaches the form underneath.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
            .semantics { contentDescription = "Camera for your profile photo" }
            .statusBarsPadding().navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StageButton(CircasoIcon.Close, "Close camera", onClick = onClose)
            BasicText(
                if (camera.shot != null) "Use this photo?" else "Profile Photo",
                Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite },
                style = t.headline.copy(color = c.ink, textAlign = TextAlign.Center),
            )
            // Balances the close button so the title sits in the middle.
            Spacer(Modifier.size(CircasoSizes.controlMd))
        }

        // The square stage, as large as the screen allows, centred in what is left above the controls.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                when (camera.start) {
                    CameraStart.Started -> {
                        // Stays on under the review, so Retake is instant.
                        viewfinder(Modifier.fillMaxSize())
                        camera.shot?.let { shot ->
                            coil3.compose.AsyncImage(
                                shot.bytes, contentDescription = "Your new profile photo",
                                modifier = Modifier.fillMaxSize().background(c.bg), contentScale = ContentScale.Crop,
                            )
                        }
                        RoundGuide()
                    }
                    CameraStart.PermissionDenied -> Blocked(
                        "Allow the camera to take a profile photo", "Circaso uses the camera only while this screen is open.",
                        action = "Open Settings", onAction = { openAppSettings() },
                    )
                    CameraStart.Unavailable -> Blocked(
                        "No camera here", "Choose a photo from your library instead.",
                        action = "Choose Photo", onAction = onChooseInstead,
                    )
                    null -> Unit // opening: the dark stage, for the moment it takes
                }
            }
        }

        // The controls: shutter and flip while shooting; Retake and Use Photo once the photo is taken.
        Box(Modifier.fillMaxWidth().heightIn(min = 132.dp).padding(horizontal = CircasoSpacing.s6, vertical = CircasoSpacing.s4), contentAlignment = Alignment.Center) {
            val shot = camera.shot
            when {
                shot != null -> Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    if (!online) {
                        BasicText(
                            "Changing your photo needs a connection.", Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                            style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                        CircasoButton("Retake", onRetake, Modifier.weight(1f), style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
                        CircasoButton("Use Photo", onUse, Modifier.weight(1f), size = ButtonSize.Large, block = true, enabled = online)
                    }
                }
                camera.start == CameraStart.Started -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.size(CircasoSizes.controlMd))
                    Shutter(camera.shooting, onShutter)
                    StageButton(CircasoIcon.FlipCamera, "Flip camera", enabled = !camera.shooting, onClick = onFlip)
                }
            }
        }
    }
}

/** The round guide: the square outside the circle is dimmed, and a thin ring marks the edge of the profile photo. */
@Composable
private fun RoundGuide() {
    val c = CircasoTheme.colors
    Canvas(Modifier.fillMaxSize()) {
        val inset = 2.dp.toPx()
        val radius = size.minDimension / 2 - inset
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }
        clipPath(circle, ClipOp.Difference) { drawRect(c.scrim) }
        drawCircle(c.ink.copy(alpha = 0.85f), radius, center = Offset(size.width / 2, size.height / 2), style = Stroke(1.5.dp.toPx()))
    }
}

/** The design's shutter (M1): an ink ring around an ink disc; while the photo is being taken, a spinner in its place. */
@Composable
private fun Shutter(shooting: Boolean, onShutter: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(78.dp)
            .drawBehind {
                val w = 4.dp.toPx()
                drawCircle(c.ink, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
            }
            .padding(8.dp).clip(CircasoShapes.circle).background(if (shooting) c.fill else c.ink)
            .pressable(enabled = !shooting, role = Role.Button, onClick = onShutter)
            .semantics { contentDescription = if (shooting) "Taking photo" else "Take photo" },
        contentAlignment = Alignment.Center,
    ) { if (shooting) Spinner(c.ink, Modifier.size(24.dp)) }
}

@Composable
private fun StageButton(icon: CircasoIcon, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(CircasoSizes.controlMd).clip(CircasoShapes.circle).background(c.glass)
            .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CircasoIconImage(icon, if (enabled) c.ink else c.inkTertiary, Modifier.size(20.dp)) }
}

/** Camera refused or missing (design M1c's pattern): what happened, why, and the way on. */
@Composable
private fun Blocked(title: String, sub: String, action: String, onAction: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(
        Modifier.padding(horizontal = CircasoSpacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
    ) {
        CircasoIconImage(CircasoIcon.Camera, c.inkSecondary, Modifier.size(40.dp))
        BasicText(title, style = t.title3.copy(color = c.ink, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold))
        BasicText(sub, style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
        CircasoButton(action, onAction, style = ButtonStyle.Secondary, size = ButtonSize.Small)
    }
}
