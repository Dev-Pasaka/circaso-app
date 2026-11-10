package com.circaso.designsystem.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * One amount in the composer's text drawn as money (design C3, Pasaka 2026-11-09/10): `#500` reads "KES 500" in the
 * money colour while it is typed, without its `#`, and turns into a chip ("KES 500", inverted colours) once it is
 * finished. Only the drawing changes; the typed text keeps its `#500`, so what the screen reads (the money card, the
 * note, the `@coin`) is what was typed.
 *
 * [start] is the `#`, [end] just after the last digit. While typed it reads [prefix] + the digits + [suffix] ("KES " +
 * "500", or "0.5" + " ETH"); as a chip it reads [chip], already formatted ("KES 1,500").
 */
@Immutable
data class ComposerAmount(val start: Int, val end: Int, val prefix: String, val suffix: String, val chip: String)

/**
 * How the field draws [text] with [a]: the shown text, which part is the chip (null while the amount is still being
 * typed) and the cursor mapping between the two.
 *
 * The amount is a chip once it is finished: something follows it (a space, a comma; Pasaka 2026-11-10). A chip is one
 * piece, never edited in place: the cursor stands before it or after it, never inside ([settle]), and it goes as a whole
 * (its ✕, or backspace, see [ComposerAmountKeys]). [selected] is a chip tapped (or backspaced into) once: it shows its ✕
 * ([cross]), and room is kept for it in the label. While typed, the cursor never stops between the hidden `#` and the
 * first digit (a backspace there would delete a character nobody can see).
 */
internal class AmountDisplay(text: String, private val a: ComposerAmount, selected: Boolean = false) {
    val chip: Boolean = a.end < text.length
    val selected: Boolean = chip && selected
    private val digits = text.substring(a.start + 1, a.end)
    // Glued to a word ("lunch#500"), the money keeps a hair of space from it rather than reading "lunchKES 500".
    private val prefix = (if (a.start > 0 && !text[a.start - 1].isWhitespace()) Narrow.toString() else "") + a.prefix.replace(' ', Nbsp)
    private val chipLabel = a.chip.replace(' ', Nbsp)
    private val label: String = when {
        this.selected -> Pad + chipLabel + CrossRoom + Pad
        chip -> Pad + chipLabel + Pad
        else -> prefix + digits + a.suffix.replace(' ', Nbsp)
    }
    val shown: String = text.substring(0, a.start) + label + text.substring(a.end)
    /** Where the amount is in [shown]. */
    val range: IntRange = a.start until a.start + label.length
    /** The chip's glyphs in [shown]: its label, and the ✕'s room when [selected] (not the margin around it). */
    val pill: IntRange? = if (chip) (a.start + Pad.length) until (a.start + label.length - Pad.length) else null
    /** The chip's label alone ("KES 500") in [shown], drawn in the chip's colours. */
    val pillLabel: IntRange? = if (chip) (a.start + Pad.length) until (a.start + Pad.length + chipLabel.length) else null
    /** The room the ✕ is drawn in, at the chip's end, while [selected]. */
    val cross: IntRange? = if (this.selected) (a.start + Pad.length + chipLabel.length + CrossGap) until (a.start + label.length - Pad.length) else null
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
         * Room kept on each side of the chip's label: the chip's own 10 dp sides are drawn into it, and what is left is
         * its margin from the words around it.
         */
        private const val Pad = "    "
        /** One space between the label and the ✕, then the ✕'s own room (about an em: an 11 dp mark with air). */
        private const val CrossGap = 1
        private const val CrossRoom = "     "
    }
}

/**
 * Where [d]'s chip and its ✕ are in the field, from the field's [r] (laid out from [AmountDisplay.shown]); null while
 * there is no chip, the layout is of another text, the chip wraps across lines, or the field scrolls (past five lines).
 * [side] and [grow] are the chip's drawn room around its glyphs (10 dp sides, 2 dp above and below the line).
 */
internal class ChipBounds(val chip: Rect, val cross: Rect?)

internal fun chipBounds(r: TextLayoutResult?, d: AmountDisplay?, side: Float, grow: Float): ChipBounds? {
    if (r == null || d == null) return null
    val p = d.pill ?: return null
    if (r.layoutInput.text.text != d.shown || r.lineCount > 5 || p.last >= d.shown.length) return null
    val line = r.getLineForOffset(p.first)
    if (r.getLineForOffset(p.last) != line) return null
    val first = r.getBoundingBox(p.first)
    val last = r.getBoundingBox(p.last)
    val top = r.getLineTop(line) - grow
    val bottom = r.getLineBottom(line) + grow
    val chip = Rect(minOf(first.left, last.left) - side, top, maxOf(first.right, last.right) + side, bottom)
    val cross = d.cross?.let { x ->
        val a = r.getBoundingBox(x.first)
        val b = r.getBoundingBox(x.last)
        Rect(minOf(a.left, b.left), top, maxOf(a.right, b.right), bottom)
    }
    return ChipBounds(chip, cross)
}

/**
 * The field's [VisualTransformation] for [amount]: the shown text with [money] on an amount being typed (and the chip's
 * room), [chipText] on a chip's label, and the cursor mapping. [selected]: the chip shows its ✕.
 */
internal fun amountTransformation(amount: (String) -> ComposerAmount?, selected: Boolean, money: SpanStyle, chipText: SpanStyle): VisualTransformation =
    VisualTransformation { original ->
        val a = amount(original.text)?.takeIf { it.start >= 0 && it.end <= original.length && it.start < it.end }
            ?: return@VisualTransformation TransformedText(original, OffsetMapping.Identity)
        val d = AmountDisplay(original.text, a, selected)
        val shown = AnnotatedString.Builder(d.shown).apply {
            addStyle(money, d.range.first, d.range.last + 1)
            d.pillLabel?.let { addStyle(chipText, it.first, it.last + 1) }
        }.toAnnotatedString()
        TransformedText(shown, object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = d.toShown(offset).coerceIn(0, shown.length)
            override fun transformedToOriginal(offset: Int) = d.toTyped(offset).coerceIn(0, original.length)
        })
    }

/**
 * Where the cursor may stand in [text] (the field's [next] selection; [previous] the one before it): never between an
 * amount's hidden `#` and its first digit (it moves before the `#`), and never inside a chip (Pasaka 2026-11-10): there
 * it goes past the chip the way it was moving, after the space that finished the amount when moving right (or tapped),
 * before the `#` when moving left. A chip is removed whole, never edited a digit at a time.
 */
internal fun ComposerAmount.settle(text: String, next: TextRange, previous: TextRange? = null): TextRange {
    if (!next.collapsed) return next
    val at = next.start
    if (end >= text.length) return if (at == start + 1) TextRange(start) else next
    if (at <= start || at > end) return next
    val movingLeft = previous != null && previous.collapsed && previous.start > at && previous.start <= end + 1
    return if (movingLeft) TextRange(start) else TextRange(end + 1)
}

/**
 * The text with the chip [this] taken out, and where the cursor goes (where the chip was). The space that finished it
 * goes too when the amount stood as its own word ("hi #500 thanks" → "hi thanks"), so no double space is left; glued to
 * a word ("lunch#500 thanks") only the amount goes ("lunch thanks").
 */
internal fun ComposerAmount.removeFrom(text: String): Pair<String, TextRange> {
    val ownWord = start == 0 || text[start - 1].isWhitespace()
    val to = if (ownWord && end < text.length && text[end].isWhitespace()) end + 1 else end
    return text.removeRange(start, to) to TextRange(start)
}

/**
 * Backspace right after a chip (Pasaka 2026-11-10). Deleting the space that finished it would glue the next word to the
 * amount ("#500thanks", no longer money) or turn the chip back into digits to pick at; instead the first backspace selects
 * the chip (it shows its ✕), and a second one removes the whole amount. Chosen over removing it at once: one stray
 * backspace never throws away money the person typed, and the ✕ shows what the next press will do (iOS Mail's tokens).
 */
internal object ComposerAmountKeys {
    sealed interface Result {
        val text: String
        val selection: TextRange

        /** Keep the text as it was; the chip is now selected. */
        data class Select(override val text: String, override val selection: TextRange) : Result

        /** The chip is gone. */
        data class Remove(override val text: String, override val selection: TextRange) : Result
    }

    /**
     * What a backspace from [oldText] (cursor [oldSelection]) to [newText] (cursor [newSelection]) does to the chip
     * [before] (the amount in [oldText]); null when the edit is not a backspace at a chip, and is taken as it is.
     */
    fun backspaceAtChip(
        oldText: String,
        oldSelection: TextRange,
        newText: String,
        newSelection: TextRange,
        before: ComposerAmount?,
        selected: Boolean,
    ): Result? {
        val a = before ?: return null
        if (a.end >= oldText.length || !oldText[a.end].isWhitespace()) return null
        if (!oldSelection.collapsed || !newSelection.collapsed || newText.length != oldText.length - 1) return null
        // The finishing space (cursor after it) or, should the cursor stand at the chip's edge, its last digit.
        val deleted = newSelection.start
        val atChip = (deleted == a.end && oldSelection.start == a.end + 1) || (deleted == a.end - 1 && oldSelection.start == a.end)
        if (!atChip || oldText.removeRange(deleted, deleted + 1) != newText) return null
        return if (selected) a.removeFrom(oldText).let { (t, c) -> Result.Remove(t, c) }
        else Result.Select(oldText, TextRange(a.end + 1))
    }
}
