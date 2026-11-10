package com.circaso.designsystem

import com.circaso.designsystem.components.AshaCommandHit
import com.circaso.designsystem.components.ashaCommandTyped
import com.circaso.designsystem.components.ashaFragment
import com.circaso.designsystem.components.takeAshaFragment
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

    // The `/` suggestion (2026-11-10): offered while the word at the cursor is still the start of `/asha`.

    @Test fun suggestsFromTheSlashOn() {
        for (typed in listOf("/", "/a", "/as", "/ash", "/asha")) assertEquals(0 until typed.length, ashaFragment(typed), typed)
        assertEquals(6 until 9, ashaFragment("hello /AS"), "case-insensitive, after a space")
        assertEquals(3 until 5, ashaFragment("hi\n/a"), "after a new line")
    }

    @Test fun stopsSuggestingWhenItIsNoLongerThePrefix() {
        assertNull(ashaFragment("/ax"), "a misspelling")
        assertNull(ashaFragment("/ashan"), "longer than the command")
        assertNull(ashaFragment("/as "), "a space typed")
        assertNull(ashaFragment(""), "the slash deleted")
        assertNull(ashaFragment("hello"))
        assertNull(ashaFragment("path/a"), "not at a word start")
        assertNull(ashaFragment("1/2"))
    }

    @Test fun followsTheCursor() {
        assertEquals(4 until 6, ashaFragment("ask /a now", cursor = 6), "typed in the middle")
        assertNull(ashaFragment("ask /ak now", cursor = 6), "inside a longer word")
        assertNull(ashaFragment("/as", cursor = 0))
        assertNull(ashaFragment("/as", cursor = 9))
    }

    @Test fun summoningTakesTheFragmentOut() {
        assertEquals(AshaCommandHit("hello ", 6), takeAshaFragment("hello /as", 6 until 9))
        assertEquals(AshaCommandHit("ask  now", 4), takeAshaFragment("ask /a now", 4 until 6))
    }
}
