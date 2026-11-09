package com.circaso.designsystem.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * One amount in the composer's text drawn as money (design C3, Pasaka 2026-11-09): `#500` reads "KES 500" in the money
 * colour while it is typed, without its `#`, and turns into a small pill ("KES 500") once something follows it. Only the
 * drawing changes; the typed text keeps its `#500`, so what the screen reads (the money card, the note) is what was typed.
 *
 * [start] is the `#`, [end] just after the last digit. While typed it reads [prefix] + the digits + [suffix] ("KES " +
 * "500", or "0.5" + " ETH"); as a pill it reads [chip], already formatted ("KES 1,500").
 */
@Immutable
data class ComposerAmount(val start: Int, val end: Int, val prefix: String, val suffix: String, val chip: String)

/**
 * How the field draws [text] with [amount]: the shown text, which part is the pill (null while the amount is still being
 * typed) and the cursor mapping between the two.
 *
 * The amount is a pill once something follows it, unless the cursor sits right after its last digit: that is where the
 * person edits it, so there it reads as typed money again (backspace from after the pill lands there, see
 * [ComposerAmountKeys]). The cursor never stops between the hidden `#` and the first digit (a backspace there would
 * delete a character nobody can see); inside a pill it goes to whichever edge is nearer, so the pill moves as one.
 */
internal class AmountDisplay(text: String, private val a: ComposerAmount, cursor: TextRange) {
    val chip: Boolean = a.end < text.length && !(cursor.collapsed && cursor.start == a.end)
    private val digits = text.substring(a.start + 1, a.end)
    // Glued to a word ("lunch#500"), the money keeps a hair of space from it rather than reading "lunchKES 500".
    private val prefix = (if (a.start > 0 && !text[a.start - 1].isWhitespace()) Narrow.toString() else "") + a.prefix.replace(' ', Nbsp)
    private val label: String = if (chip) Pad + a.chip.replace(' ', Nbsp) + Pad else prefix + digits + a.suffix.replace(' ', Nbsp)
    val shown: String = text.substring(0, a.start) + label + text.substring(a.end)
    /** Where the amount is in [shown]. */
    val range: IntRange = a.start until a.start + label.length
    /** The pill's label in [shown] (without the room around it): the field draws the pill around these glyphs. */
    val pill: IntRange? = if (chip) (a.start + Pad.length) until (a.start + label.length - Pad.length) else null
    private val delta = label.length - (a.end - a.start)

    fun toShown(offset: Int): Int {
        val s = a.start
        return when {
            offset <= s -> offset
            offset > a.end -> offset + delta
            chip -> if (offset == a.end || (offset - s) * 2 > a.end - s) s + label.length else s
            // Typed: the digits keep their places after the prefix; the end of the digits sits before any suffix.
            else -> s + prefix.length + (offset - s - 1).coerceAtLeast(0)
        }
    }

    fun toTyped(offset: Int): Int {
        val s = a.start
        val l = label.length
        return when {
            offset <= s -> offset
            offset >= s + l -> offset - delta
            chip -> if ((offset - s) * 2 > l) a.end else s
            // In the prefix, or just before the first digit: before the `#`, never between it and the digits.
            offset <= s + prefix.length -> s
            offset >= s + prefix.length + digits.length -> a.end
            else -> s + 1 + (offset - s - prefix.length)
        }
    }

    companion object {
        /** No-break spaces, so "KES 500" never wraps between its parts. */
        private const val Nbsp = ' '
        /** A narrow no-break space (about a fifth of the type size). */
        private const val Narrow = ' '
        /**
         * Room kept on each side of the pill's label: the pill's own 10 dp sides are drawn into it (C3's `.cx-chip-money`,
         * `padding: 0 10px`), and what is left is its margin from the words around it.
         */
        private const val Pad = "    "
    }
}

/** The field's [VisualTransformation] for [amount]: the shown text with [money] on the amount, and its cursor mapping. */
internal fun amountTransformation(amount: (String) -> ComposerAmount?, cursor: TextRange, money: SpanStyle): VisualTransformation =
    VisualTransformation { original ->
        val a = amount(original.text)?.takeIf { it.start >= 0 && it.end <= original.length && it.start < it.end }
            ?: return@VisualTransformation TransformedText(original, OffsetMapping.Identity)
        val d = AmountDisplay(original.text, a, cursor)
        val shown = AnnotatedString.Builder(d.shown).apply { addStyle(money, d.range.first, d.range.last + 1) }.toAnnotatedString()
        TransformedText(shown, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = d.toShown(offset).coerceIn(0, shown.length)
            override fun transformedToOriginal(offset: Int) = d.toTyped(offset).coerceIn(0, original.length)
        })
    }

/** Where the cursor may stand in [text]: never between an amount's hidden `#` and its first digit (it moves before the `#`). */
internal fun ComposerAmount.settle(selection: TextRange): TextRange =
    if (selection.collapsed && selection.start == start + 1) TextRange(start) else selection

/**
 * Backspace right after a pill (the space that ends it): deleting that space would glue the next word to the amount
 * ("#500lunch", no longer money), so instead the cursor steps into the pill, which opens it for editing ("KES 500|").
 * Returns the value to keep instead of [newText], or null to take the edit as it is.
 */
internal object ComposerAmountKeys {
    fun backspaceIntoPill(oldText: String, newText: String, newCursor: TextRange, before: ComposerAmount?, after: ComposerAmount?): Pair<String, TextRange>? {
        val a = before ?: return null
        val deletedAfterAmount = newText.length == oldText.length - 1 && newCursor.collapsed && newCursor.start == a.end &&
            a.end < oldText.length && oldText.removeRange(a.end, a.end + 1) == newText
        if (!deletedAfterAmount) return null
        // Still the same amount with the space gone (it ended the text, or a comma follows): a normal backspace.
        if (after != null && after.start == a.start && after.end == a.end) return null
        return oldText to TextRange(a.end)
    }
}
