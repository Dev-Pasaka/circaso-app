package com.circaso.feature.calls

import androidx.compose.ui.unit.dp
import com.circaso.feature.calls.presentation.fitTiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitTilesTest {
    @Test
    fun aPhoneUprightPutsSixPeopleTwoAcross() {
        val (cols, width) = fitTiles(6, 358.dp, 560.dp, 8.dp, 1.15f)
        assertEquals(2, cols)
        assertTrue(width > 150.dp)
    }

    @Test
    fun anIpadInLandscapeSpreadsFivePeopleThreeAcross() {
        val (cols, _) = fitTiles(5, 1344.dp, 860.dp, 8.dp, 1.15f)
        assertEquals(3, cols)
    }

    @Test
    fun aPhoneOnItsSideKeepsEveryoneOnScreen() {
        // Beside the live goal: about 430 x 195 dp for the tiles.
        val (cols, width) = fitTiles(5, 430.dp, 195.dp, 8.dp, 1.15f)
        val rows = (5 + cols - 1) / cols
        assertTrue(rows * (width / 1.15f) + 8.dp * (rows - 1) <= 195.dp + 0.5.dp)
        assertTrue(cols * width + 8.dp * (cols - 1) <= 430.dp + 0.5.dp)
    }

    @Test
    fun oneTileFillsTheSpaceItHas() {
        val (cols, width) = fitTiles(1, 400.dp, 300.dp, 8.dp, 1.15f)
        assertEquals(1, cols)
        assertEquals(300.dp * 1.15f, width)
    }
}
