package com.circaso.feature.auth

import androidx.compose.ui.text.AnnotatedString
import com.circaso.feature.auth.presentation.PhoneNumberTransformation
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneTransformationTest {
    private fun shown(digits: String) = PhoneNumberTransformation.filter(AnnotatedString(digits)).text.text

    @Test fun groupsOfThree() {
        assertEquals("", shown(""))
        assertEquals("7", shown("7"))
        assertEquals("712", shown("712"))
        assertEquals("712 3", shown("7123"))
        assertEquals("712 345 678", shown("712345678"))
    }

    private val m = PhoneNumberTransformation.Mapping

    @Test fun theCursorFollowsTheDigitsNotTheSpaces() {
        // "712 345 678": digits 7,1,2 | 3,4,5 | 6,7,8
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 7, 9, 10, 11), (0..9).map { m.originalToTransformed(it) })
    }

    @Test fun andBackAgain() {
        // every cursor position in the shown text lands on a real digit position
        assertEquals(listOf(0, 1, 2, 3, 3, 4, 5, 6, 6, 7, 8, 9), (0..11).map { m.transformedToOriginal(it) })
    }

    @Test fun roundTripNeverDrifts() {
        for (n in 0..9) assertEquals(n, m.transformedToOriginal(m.originalToTransformed(n)), "digits $n")
    }
}
