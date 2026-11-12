package com.circaso.feature.auth.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Shows `712345678` as `712 345 678` without changing what the field holds, so typing, deleting and moving the
 * cursor behave normally. Groups of three, fixed by position for the country's longest number ([maxDigits]), so the
 * spaces never move while typing. A single digit left over joins the last group (`770 090 0123`, never `770 090 012 3`);
 * two left over make the last two groups of four (an 11-digit Chinese number reads `138 0013 8000`). A "+code" still being
 * typed is shown as it is.
 */
class PhoneNumberTransformation(maxDigits: Int = 9) : VisualTransformation {
    private val breaks: List<Int> = run {
        val groups = when {
            maxDigits % GroupSize == 1 && maxDigits > GroupSize -> List((maxDigits - 4) / GroupSize) { GroupSize } + 4
            maxDigits % GroupSize == 2 && maxDigits >= 8 -> List((maxDigits - 8) / GroupSize) { GroupSize } + listOf(4, 4)
            else -> List((maxDigits + GroupSize - 1) / GroupSize) { GroupSize }
        }
        groups.runningReduce(Int::plus).dropLast(1).filter { it < maxDigits }
    }

    /** [digits] with this field's spacing, for a placeholder. */
    fun spaced(digits: String): String = filter(AnnotatedString(digits)).text.text

    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        if (digits.startsWith("+")) return TransformedText(text, OffsetMapping.Identity)
        val shown = buildString {
            digits.forEachIndexed { i, ch ->
                if (i in breaks) append(' ')
                append(ch)
            }
        }
        return TransformedText(AnnotatedString(shown), mapping(digits.length))
    }

    /** The cursor follows the digits, never the spaces, for a field holding [length] digits. */
    fun mapping(length: Int): OffsetMapping = object : OffsetMapping {
        private val shownBreaks = breaks.filter { it < length }

        /** Position in the digits to position in the shown text: one space for every break before it. */
        override fun originalToTransformed(offset: Int): Int = offset + shownBreaks.count { it < offset }

        /** The other way: the space for the i-th break sits at shown position break + i; each one before takes one off. */
        override fun transformedToOriginal(offset: Int): Int =
            (offset - shownBreaks.withIndex().count { (i, b) -> b + i < offset }).coerceIn(0, length)
    }

    private companion object {
        const val GroupSize = 3
    }
}
