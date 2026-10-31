package com.circaso.core.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import circaso.shared.generated.resources.Res
import circaso.shared.generated.resources.circaso_mark_black
import circaso.shared.generated.resources.circaso_mark_white
import com.circaso.designsystem.adaptive.ProvideWindowLayout
import com.circaso.designsystem.adaptive.WindowLayout
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import org.jetbrains.compose.resources.painterResource

/**
 * Where an entry may show on a wide window. Home is the list; a full-screen flow (a call, voice with Asha, a map,
 * sign-in) always takes the whole window; anything else opened from Home shows beside it, in the detail pane.
 */
object Panes {
    private const val Key = "circaso.pane"
    val List: Map<String, Any> = mapOf(Key to "list")
    val FullScreen: Map<String, Any> = mapOf(Key to "full")

    internal fun isList(entry: NavEntry<*>) = entry.metadata[Key] == "list"
    internal fun isFullScreen(entry: NavEntry<*>) = entry.metadata[Key] == "full"
}

/**
 * True for the list while something is open beside it: Back then belongs to the detail (it closes it), so the list
 * must not take Back for itself (Home's "Leave Circaso?" and its jump back to Chats).
 */
val LocalListHasDetail = androidx.compose.runtime.compositionLocalOf { false }

/**
 * List and detail side by side when the window is wide enough ([twoPane]), as iPad Messages, Mail and Settings
 * do: Home in a fixed list pane on the left, the top of the stack opened from it on the right. One scene for the
 * whole time Home is the list, so opening the next chat only changes the detail pane; the list never moves.
 */
class ListDetailSceneStrategy<T : Any>(private val twoPane: Boolean) : SceneStrategy<T> {
    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (!twoPane) return null
        val listIndex = entries.indexOfLast { Panes.isList(it) }
        if (listIndex < 0) return null
        val above = entries.drop(listIndex + 1)
        // A full-screen flow on top covers everything (it is pushed over the split like any screen on a phone).
        if (above.any { Panes.isFullScreen(it) }) return null
        val list = entries[listIndex]
        val detail = above.lastOrNull()
        return ListDetailScene(
            key = list.contentKey,
            list = list,
            detail = detail,
            depth = above.size,
            previousEntries = entries.dropLast(1),
        )
    }
}

internal data class ListDetailScene<T : Any>(
    override val key: Any,
    val list: NavEntry<T>,
    val detail: NavEntry<T>?,
    /** How many screens are open above the list: it grows on a push and shrinks on a pop. */
    val depth: Int,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOfNotNull(list, detail)

    override val content: @Composable () -> Unit = { ListDetail(list, detail, depth) }
}

@Composable
private fun <T : Any> ListDetail(list: NavEntry<T>, detail: NavEntry<T>?, depth: Int) {
    val c = CircasoTheme.colors
    val still = CircasoTheme.reduceMotion
    // Push or pop inside the detail pane, by whether the stack above the list grew or shrank.
    var lastDepth by remember { mutableIntStateOf(depth) }
    val forward = depth >= lastDepth
    lastDepth = depth
    Row(Modifier.fillMaxSize().background(c.bg)) {
        ProvideWindowLayout(Modifier.width(WindowLayout.ListPaneWidth).fillMaxHeight()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalListHasDetail provides (detail != null)) { list.Content() }
        }
        Box(Modifier.width(0.5.dp).fillMaxHeight().background(c.separator))
        ProvideWindowLayout(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            AnimatedContent(
                targetState = detail,
                contentKey = { it?.contentKey },
                transitionSpec = { if (forward) pushTransition(still) else popTransition(still) },
                label = "detail",
            ) { entry -> if (entry == null) EmptyDetail() else entry.Content() }
        }
    }
}

/** The detail pane before anything is opened: the mark and a line on what goes here. */
@Composable
private fun EmptyDetail() {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Box(Modifier.fillMaxSize().background(c.bgGrouped), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
            Image(
                painterResource(if (c.isDark) Res.drawable.circaso_mark_white else Res.drawable.circaso_mark_black),
                contentDescription = null, modifier = Modifier.size(44.dp),
            )
            BasicText(
                "Open a chat, a payment or a setting\nand it shows here.",
                style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                modifier = Modifier.padding(horizontal = CircasoSpacing.s6),
            )
        }
    }
}

/**
 * UIKit's push, on a spring: the new screen slides in from the trailing edge while the one underneath drifts a third
 * of the way back and dims. Critically damped (no bounce: screens are heavy) and interruptible, so a back swipe or a
 * rotation mid-flight carries on from where it is.
 */
fun <S> AnimatedContentTransitionScope<S>.pushTransition(still: Boolean): ContentTransform =
    if (still) fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith fadeOut(spring(stiffness = Spring.StiffnessMedium))
    else slideInHorizontally(ScreenSpring) { it } togetherWith (slideOutHorizontally(ScreenSpring) { -it / 3 } + fadeOut(ScreenFade, targetAlpha = UnderneathAlpha))

/** The push in reverse: the top screen slides off to the trailing edge, uncovering the one underneath. */
fun <S> AnimatedContentTransitionScope<S>.popTransition(still: Boolean): ContentTransform =
    if (still) fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith fadeOut(spring(stiffness = Spring.StiffnessMedium))
    else ((slideInHorizontally(ScreenSpring) { -it / 3 } + fadeIn(ScreenFade, initialAlpha = UnderneathAlpha)) togetherWith slideOutHorizontally(ScreenSpring) { it })
        .apply { targetContentZIndex = -1f } // the screen leaving stays on top as it slides away

/** About UIKit's 0.5 s push: critically damped, fast enough to never feel like waiting. */
private val ScreenSpring = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f, visibilityThreshold = IntOffset(1, 1))
private val ScreenFade = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
private const val UnderneathAlpha = 0.6f
