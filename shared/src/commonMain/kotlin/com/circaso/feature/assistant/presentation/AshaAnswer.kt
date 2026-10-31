package com.circaso.feature.assistant.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.circaso.core.platform.sharePdf
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.model.DefaultMarkdownColors
import com.mikepenz.markdown.model.DefaultMarkdownTypography
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One piece of an Asha answer: markdown text, a chart she asked the app to draw, or a statement it can turn into a PDF. */
sealed interface AnswerPart {
    data class Text(val markdown: String) : AnswerPart
    data class Chart(val type: String, val title: String?, val unit: String?, val series: List<Series>) : AnswerPart
    data class Statement(val title: String, val period: String?, val columns: List<String>, val rows: List<List<String>>, val totals: List<Pair<String, String>>) : AnswerPart
    data class Series(val name: String?, val points: List<Pair<String, Double>>)
    data class Diagram(val title: String?, val nodes: List<Pair<String, String>>, val edges: List<Triple<String, String, String?>>) : AnswerPart
}

private val Fence = Regex("""```(chart|statement|diagram)\s*\n([\s\S]*?)```""")
private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Splits an answer into its parts (the output contract in the mobile guide, Asha section): fenced ```chart and
 * ```statement blocks become native views, everything else stays markdown. A block that doesn't parse is shown as
 * nothing rather than as raw JSON (the server also drops broken ones).
 */
fun answerParts(text: String): List<AnswerPart> {
    val parts = mutableListOf<AnswerPart>()
    var at = 0
    Fence.findAll(text).forEach { m ->
        text.substring(at, m.range.first).trim().takeIf { it.isNotEmpty() }?.let { parts += AnswerPart.Text(it) }
        val body = runCatching { Lenient.parseToJsonElement(m.groupValues[2]).jsonObject }.getOrNull()
        when (m.groupValues[1]) {
            "chart" -> body?.let(::chartOf)?.let { parts += it }
            "statement" -> body?.let(::statementOf)?.let { parts += it }
            "diagram" -> body?.let(::diagramOf)?.let { parts += it }
        }
        at = m.range.last + 1
    }
    text.substring(at).trim().takeIf { it.isNotEmpty() }?.let { parts += AnswerPart.Text(it) }
    return parts
}

private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun chartOf(o: JsonObject): AnswerPart.Chart? {
    val series = (o["series"] as? JsonArray)?.mapNotNull { s ->
        val obj = s as? JsonObject ?: return@mapNotNull null
        val points = (obj["points"] as? JsonArray)?.mapNotNull { p ->
            val po = p as? JsonObject ?: return@mapNotNull null
            val v = (po["value"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            (po["label"].str() ?: "") to v
        }.orEmpty()
        AnswerPart.Series(obj["name"].str(), points).takeIf { points.isNotEmpty() }
    }.orEmpty()
    if (series.isEmpty()) return null
    return AnswerPart.Chart(o["type"].str() ?: "bar", o["title"].str(), o["unit"].str(), series)
}

private fun diagramOf(o: JsonObject): AnswerPart.Diagram? {
    val nodes = (o["nodes"] as? JsonArray)?.mapNotNull { n -> (n as? JsonObject)?.let { (it["id"].str() ?: return@mapNotNull null) to (it["label"].str() ?: "") } }.orEmpty().take(8)
    if (nodes.isEmpty()) return null
    val ids = nodes.map { it.first }.toSet()
    val edges = (o["edges"] as? JsonArray)?.mapNotNull { e ->
        val eo = e as? JsonObject ?: return@mapNotNull null
        val from = eo["from"].str() ?: return@mapNotNull null
        val to = eo["to"].str() ?: return@mapNotNull null
        if (from !in ids || to !in ids) null else Triple(from, to, eo["label"].str())
    }.orEmpty()
    return AnswerPart.Diagram(o["title"].str(), nodes, edges)
}

private fun statementOf(o: JsonObject): AnswerPart.Statement? {
    val rows = (o["rows"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    if (rows.isEmpty()) return null
    val columns = (o["columns"] as? JsonArray)?.mapNotNull { it.str() } ?: rows.first().keys.toList()
    val cells = rows.map { r -> columns.map { k -> r[k]?.let { v -> v.str() ?: v.toString() }.orEmpty() } }
    val totals = when (val t = o["totals"]) {
        is JsonObject -> t.entries.map { (k, v) -> k to (v.str() ?: v.toString()) }
        is JsonArray -> t.mapNotNull { e -> (e as? JsonObject)?.let { (it["label"].str() ?: "") to (it["value"].str() ?: "") } }
        else -> emptyList()
    }
    return AnswerPart.Statement(o["title"].str() ?: "Statement", o["period"].str(), columns, cells, totals)
}

/** An Asha answer in her bubble: markdown in Circaso's type, charts drawn here, statements with Share as PDF. */
@Composable
fun AshaAnswer(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) { answerParts(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
        parts.forEach { part ->
            when (part) {
                is AnswerPart.Text -> AshaMarkdown(part.markdown)
                is AnswerPart.Chart -> AshaChart(part)
                is AnswerPart.Statement -> AshaStatement(part)
                is AnswerPart.Diagram -> AshaDiagram(part)
            }
        }
    }
}

@Composable
private fun AshaMarkdown(markdown: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val body = t.body.copy(color = c.ink)
    Markdown(
        markdown,
        colors = DefaultMarkdownColors(
            text = c.ink, codeBackground = c.fill, inlineCodeBackground = c.fill, dividerColor = c.separator,
            tableBackground = c.fill, highlightText = c.ink, highlightBackground = c.fillStrong,
        ),
        typography = DefaultMarkdownTypography(
            h1 = t.title2.copy(color = c.ink), h2 = t.title3.copy(color = c.ink), h3 = t.headline.copy(color = c.ink),
            h4 = t.headline.copy(color = c.ink), h5 = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), h6 = t.subhead.copy(color = c.inkSecondary),
            text = body, code = t.footnote.copy(color = c.ink, fontFamily = FontFamily.Monospace), inlineCode = body.copy(fontFamily = FontFamily.Monospace),
            quote = body.copy(color = c.inkSecondary), paragraph = body, ordered = body, bullet = body, list = body,
            textLink = TextLinkStyles(style = body.copy(color = c.action, fontWeight = FontWeight.SemiBold).toSpanStyle()),
            table = t.footnote.copy(color = c.ink), alertTitle = t.headline.copy(color = c.ink),
        ),
    )
}

/** A chart Asha asked for, drawn in ink tones (the UI has no colour; series differ by shade). */
@Composable
private fun AshaChart(chart: AnswerPart.Chart) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val shades = listOf(c.ink, c.inkSecondary, c.inkTertiary, c.fillStrong)
    val all = chart.series.flatMap { s -> s.points }
    val max = all.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    val spoken = (chart.title?.let { "$it. " } ?: "") + chart.series.joinToString(". ") { s ->
        (s.name?.let { "$it: " } ?: "") + s.points.joinToString(", ") { (l, v) -> "$l ${fmt(v)}${chart.unit?.let { " $it" } ?: ""}" }
    }
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4).semantics { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
    ) {
        chart.title?.let { BasicText(it, style = t.headline.copy(color = c.ink)) }
        chart.unit?.let { BasicText(it, style = t.caption1.copy(color = c.inkSecondary)) }
        when (chart.type.lowercase()) {
            "pie" -> {
                val points = chart.series.first().points
                val total = points.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s4)) {
                    Canvas(Modifier.size(120.dp)) {
                        var start = -90f
                        points.forEachIndexed { i, (_, v) ->
                            val sweep = (v / total * 360f).toFloat()
                            drawArc(shades[i % shades.size], start, sweep, useCenter = false, style = Stroke(width = size.minDimension * 0.22f))
                            start += sweep
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        points.forEachIndexed { i, (l, v) -> Legend(shades[i % shades.size], "$l · ${fmt(v)}") }
                    }
                }
            }
            "line" -> {
                Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                    chart.series.forEachIndexed { si, s ->
                        val step = if (s.points.size > 1) size.width / (s.points.size - 1) else 0f
                        val path = Path()
                        s.points.forEachIndexed { i, (_, v) ->
                            val x = i * step
                            val y = size.height - (v / max * size.height).toFloat()
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, shades[si % shades.size], style = Stroke(width = 3.dp.toPx()))
                    }
                }
                Labels(chart.series.first().points.map { it.first })
            }
            else -> {
                // Bars: one group per label, a bar per series.
                val labels = chart.series.first().points.map { it.first }
                Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                    val group = size.width / labels.size.coerceAtLeast(1)
                    val bar = (group * 0.7f) / chart.series.size
                    labels.indices.forEach { li ->
                        chart.series.forEachIndexed { si, s ->
                            val v = s.points.getOrNull(li)?.second ?: return@forEachIndexed
                            val h = (v / max * size.height).toFloat()
                            drawRoundRect(
                                shades[si % shades.size],
                                topLeft = Offset(li * group + group * 0.15f + si * bar, size.height - h),
                                size = Size(bar - 2.dp.toPx(), h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                            )
                        }
                    }
                }
                Labels(labels)
            }
        }
        if (chart.series.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3)) {
                chart.series.forEachIndexed { i, s -> Legend(shades[i % shades.size], s.name ?: "Series ${i + 1}") }
            }
        }
    }
}

@Composable
private fun Labels(labels: List<String>) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(Modifier.fillMaxWidth()) {
        labels.forEach { BasicText(it, Modifier.weight(1f), style = t.caption2.copy(color = c.inkSecondary, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircasoShapes.circle).background(color))
        BasicText(text, style = t.caption1.copy(color = c.ink))
    }
}

/**
 * A diagram Asha drew to explain something (how a payment moves): boxes top to bottom, joined by arrows, in a
 * pencil line (a slightly uneven double stroke), so it reads as a sketch rather than a chart. Edges that don't join
 * neighbours are listed under it.
 */
@Composable
private fun AshaDiagram(d: AnswerPart.Diagram) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val pencil = c.inkSecondary
    val spoken = (d.title?.let { "$it. " } ?: "") + d.edges.joinToString(". ") { (f, to, l) ->
        "${d.nodes.first { it.first == f }.second} to ${d.nodes.first { it.first == to }.second}" + (l?.let { ", $it" } ?: "")
    }
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4).semantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        d.title?.let { BasicText(it, style = t.headline.copy(color = c.ink)); Spacer(Modifier.height(CircasoSpacing.s3)) }
        d.nodes.forEachIndexed { i, (id, label) ->
            Box(Modifier.fillMaxWidth(0.75f).pencilBox(pencil, seed = i).padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3), contentAlignment = Alignment.Center) {
                BasicText(label, style = t.subhead.copy(color = c.ink, textAlign = TextAlign.Center))
            }
            val next = d.nodes.getOrNull(i + 1)
            if (next != null) {
                val edge = d.edges.firstOrNull { it.first == id && it.second == next.first }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
                    Canvas(Modifier.width(16.dp).height(36.dp)) {
                        val x = size.width / 2
                        val stroke = Stroke(width = 1.6.dp.toPx())
                        val shaft = Path().apply { moveTo(x - 0.6f, 2f); quadraticTo(x + 1.4f, size.height / 2, x, size.height - 3f) }
                        drawPath(shaft, if (edge != null) pencil else pencil.copy(alpha = 0.25f), style = stroke)
                        val head = Path().apply { moveTo(x - 5.dp.toPx(), size.height - 9.dp.toPx()); lineTo(x, size.height - 2f); lineTo(x + 5.dp.toPx(), size.height - 8.dp.toPx()) }
                        drawPath(head, if (edge != null) pencil else pencil.copy(alpha = 0.25f), style = stroke)
                    }
                    edge?.third?.let { BasicText(it, style = t.caption1.copy(color = c.inkSecondary)) }
                }
            }
        }
        val others = d.edges.filter { (f, to, _) -> d.nodes.indexOfFirst { it.first == to } != d.nodes.indexOfFirst { it.first == f } + 1 }
        others.forEach { (f, to, l) ->
            BasicText(
                "${d.nodes.first { it.first == f }.second} → ${d.nodes.first { it.first == to }.second}" + (l?.let { " · $it" } ?: ""),
                style = t.caption1.copy(color = c.inkSecondary), modifier = Modifier.padding(top = CircasoSpacing.s2),
            )
        }
    }
}

/** A hand-drawn box: two slightly offset, slightly wobbly rounded outlines, the same every time for the same [seed]. */
private fun Modifier.pencilBox(color: Color, seed: Int): Modifier = this.then(
    Modifier.drawBehind {
        val r = 10.dp.toPx()
        val wobble = 1.2.dp.toPx()
        listOf(0f, 1f).forEach { pass ->
            val j = ((seed * 7 + pass.toInt() * 3) % 5 - 2) / 2f * wobble
            drawRoundRect(
                color.copy(alpha = if (pass == 0f) 0.9f else 0.45f),
                topLeft = Offset(j, -j / 2),
                size = Size(size.width - j, size.height + j / 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r + j),
                style = Stroke(width = (1.4f + pass * 0.4f).dp.toPx()),
            )
        }
    },
)

/** A statement Asha put together: its rows and totals, and a PDF of it to keep or send. */
@Composable
private fun AshaStatement(s: AnswerPart.Statement) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.md).background(c.surface).padding(CircasoSpacing.s4), verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s2)) {
        BasicText(s.title, style = t.headline.copy(color = c.ink))
        s.period?.let { BasicText(it, style = t.footnote.copy(color = c.inkSecondary)) }
        Row(Modifier.fillMaxWidth()) {
            s.columns.forEach { BasicText(it.replaceFirstChar { ch -> ch.uppercase() }, Modifier.weight(1f), style = t.caption1.copy(color = c.inkSecondary, fontWeight = FontWeight.SemiBold), maxLines = 1) }
        }
        s.rows.take(30).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { BasicText(it, Modifier.weight(1f), style = t.footnote.copy(color = c.ink), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (s.rows.size > 30) BasicText("and ${s.rows.size - 30} more in the PDF", style = t.caption1.copy(color = c.inkSecondary))
        if (s.totals.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            s.totals.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth()) {
                    BasicText(k.replaceFirstChar { ch -> ch.uppercase() }, Modifier.weight(1f), style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                    BasicText(v, style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                }
            }
        }
        Spacer(Modifier.width(1.dp))
        CircasoButton("Share as PDF", { sharePdf(s.title, statementLines(s)) }, style = ButtonStyle.Secondary, size = ButtonSize.Medium, block = true)
    }
}

/** The statement as plain lines for the PDF: title, period, a header, each row, then the totals. */
internal fun statementLines(s: AnswerPart.Statement): List<String> = buildList {
    s.period?.let { add(it) }
    add("")
    add(s.columns.joinToString("   ") { it.replaceFirstChar { ch -> ch.uppercase() } })
    s.rows.forEach { add(it.joinToString("   ")) }
    if (s.totals.isNotEmpty()) {
        add("")
        s.totals.forEach { (k, v) -> add("${k.replaceFirstChar { ch -> ch.uppercase() }}: $v") }
    }
    add("")
    add("Made by Asha in Circaso")
}

private fun fmt(v: Double): String = if (v == kotlin.math.floor(v)) v.toLong().toString() else ((v * 100).toLong() / 100.0).toString()
