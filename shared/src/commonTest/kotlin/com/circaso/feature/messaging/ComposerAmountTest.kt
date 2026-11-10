package com.circaso.feature.messaging

import androidx.compose.ui.text.TextRange
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.ComposerAmount
import com.circaso.designsystem.components.ComposerAmountKeys
import com.circaso.designsystem.components.removeFrom
import com.circaso.designsystem.components.settle
import com.circaso.feature.messaging.presentation.composerAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The composer draws `#500` as money while it is typed, and as a chip once finished (C3, 2026-11-09/10). */
class ComposerAmountTest {
    private fun kes(text: String): ComposerAmount {
        val t = assertNotNull(com.circaso.feature.messaging.presentation.amountToken(text))
        return ComposerAmount(t.start, t.end, "KES ", "", "KES " + t.amount)
    }

    /** What a person would read: the shown text with its no-break and narrow spaces as plain ones (or none). */
    private fun read(d: AmountDisplay) = d.shown.replace(' ', ' ').replace(" ", "")

    @Test fun whileTypedItReadsAsMoneyWithoutTheHash() {
        val text = "lunch#500"
        val d = AmountDisplay(text, kes(text))
        assertFalse(d.chip)
        assertEquals("lunchKES 500", read(d))
        // The cursor after the last digit is after the last digit on screen; the `#` has no place of its own.
        assertEquals(d.shown.length, d.toShown(text.length))
        assertEquals(text.length, d.toTyped(d.shown.length))
        assertEquals("lunch KES ".length, d.toShown(6), "just after the hidden # is just before the 5")
    }

    @Test fun theCursorNeverLandsBetweenTheHashAndTheDigits() {
        val text = "#500"
        val a = kes(text)
        val d = AmountDisplay(text, a)
        // Tapping in "KES " or just before the 5 puts the cursor before the hidden `#`.
        for (shown in 0.."KES ".length) assertEquals(0, d.toTyped(shown))
        assertEquals(2, d.toTyped("KES 5".length), "between 5 and 0")
        assertEquals(TextRange(0), a.settle(text, TextRange(1)))
        assertEquals(TextRange(3), a.settle(text, TextRange(3)), "while typed, digits can be edited")
    }

    @Test fun finishedItBecomesAChipTheRestStaysAsTyped() {
        val text = "lunch#500 thanks"
        val d = AmountDisplay(text, kes(text))
        assertTrue(d.chip)
        assertEquals("lunchKES 500 thanks", read(d))
        val label = assertNotNull(d.pillLabel)
        assertEquals("KES 500", d.shown.substring(label.first, label.last + 1), "KES 500 never wraps apart")
        assertNull(d.cross, "no ✕ until the chip is tapped")
        // " thanks" keeps its place after the chip.
        assertEquals(d.shown.length, d.toShown(text.length))
        assertEquals(text.length, d.toTyped(d.shown.length))
        // Inside the chip the cursor goes to its nearer edge: the chip moves as one.
        assertEquals(5, d.toTyped(d.range.first + 1))
        assertEquals(9, d.toTyped(d.range.last))
    }

    @Test fun aChipStaysAChipWhereverTheCursorIs() {
        // Pasaka 2026-11-10: no stepping into the chip to edit it; the cursor right after it still sees the chip.
        val text = "#500 thanks"
        assertTrue(AmountDisplay(text, kes(text)).chip)
        assertFalse(AmountDisplay("#500", kes("#500")).chip, "nothing after it yet: still being typed")
    }

    @Test fun aSelectedChipMakesRoomForItsCross() {
        val text = "#500 thanks"
        val plain = AmountDisplay(text, kes(text))
        val selected = AmountDisplay(text, kes(text), selected = true)
        assertTrue(selected.selected)
        val cross = assertNotNull(selected.cross)
        val pill = assertNotNull(selected.pill)
        assertTrue(cross.first > assertNotNull(selected.pillLabel).last && cross.last == pill.last, "the ✕ is at the chip's end, inside it")
        assertTrue(selected.shown.substring(cross.first, cross.last + 1).isBlank())
        assertEquals(read(plain), read(selected).replace(Regex(" {2,}"), " "), "the words around it are the same")
        // The text after the chip still maps both ways.
        assertEquals(text.length, selected.toTyped(selected.toShown(text.length)))
        assertFalse(AmountDisplay("#500", kes("#500"), selected = true).selected, "only a chip can be selected")
    }

    @Test fun theCursorNeverStandsInsideAChip() {
        val text = "hi #500 thanks"
        val a = kes(text) // start 3, end 7
        // A tap on the chip, or moving right into it: after the space that finished it.
        assertEquals(TextRange(8), a.settle(text, TextRange(7)))
        assertEquals(TextRange(8), a.settle(text, TextRange(5)))
        assertEquals(TextRange(8), a.settle(text, TextRange(4), previous = TextRange(3)))
        // Moving left from after it: before the `#`.
        assertEquals(TextRange(3), a.settle(text, TextRange(7), previous = TextRange(8)))
        // Its edges and the rest of the text are left alone, and so is a selection.
        assertEquals(TextRange(3), a.settle(text, TextRange(3)))
        assertEquals(TextRange(8), a.settle(text, TextRange(8)))
        assertEquals(TextRange(0, 10), a.settle(text, TextRange(0, 10)))
    }

    @Test fun removingAChipTakesTheWholeAmount() {
        val own = "hi #500 thanks"
        assertEquals("hi thanks" to TextRange(3), kes(own).removeFrom(own))
        val glued = "lunch#500 thanks"
        assertEquals("lunch thanks" to TextRange(5), kes(glued).removeFrom(glued))
        val first = "#500, ok"
        assertEquals(", ok" to TextRange(0), kes(first).removeFrom(first))
        val last = "#500 "
        assertEquals("" to TextRange(0), kes(last).removeFrom(last))
    }

    @Test fun oneBackspaceAfterTheChipSelectsItTheNextRemovesIt() {
        val old = "hi #500 thanks"
        val a = kes(old)
        // The cursor after the finishing space; backspace would delete it ("hi #500thanks").
        val select = ComposerAmountKeys.backspaceAtChip(old, TextRange(8), "hi #500thanks", TextRange(7), a, selected = false)
        assertIs<ComposerAmountKeys.Result.Select>(select)
        assertEquals(old, select.text, "the text stays as it was")
        assertEquals(TextRange(8), select.selection)
        val remove = ComposerAmountKeys.backspaceAtChip(old, TextRange(8), "hi #500thanks", TextRange(7), a, selected = true)
        assertIs<ComposerAmountKeys.Result.Remove>(remove)
        assertEquals("hi thanks", remove.text)
        assertEquals(TextRange(3), remove.selection)
    }

    @Test fun backspaceAtTheEndOfTheTextWorksTheSame() {
        val old = "#500 "
        assertIs<ComposerAmountKeys.Result.Select>(ComposerAmountKeys.backspaceAtChip(old, TextRange(5), "#500", TextRange(4), kes(old), false))
        val gone = ComposerAmountKeys.backspaceAtChip(old, TextRange(5), "#500", TextRange(4), kes(old), true)
        assertEquals("", assertNotNull(gone).text)
    }

    @Test fun otherEditsAreLeftAlone() {
        val old = "hi #500 thanks"
        val a = kes(old)
        // A backspace elsewhere, typing, a comma finishing the amount, a selection deleted: taken as they are.
        assertNull(ComposerAmountKeys.backspaceAtChip(old, TextRange(14), "hi #500 thank", TextRange(13), a, false))
        assertNull(ComposerAmountKeys.backspaceAtChip(old, TextRange(8), "hi #500 Xthanks", TextRange(9), a, false))
        val comma = "#500, ok"
        assertNull(ComposerAmountKeys.backspaceAtChip(comma, TextRange(5), "#500 ok", TextRange(4), kes(comma), false))
        assertNull(ComposerAmountKeys.backspaceAtChip(old, TextRange(8, 14), "hi #500 ", TextRange(8), a, false))
        assertNull(ComposerAmountKeys.backspaceAtChip("#50", TextRange(3), "#5", TextRange(2), kes("#50"), false), "still typing")
    }

    @Test fun theUnitFollowsTheMoneyCard() {
        // Without a wallet yet the amount is in USDC, as the card would read it.
        val a = assertNotNull(composerAmount("a #12.5 @eth", null))
        assertEquals("", a.prefix)
        assertEquals(" USDC", a.suffix)
        assertEquals("12.5 USDC", a.chip)
        assertNull(composerAmount("see https://circaso.com/help#12", null), "a link's anchor stays text")
    }
}
