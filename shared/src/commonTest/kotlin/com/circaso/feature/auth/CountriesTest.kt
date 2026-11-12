package com.circaso.feature.auth

import com.circaso.feature.auth.domain.Countries
import com.circaso.feature.auth.domain.PhoneNumber
import com.circaso.feature.auth.presentation.CodeViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone number screen's countries (2026-11-12): Kenya by default, any other by choice. */
class CountriesTest {
    private val uk = Countries.byIso("GB")!!
    private val us = Countries.byIso("US")!!
    private val italy = Countries.byIso("IT")!!

    @Test fun everyCountryIsListedOnceWithAFlag() {
        assertEquals(Countries.all.size, Countries.all.map { it.iso }.toSet().size)
        assertTrue(Countries.all.size > 200)
        assertEquals("🇰🇪", Countries.Kenya.flag)
    }

    @Test fun otherCountriesBecomeE164() {
        assertEquals("+447700900123", PhoneNumber.toE164("07700 900123", uk))
        assertEquals("+12025550123", PhoneNumber.toE164("202 555 0123", us))
        assertEquals("+390612345678", PhoneNumber.toE164("06 1234 5678", italy), "Italy keeps its 0")
        assertNull(PhoneNumber.toE164("7700 9001", uk), "the UK is checked exactly")
    }

    @Test fun aPastedInternationalNumberDropsItsCallingCode() {
        assertEquals("7700900123", PhoneNumber.nationalDigits("+44 7700 900123", uk))
    }

    @Test fun searchFindsByNameIsoOrCode() {
        assertEquals("GB", Countries.search("united k").first().iso)
        assertEquals("KE", Countries.search("ke").first().iso)
        assertTrue(Countries.search("+254").any { it.iso == "KE" })
        assertTrue(Countries.search("zzz").isEmpty())
    }

    @Test fun internationalNumbersFindTheirCountry() {
        assertEquals("KE", Countries.forInternational("254712345678")?.iso)
        assertEquals("JM", Countries.forInternational("18765551234")?.iso, "the longest code wins")
        assertEquals("US", Countries.forInternational("12025550123")?.iso)
        assertEquals("GB", Countries.forInternational("447700900123")?.iso)
    }

    @Test fun anyCountrysNumberIsMasked() {
        assertEquals("+254 7•• ••• 678", CodeViewModel.mask("+254712345678"))
        assertEquals("+44 7•• ••• 123", CodeViewModel.mask("+447700900123"))
    }
}
