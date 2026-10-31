package com.circaso.designsystem.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoTheme

/**
 * Screen frame. Order, top to bottom: top bar, banner (the connection banner), content.
 * The floating [bottomBar] sits over the content; [content] receives the bottom padding it needs so
 * nothing hides behind it. [overlay] (sheets) draws above everything but the [toast].
 */
@Composable
fun CircasoScaffold(
    modifier: Modifier = Modifier,
    grouped: Boolean = false,
    topBar: @Composable () -> Unit = {},
    banner: @Composable () -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    toast: @Composable BoxScope.() -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
    /** Drawn behind everything from the very top of the screen, under the status bar (the You tab's photo glow). */
    backdrop: @Composable BoxScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val c = CircasoTheme.colors
    val density = LocalDensity.current
    var bottomBarHeight by remember { mutableStateOf(0.dp) }
    // Some devices report no navigation inset; keep the floating bar off the screen edge anyway.
    val navInset = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 16.dp)
    Box(modifier.fillMaxSize().background(if (grouped) c.bgGrouped else c.bg)) {
        backdrop()
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).let {
                // With a tab bar the bar itself clears the home indicator; without one the content must.
                if (bottomBar == null) it.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)) else it.imePadding()
            },
        ) {
            topBar()
            banner()
            Box(Modifier.weight(1f)) { content(PaddingValues(bottom = if (bottomBar != null) bottomBarHeight + navInset else 0.dp)) }
        }
        if (bottomBar != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = navInset)
                    .onSizeChanged { bottomBarHeight = with(density) { it.height.toDp() } + 8.dp },
            ) { bottomBar() }
        }
        overlay()
        toast()
    }
}
