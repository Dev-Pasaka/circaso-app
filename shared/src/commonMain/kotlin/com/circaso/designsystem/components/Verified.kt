package com.circaso.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetActions
import com.circaso.designsystem.chrome.SheetMessage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoTheme
import kotlin.math.round

/** What the badge means, said the same way everywhere (the verified badge, 2026-11-24). */
object VerifiedCopy {
    const val Title = "Verified"
    const val Person = "Circaso verified this person's identity with a government ID and a selfie."
    const val Own = "Circaso verified your identity with a government ID and a selfie."
    const val Private = "Only the badge is shown, never their ID or selfie."
    const val PrivateOwn = "People see only the badge, never your ID or selfie."
}

/** Whether the "what this means" sheet is open: shared by a profile's name mark and the seal on its large photo. */
@Stable
class VerifiedExplainer internal constructor(open: Boolean) {
    var open by mutableStateOf(open)
}

/** Kept across rotation and process death, like the sheet it opens. */
@Composable
fun rememberVerifiedExplainer(): VerifiedExplainer {
    var saved by rememberSaveable { mutableStateOf(false) }
    val explainer = remember { VerifiedExplainer(saved) }
    LaunchedEffect(explainer.open) { saved = explainer.open }
    return explainer
}

/** The sheet that says what the badge means. [own] words it for my own profile. */
@Composable
fun BoxScope.VerifiedSheet(explainer: VerifiedExplainer, own: Boolean = false) {
    CircasoSheet(explainer.open, { explainer.open = false }, label = VerifiedCopy.Title) {
        SheetMessage(
            title = VerifiedCopy.Title,
            message = "${if (own) VerifiedCopy.Own else VerifiedCopy.Person} ${if (own) VerifiedCopy.PrivateOwn else VerifiedCopy.Private}",
            icon = null,
            face = { VerifiedSeal(com.circaso.designsystem.theme.CircasoSizes.sheetBadge, Modifier.clearAndSetSemantics {}) },
        )
        SheetActions("OK", { explainer.open = false }, secondaryLabel = null)
    }
}

/**
 * A person's name with the verified mark after it, as X puts its check after a name: the seal at the text's own size,
 * sitting on the line like a letter (its middle at the capitals' middle, a hair under the baseline), wrapping with
 * the last word and growing with Dynamic Type. [verified] false shows the name alone.
 *
 * A tap on the mark calls [onMarkClick] (open the explanation: [VerifiedSheet]); null leaves it a plain mark (inside
 * another sheet, where a second sheet would stack). Screen readers hear "<name>, Verified".
 */
@Composable
fun VerifiedName(
    name: String,
    verified: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
    onMarkClick: (() -> Unit)? = null,
) {
    if (!verified) {
        BasicText(name, modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        return
    }
    val text = remember(name) {
        buildAnnotatedString {
            append(name)
            // A word joiner: the mark never starts a line on its own, it goes with the last word.
            append('\u2060')
            appendInlineContent(VerifiedMarkId, ", ${VerifiedCopy.Title}")
        }
    }
    val inline = remember {
        mapOf(
            VerifiedMarkId to InlineTextContent(
                // The mark's box: a little space, then the seal; its bottom on the baseline.
                Placeholder(width = VerifiedMarkWidth.em, height = 1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline),
            ) { VerifiedMark() },
        )
    }
    // Tappable, the whole name is the button (a target as large as the name, said once: "<name>, Verified, button").
    val whole = if (onMarkClick != null) {
        Modifier.pressable(role = Role.Button, onClickLabel = "What this means", pressedScale = 0.98f, onClick = onMarkClick)
    } else {
        Modifier
    }
    BasicText(text, modifier.then(whole), style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, inlineContent = inline)
}

/** The mark in its placeholder (1 em high, its bottom on the baseline). The name's text says "Verified" for it. */
@Composable
private fun VerifiedMark() {
    val colors = sealColors()
    Box(
        Modifier.fillMaxSize().clearAndSetSemantics {}
            .drawWithCache {
                val em = size.height
                // Even pixels across, on a whole-pixel centre: the check stays sharp at text sizes.
                val diameter = round(em * VerifiedMarkSeal / 2f) * 2f
                val x = round(size.width - diameter / 2f)
                // The capitals' middle: a cap height is about 0.7 em above the baseline in Inter and SF.
                val y = round(em - em * 0.35f)
                val shape = sealShapeFor(diameter, Offset(x, y))
                onDrawBehind { drawSealShape(shape, colors) }
            },
    )
}

private const val VerifiedMarkId = "verified"
/** The seal is 0.88 em across, after 0.24 em of space. */
private const val VerifiedMarkSeal = 0.88f
private const val VerifiedMarkWidth = 1.12f
