package com.circaso.feature.auth.presentation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Shows `712345678` as `712 345 678` without changing what the field holds, so typing, deleting and moving the
 * cursor behave normally. Groups of three, a space between groups.
 */
object PhoneNumberTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val shown = digits.chunked(GroupSize).joinToString(" ")
        return TransformedText(AnnotatedString(shown), Mapping)
    }

    private const val GroupSize = 3

    object Mapping : OffsetMapping {
        /** Position in the digits to position in the shown text: one space for every full group before it. */
        override fun originalToTransformed(offset: Int): Int = if (offset <= 0) 0 else offset + (offset - 1) / GroupSize

        /** The other way: positions after a space map back to the digit before it. */
        override fun transformedToOriginal(offset: Int): Int = (offset - offset / (GroupSize + 1)).coerceAtLeast(0)
    }
}
