@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIContextMenuConfigurationElementOrderFixed
import platform.UIKit.UIImage
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementState
import platform.UIKit.setAccessibilityElementsHidden
import platform.UIKit.setIsAccessibilityElement
import platform.UIKit.setAccessibilityLabel
import platform.UIKit.UIMenuOptionsDisplayInline
import platform.UIKit.UIUserInterfaceStyle

// The drawn button's circle, like a toolbar's `ellipsis` button. The UIKit anchor is a point at its centre, under the
// middle dot: an interop view leaves a clear hole in Compose's drawing, and a point-sized one can't show through the
// disc while it dims on press or fades in a crossfade. UIMenu still hangs from the button, placed from that point.
private val ButtonCircle = 30.dp
private val AnchorSize = 2.dp

/**
 * iOS: a real UIKit pull-down menu (UIMenu), the same one a `UIButton` with `showsMenuAsPrimaryAction` opens: its
 * title, inline sections with the system separator, a tick on the chosen group, subtitles, SF Symbols, the system's
 * blur, haptics, spring, Dynamic Type, VoiceOver and Reduce Motion. The button the person sees is drawn by Compose (so
 * it sits in the page's own layers, under sheets and screen transitions, over the wallet's glow); under it, the size
 * of its circle, a plain UIButton holds the menu and is what the menu hangs from. A tap asks it to open
 * (`performPrimaryAction`, iOS 17.4; the app needs 18.2).
 *
 * Known platform limit: UIMenu follows the app's light/dark choice (set on the anchor) but takes contrast from the
 * phone's Increase Contrast setting, not from the app's own high-contrast themes.
 */
@Composable
internal actual fun NativeMoreMenu(
    title: String,
    sections: List<List<NativeMenuItem>>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    contentDescription: String,
    modifier: Modifier,
) {
    val c = CircasoTheme.colors
    val select by rememberUpdatedState(onSelect)
    val anchor = remember {
        UIButton.buttonWithType(UIButtonTypeSystem).apply {
            showsMenuAsPrimaryAction = true
            // Groups stay in the order given whether the menu opens down or up.
            preferredMenuElementOrder = UIContextMenuConfigurationElementOrderFixed
            backgroundColor = UIColor.clearColor
            // VoiceOver reads the drawn button; the anchor is not a second element.
            // Every tap comes through the drawn button and `performPrimaryAction`: one path, one feel.
            userInteractionEnabled = false
            setIsAccessibilityElement(false)
            setAccessibilityElementsHidden(true)
        }
    }
    val menu = remember(title, sections) { uiMenu(title, sections) { id -> select(id) } }
    val dark = c.isDark
    SideEffect {
        anchor.menu = menu
        // The app's own light/dark choice (You → Appearance) can differ from the phone's: the menu follows the app.
        anchor.overrideUserInterfaceStyle = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
    // UIKit owns the menu once it is up, so "expanded" only asks it to open (previews) and is handed straight back.
    LaunchedEffect(expanded) {
        if (expanded) {
            anchor.performPrimaryAction()
            onExpandedChange(false)
        }
    }
    Box(modifier.size(CircasoSizes.controlMd), contentAlignment = Alignment.Center) {
        UIKitView(
            factory = { anchor },
            modifier = Modifier.size(AnchorSize),
            properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
        )
        // Drawn after the anchor, so it covers the anchor's hole; opaque, so nothing shows through it.
        val fill = c.fill.compositeOver(c.bg)
        Box(
            Modifier.size(CircasoSizes.controlMd)
                .pressable(role = Role.Button, onClick = { anchor.performPrimaryAction() })
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(ButtonCircle).clip(CircasoShapes.circle).background(fill), contentAlignment = Alignment.Center) {
                CircasoIconImage(CircasoIcon.More, c.ink, Modifier.size(20.dp), strokeWidth = 2.8f)
            }
        }
    }
}

/** The menu: [title] over inline sections (the system draws the separators); each item an action with its subtitle and tick. */
private fun uiMenu(title: String, sections: List<List<NativeMenuItem>>, onSelect: (String) -> Unit): UIMenu {
    val children = sections.map { section ->
        UIMenu.menuWithTitle(
            title = "", image = null, identifier = null, options = UIMenuOptionsDisplayInline,
            children = section.map { item ->
                UIAction.actionWithTitle(item.title, image = item.icon?.let { UIImage.systemImageNamed(it.symbol()) }, identifier = null, handler = { _ -> onSelect(item.id) }).apply {
                    item.subtitle?.let { subtitle = it }
                    if (item.checked) state = UIMenuElementState.UIMenuElementStateOn
                    item.accessibilityLabel?.let { setAccessibilityLabel(it) }
                }
            },
        )
    }
    return UIMenu.menuWithTitle(title = title, image = null, identifier = null, options = 0u, children = children)
}

private fun NativeMenuIcon.symbol() = when (this) {
    NativeMenuIcon.Chat -> "bubble.left.and.bubble.right"
    NativeMenuIcon.Info -> "info.circle"
    NativeMenuIcon.List -> "person.3"
    NativeMenuIcon.Send -> "arrow.up.circle"
    NativeMenuIcon.External -> "arrow.up.right.square"
}
