package com.circaso.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.circaso.designsystem.platform.LocalPlatformStyle
import com.circaso.designsystem.platform.PlatformStyle
import com.circaso.designsystem.platform.currentPlatformStyle
import com.circaso.designsystem.platform.rememberIsHighContrast

val LocalCircasoColors = staticCompositionLocalOf { LightColors }
val LocalCircasoTypography = staticCompositionLocalOf { DefaultTypography }

object CircasoTheme {
    val colors: CircasoColors
        @Composable @ReadOnlyComposable get() = LocalCircasoColors.current
    val typography: CircasoTypography
        @Composable @ReadOnlyComposable get() = LocalCircasoTypography.current
    val platform: PlatformStyle
        @Composable @ReadOnlyComposable get() = LocalPlatformStyle.current
    /** The OS asked for less motion: fade instead of slide, snap instead of grow, no press scale. */
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = com.circaso.designsystem.platform.LocalReduceMotion.current
}

fun circasoColors(dark: Boolean, highContrast: Boolean): CircasoColors = when {
    dark && highContrast -> DarkHighContrastColors
    dark -> DarkColors
    highContrast -> LightHighContrastColors
    else -> LightColors
}

@Composable
fun CircasoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highContrast: Boolean = rememberIsHighContrast(),
    platformStyle: PlatformStyle = currentPlatformStyle(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCircasoColors provides circasoColors(darkTheme, highContrast),
        LocalCircasoTypography provides DefaultTypography,
        LocalPlatformStyle provides platformStyle,
        com.circaso.designsystem.platform.LocalReduceMotion provides com.circaso.designsystem.platform.rememberReduceMotion(),
        content = content,
    )
}
