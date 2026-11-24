package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.chrome.SheetActions
import com.circaso.designsystem.chrome.SheetMessage
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** What the badge means, said the same way everywhere (the verified badge, 2026-11-24). */
object VerifiedCopy {
    const val Title = "Verified"
    const val Person = "Circaso verified this person's identity with a government ID and a selfie."
    const val Own = "Circaso verified your identity with a government ID and a selfie."
    const val Private = "Only the badge is shown, never their ID or selfie."
    const val PrivateOwn = "People see only the badge, never your ID or selfie."
}

/**
 * The "Verified" pill under a person's name on their profile (and on my own): the seal and the word, in a quiet
 * capsule. A tap opens a short sheet saying what it means. [own] words it for my own profile. With [explainable] false
 * (inside another sheet, where a second sheet would stack) the meaning is written under the pill instead.
 */
@Composable
fun VerifiedPill(modifier: Modifier = Modifier, own: Boolean = false, explainable: Boolean = true) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    var open by rememberSaveable { mutableStateOf(false) }
    val meaning = if (own) VerifiedCopy.Own else VerifiedCopy.Person
    Box(modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s1)) {
            Row(
                Modifier
                    // A 44 dp touch target around a capsule that looks smaller.
                    .heightIn(min = CircasoSizes.controlMd)
                    .then(
                        if (explainable) {
                            Modifier.pressable(role = Role.Button, onClickLabel = "What this means") { open = true }
                        } else {
                            Modifier
                        },
                    )
                    .clearAndSetSemantics {
                        contentDescription = if (explainable) VerifiedCopy.Title else "${VerifiedCopy.Title}. $meaning"
                        if (explainable) role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier.clip(CircasoShapes.pill).background(c.fill).padding(start = CircasoSpacing.s1, end = CircasoSpacing.s3, top = CircasoSpacing.s1, bottom = CircasoSpacing.s1),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    VerifiedSeal(18.dp)
                    BasicText(VerifiedCopy.Title, style = t.footnote.copy(color = c.ink, fontWeight = FontWeight.SemiBold))
                }
            }
            if (!explainable) {
                BasicText(
                    meaning,
                    Modifier.padding(horizontal = CircasoSpacing.s4).clearAndSetSemantics {},
                    style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
                )
            }
        }
        if (explainable) {
            CircasoSheet(open, { open = false }, label = VerifiedCopy.Title) {
                SheetMessage(
                    title = VerifiedCopy.Title,
                    message = "$meaning ${if (own) VerifiedCopy.PrivateOwn else VerifiedCopy.Private}",
                    icon = null,
                    face = { VerifiedSeal(56.dp, Modifier.clearAndSetSemantics {}) },
                )
                SheetActions("OK", { open = false }, secondaryLabel = null)
            }
        }
    }
}

/**
 * [VerifiedPill] when [shown], coming in with a fade and the room it needs (and going the same way) rather than
 * pushing what is under it the moment the profile arrives. Still under Reduce Motion.
 */
@Composable
fun VerifiedPillReveal(shown: Boolean, modifier: Modifier = Modifier, own: Boolean = false) {
    val still = CircasoTheme.reduceMotion
    androidx.compose.animation.AnimatedVisibility(
        shown,
        modifier,
        enter = if (still) androidx.compose.animation.EnterTransition.None else
            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)) +
                androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.BaseMs)),
        exit = if (still) androidx.compose.animation.ExitTransition.None else
            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)) +
                androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(com.circaso.designsystem.theme.CircasoMotion.QuickMs)),
    ) { VerifiedPill(own = own) }
}
