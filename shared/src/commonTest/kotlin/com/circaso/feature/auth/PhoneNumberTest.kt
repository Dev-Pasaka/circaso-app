package com.circaso.feature.auth

import com.circaso.feature.auth.domain.PhoneNumber
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneNumberTest {
    @Test fun nineDigitsBecomeE164() = assertEquals("+254712345678", PhoneNumber.toE164("712 345 678"))
    @Test fun aLeadingZeroIsDropped() = assertEquals("+254712345678", PhoneNumber.toE164("0712 345 678"))
    @Test fun theCountryCodeIsAccepted() {
        assertEquals("+254712345678", PhoneNumber.toE164("+254 712 345 678"))
        assertEquals("+254712345678", PhoneNumber.toE164("254712345678"))
    }
    @Test fun incompleteOrTooLongIsRejected() {
        assertNull(PhoneNumber.toE164("71234567"))
        assertNull(PhoneNumber.toE164("7123456789"))
        assertNull(PhoneNumber.toE164(""))
    }
    @Test fun tooShortOnlyAfterTheyStartTyping() {
        assertFalse(PhoneNumber.looksTooShort(""))
        assertTrue(PhoneNumber.looksTooShort("7123"))
        assertFalse(PhoneNumber.looksTooShort("712345678"))
    }
    @Test fun formatsInGroupsOfThree() {
        assertEquals("712 345 678", PhoneNumber.format("712345678"))
        assertEquals("712 34", PhoneNumber.format("71234"))
        assertEquals("712 345 678", PhoneNumber.format("0712345678"))
    }

    @Test fun nationalDigitsDropsPunctuationPrefixesAndExtraDigits() {
        assertEquals("712345678", PhoneNumber.nationalDigits("+254 (712) 345-678"))
        assertEquals("712345678", PhoneNumber.nationalDigits("0712345678"))
        assertEquals("7", PhoneNumber.nationalDigits("0 7"))
        assertEquals("", PhoneNumber.nationalDigits("abc"))
    }
}
