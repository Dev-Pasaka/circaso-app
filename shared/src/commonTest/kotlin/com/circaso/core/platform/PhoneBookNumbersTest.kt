package com.circaso.core.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneBookNumbersTest {
    @Test fun localFormatsBecomeE164() {
        assertEquals("+254712345678", PhoneBookNumbers.toE164("0712 345 678"))
        assertEquals("+254712345678", PhoneBookNumbers.toE164("0712-345-678"))
        assertEquals("+254712345678", PhoneBookNumbers.toE164("712345678"))
        assertEquals("+254112345678", PhoneBookNumbers.toE164("0112345678"))
    }

    @Test fun internationalFormatsAreKept() {
        assertEquals("+254712345678", PhoneBookNumbers.toE164("+254 712 345 678"))
        assertEquals("+254712345678", PhoneBookNumbers.toE164("254712345678"))
        assertEquals("+254712345678", PhoneBookNumbers.toE164("00254712345678"))
        assertEquals("+14155552671", PhoneBookNumbers.toE164("+1 (415) 555-2671"))
    }

    @Test fun thingsThatAreNotNumbersAreDropped() {
        assertNull(PhoneBookNumbers.toE164(""))
        assertNull(PhoneBookNumbers.toE164("abc"))
        assertNull(PhoneBookNumbers.toE164("12345"))
        assertNull(PhoneBookNumbers.toE164("*144#"))
        assertNull(PhoneBookNumbers.toE164("0712345")) // too short
        assertNull(PhoneBookNumbers.toE164("+0712345678")) // a country code cannot start with 0
    }

    @Test fun normaliseDropsInvalidAndDuplicatesAndKeepsOrder() {
        assertEquals(
            listOf("+254712345678", "+254722104556"),
            PhoneBookNumbers.normalise(listOf("0712 345 678", "+254712345678", "junk", "0722 104 556", "722104556")),
        )
    }

    @Test fun numbersWithoutACodeAreReadAsTheHomeCountrys() {
        val uk = com.circaso.feature.auth.domain.Countries.byIso("GB")!!
        assertEquals("+447700900123", PhoneBookNumbers.toE164("07700 900123", uk))
        assertEquals("+447700900123", PhoneBookNumbers.toE164("447700900123", uk))
        assertEquals("+254712345678", PhoneBookNumbers.toE164("+254 712 345 678", uk), "a full number stays as it is")
        val us = com.circaso.feature.auth.domain.Countries.byIso("US")!!
        assertEquals("+12025550123", PhoneBookNumbers.toE164("(202) 555-0123", us))
        assertEquals("+12025550123", PhoneBookNumbers.toE164("1 202 555 0123", us))
    }
}
