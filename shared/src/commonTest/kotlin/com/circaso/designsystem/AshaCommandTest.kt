package com.circaso.designsystem

import com.circaso.designsystem.components.AshaCommandHit
import com.circaso.designsystem.components.ashaCommandTyped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AshaCommandTest {
    @Test fun stripsTheCommandAtTheEnd() = assertEquals(AshaCommandHit("Hello ", 6), ashaCommandTyped("Hello /ash", "Hello /asha"))
    @Test fun worksWhenTheCommandIsAlone() = assertEquals(AshaCommandHit("", 0), ashaCommandTyped("/ash", "/asha"))
    @Test fun ignoresCase() = assertEquals(AshaCommandHit("", 0), ashaCommandTyped("/AsH", "/AsHa"))
    @Test fun ignoresTextWithoutTheCommand() = assertNull(ashaCommandTyped("Hell", "Hello"))

    @Test fun needsItsOwnWord() {
        assertNull(ashaCommandTyped("path/ash", "path/asha"))
        assertNull(ashaCommandTyped("/asha", "/ashan"))
    }

    @Test fun firesWhenFollowedByPunctuationOrSpace() = assertEquals(AshaCommandHit("hi  there", 3), ashaCommandTyped("hi there", "hi /asha there", cursor = 8))

    @Test fun takesTheOneAtTheCursorOutOfTheMiddle() =
        assertEquals(AshaCommandHit("send  later", 5), ashaCommandTyped("send  later", "send /asha later", cursor = 10))

    @Test fun anEditElsewhereNeverFires() = assertNull(ashaCommandTyped("/asha note", "/asha notes"))

    @Test fun pastingTextWithTheCommandFires() = assertEquals(AshaCommandHit("ask  now", 4), ashaCommandTyped("", "ask /asha now"))
}
