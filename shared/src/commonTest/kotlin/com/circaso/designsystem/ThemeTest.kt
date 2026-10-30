package com.circaso.designsystem

import com.circaso.designsystem.theme.DarkColors
import com.circaso.designsystem.theme.DarkHighContrastColors
import com.circaso.designsystem.theme.LightColors
import com.circaso.designsystem.theme.LightHighContrastColors
import com.circaso.designsystem.theme.circasoColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ThemeTest {
    @Test
    fun picksOneOfFourThemes() {
        assertSame(LightColors, circasoColors(dark = false, highContrast = false))
        assertSame(DarkColors, circasoColors(dark = true, highContrast = false))
        assertSame(LightHighContrastColors, circasoColors(dark = false, highContrast = true))
        assertSame(DarkHighContrastColors, circasoColors(dark = true, highContrast = true))
    }

    @Test
    fun highContrastKeepsDarkness() {
        assertEquals(true, DarkHighContrastColors.isDark)
        assertEquals(false, LightHighContrastColors.isDark)
    }
}
