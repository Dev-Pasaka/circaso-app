package com.circaso.designsystem

import com.circaso.designsystem.components.textWithoutAshaCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AshaCommandTest {
    @Test fun stripsTheCommandAtTheEnd() = assertEquals("Hello ", textWithoutAshaCommand("Hello /asha"))
    @Test fun worksWhenTheCommandIsAlone() = assertEquals("", textWithoutAshaCommand("/asha"))
    @Test fun ignoresCase() = assertEquals("", textWithoutAshaCommand("/Asha"))
    @Test fun ignoresTextWithoutTheCommand() = assertNull(textWithoutAshaCommand("Hello"))
    @Test fun ignoresTheCommandInTheMiddle() = assertNull(textWithoutAshaCommand("/asha later"))
}
