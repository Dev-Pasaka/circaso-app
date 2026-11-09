package com.circaso.feature.messaging

import androidx.compose.ui.text.TextRange
import com.circaso.designsystem.components.AmountDisplay
import com.circaso.designsystem.components.ComposerAmount
import com.circaso.designsystem.components.ComposerAmountKeys
import com.circaso.designsystem.components.settle
import com.circaso.feature.messaging.presentation.composerAmount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The composer draws `#500` as money while it is typed, and as a pill once typed past (C3, 2026-11-09). */
class ComposerAmountTest {
    private fun kes(text: String): ComposerAmount {
        val t = assertNotNull(com.circaso.feature.messaging.presentation.amountToken(text))
        return ComposerAmount(t.start, t.end, "KES ", "", "KES " + t.amount)
    }

    /** What a person would read: the shown text with its no-break and narrow spaces as plain ones. */
    private fun read(d: AmountDisplay) = d.shown.replace(' ', ' ').replace(" ", "")

    @Test fun whileTypedItReadsAsMoneyWithoutTheHash() {
        val text = "lunch#500"
        val d = AmountDisplay(text, kes(text), TextRange(text.length))
        assertFalse(d.chip)
        assertEquals("lunchKES 500", read(d))
        // The cursor after the last digit is after the last digit on screen; the `#` has no place of its own.
        assertEquals(d.shown.length, d.toShown(text.length))
        assertEquals(text.length, d.toTyped(d.shown.length))
        assertEquals("lunch\u202FKES ".length, d.toShown(6), "just after the hidden # is just before the 5")
    }

    @Test fun theCursorNeverLandsBetweenTheHashAndTheDigits() {
        val text = "#500"
        val a = kes(text)
        val d = AmountDisplay(text, a, TextRange(text.length))
        // Tapping in "KES " or just before the 5 puts the cursor before the hidden `#`.
        for (shown in 0.."KES ".length) assertEquals(0, d.toTyped(shown))
        assertEquals(2, d.toTyped("KES 5".length), "between 5 and 0")
        assertEquals(TextRange(0), a.settle(TextRange(1)))
        assertEquals(TextRange(3), a.settle(TextRange(3)))
    }

    @Test fun typedPastItBecomesAPillTheRestStaysAsTyped() {
        val text = "lunch#500 thanks"
        val d = AmountDisplay(text, kes(text), TextRange(text.length))
        assertTrue(d.chip)
        assertEquals("lunchKES 500 thanks", read(d))
        val pill = assertNotNull(d.pill)
        assertTrue(d.shown.substring(pill.first, pill.last + 1).contains("KES 500"), "KES 500 never wraps apart")
        // " thanks" keeps its place after the pill.
        assertEquals(d.shown.length, d.toShown(text.length))
        assertEquals(text.length, d.toTyped(d.shown.length))
        // Inside the pill the cursor goes to its nearer edge: the pill moves as one.
        assertEquals(5, d.toTyped(d.range.first + 1))
        assertEquals(9, d.toTyped(d.range.last))
    }

    @Test fun withTheCursorRightAfterItTheAmountIsEditableAgain() {
        val text = "#500 thanks"
        val d = AmountDisplay(text, kes(text), TextRange(4))
        assertFalse(d.chip)
        assertNull(d.pill)
    }

    @Test fun backspaceAfterThePillStepsIntoItInsteadOfGluingTheNextWord() {
        val old = "#500 thanks"
        val new = "#500thanks"
        val kept = ComposerAmountKeys.backspaceIntoPill(old, new, TextRange(4), kes(old), com.circaso.feature.messaging.presentation.amountToken(new)?.let { kes(new) })
        assertEquals(old to TextRange(4), kept)
        // At the end of the text the space simply goes: the amount is still the amount.
        val end = "#500 "
        assertNull(ComposerAmountKeys.backspaceIntoPill(end, "#500", TextRange(4), kes(end), kes("#500")))
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
