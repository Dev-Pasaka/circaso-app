package com.circaso.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class MaskedTextTest {
    @Test fun bulletsBecomeNarrowDotsAndEverythingElseIsUntouched() =
        assertEquals("+254 7·· ··· 324", "+254 7•• ••• 324".maskedForDisplay())

    @Test fun anUnmaskedStringIsUnchanged() = assertEquals("hello", "hello".maskedForDisplay())
}
