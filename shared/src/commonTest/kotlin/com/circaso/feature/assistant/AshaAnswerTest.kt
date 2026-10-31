package com.circaso.feature.assistant

import com.circaso.feature.assistant.presentation.AnswerPart
import com.circaso.feature.assistant.presentation.answerParts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Asha's answers split into what the app draws: markdown, charts, statements and diagrams (the output contract). */
class AshaAnswerTest {
    @Test fun plainChatIsJustText() {
        val parts = answerParts("Habari! How can I help with your money today?")
        assertEquals(1, parts.size)
        assertIs<AnswerPart.Text>(parts[0])
    }

    @Test fun aChartBetweenTextBecomesItsOwnPart() {
        val text = """
            Here's your spending by month:
            ```chart
            {"type":"bar","title":"Spending","unit":"KES","series":[{"name":"Out","points":[{"label":"Sep","value":1200},{"label":"Oct","value":950.5}]}]}
            ```
            October was lower.
        """.trimIndent()
        val parts = answerParts(text)
        assertEquals(3, parts.size)
        val chart = assertIs<AnswerPart.Chart>(parts[1])
        assertEquals("bar", chart.type)
        assertEquals(listOf("Sep" to 1200.0, "Oct" to 950.5), chart.series.single().points)
    }

    @Test fun aStatementReadsItsRowsAndTotals() {
        val text = "```statement\n{\"title\":\"October\",\"period\":\"1–31 Oct\",\"rows\":[{\"date\":\"Oct 2\",\"description\":\"To Bob\",\"amount\":\"-500\"}],\"totals\":{\"out\":\"500\"}}\n```"
        val s = assertIs<AnswerPart.Statement>(answerParts(text).single())
        assertEquals(listOf("date", "description", "amount"), s.columns)
        assertEquals(listOf("out" to "500"), s.totals)
    }

    @Test fun theContractStatementShowsAmountsWithTheirCoinAndTotalsAsALIst() {
        val text = "```statement\n{\"title\":\"October\",\"period\":{\"from\":\"2026-10-01\",\"to\":\"2026-10-31\"},\"currency\":\"KES\"," +
            "\"rows\":[{\"date\":\"2026-10-02\",\"description\":\"To Bob\",\"direction\":\"OUT\",\"amount\":\"5\",\"asset\":\"USDC\",\"fiat\":\"650\"}]," +
            "\"totals\":[{\"label\":\"Out\",\"amount\":\"5\",\"asset\":\"USDC\",\"fiat\":\"650\"}]}\n```"
        val s = assertIs<AnswerPart.Statement>(answerParts(text).single())
        assertEquals("2026-10-01 – 2026-10-31", s.period)
        assertEquals(listOf("2026-10-02", "To Bob", "−5 USDC", "KES 650"), s.rows.single())
        assertEquals(listOf("Out" to "5 USDC · KES 650"), s.totals)
    }

    @Test fun aDiagramKeepsOnlyEdgesBetweenItsNodes() {
        val text = "```diagram\n{\"nodes\":[{\"id\":\"a\",\"label\":\"You\"},{\"id\":\"b\",\"label\":\"Bob\"}],\"edges\":[{\"from\":\"a\",\"to\":\"b\",\"label\":\"KES 500\"},{\"from\":\"a\",\"to\":\"zz\"}]}\n```"
        val d = assertIs<AnswerPart.Diagram>(answerParts(text).single())
        assertEquals(1, d.edges.size)
    }

    @Test fun aBrokenBlockShowsNothingRatherThanRawJson() {
        val parts = answerParts("Before\n```chart\n{not json\n```\nAfter")
        assertTrue(parts.all { it is AnswerPart.Text })
        assertTrue(parts.none { (it as AnswerPart.Text).markdown.contains("not json") })
    }
}
