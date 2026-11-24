package com.circaso.feature.wallet.presentation

import androidx.compose.ui.text.AnnotatedString
import kotlin.test.Test
import kotlin.test.assertEquals

class AddressGroupsTest {
    private val evm = "0x7a3F09c2E4b0d81aF52c3e9B6d0A71c4F21e4b00"

    @Test fun anEvmAddressKeepsItsPrefixOnTheFirstGroup() {
        val g = addressGroups(evm)
        assertEquals(10, g.size)
        assertEquals("0x7a3F", g.first()); assertEquals("4b00", g.last())
        assertEquals("0x7a3F … 4b00", shortGrouped(evm))
    }

    @Test fun aSolanaAddressIsGroupedFromItsStart() {
        val g = addressGroups("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM")
        assertEquals("9WzD", g.first()); assertEquals("AWWM", g.last()); assertEquals(11, g.size)
    }

    @Test fun shortTextStaysWhole() {
        assertEquals(emptyList(), addressGroups(""))
        assertEquals("0x12", shortGrouped("0x12"))
    }

    @Test fun theFieldsGroupingMapsTheCaretAcrossTheSpaces() {
        val t = AddressGrouping.filter(AnnotatedString("0x7a3F09c2E4"))
        assertEquals("0x7a3F 09c2 E4", t.text.text)
        val m = t.offsetMapping
        assertEquals(0, m.originalToTransformed(0))
        assertEquals(7, m.originalToTransformed(6)) // the caret sits after the space, before "09c2"
        assertEquals(8, m.originalToTransformed(7))
        assertEquals(14, m.originalToTransformed(12))
        assertEquals(12, m.transformedToOriginal(14))
        assertEquals(6, m.transformedToOriginal(7))
        // Exactly a group long: no trailing space.
        assertEquals("0x7a3F", AddressGrouping.filter(AnnotatedString("0x7a3F")).text.text)
    }
}
