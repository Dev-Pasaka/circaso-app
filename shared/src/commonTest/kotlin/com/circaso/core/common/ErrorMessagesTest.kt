package com.circaso.core.common

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorMessagesTest {
    private fun api(code: String) = AppError.Api(code, 400, "req-9", null, emptyMap())

    @Test fun knownCodesGetTheirOwnWords() =
        assertEquals("Too many tries. Try again in 10 minutes.", ErrorMessages.forError(api("identity.code_locked")))

    @Test fun anUnknownCodeIsNeverShownRaw() =
        assertEquals(ErrorMessages.SomethingWrong, ErrorMessages.forError(api("identity.brand_new_code")))

    @Test fun noConnectionHasItsOwnWords() = assertEquals(ErrorMessages.Offline, ErrorMessages.forError(AppError.Network))

    @Test fun aScreenCanUseItsOwnWordsForACode() =
        assertEquals("Custom", ErrorMessages.forError(api("identity.code_locked"), mapOf("identity.code_locked" to "Custom")))

    @Test fun theReferenceIsJustTheRequestIdWhenWeHaveWordsForTheCode() =
        assertEquals("req-9", ErrorMessages.reference(api("identity.code_locked")))

    @Test fun anUnmappedCodeIsAddedToTheReferenceSoSupportCanSeeIt() =
        assertEquals("req-9 · some.new_code", ErrorMessages.reference(api("some.new_code")))
}
