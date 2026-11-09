package com.circaso.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Typing this, as its own word, in any text field that is not secret summons Asha's bubble (2026-11-09). */
const val AshaCommand = "/asha"

/** Summons Asha's floating bubble. Provided at the app root with a full session; elsewhere the command stays as typed. */
fun interface AshaLauncher {
    fun open()
}

val LocalAshaLauncher = staticCompositionLocalOf<AshaLauncher?> { null }

/** The text once the typed command is taken out, and where the cursor goes (where the command was). */
data class AshaCommandHit(val text: String, val cursor: Int)

/**
 * `/asha` just typed as its own word (case-insensitive): at the start or after a space, and not followed by a letter
 * or digit. It counts only when [after] has more of them than [before] (so an edit elsewhere in text that holds one
 * never fires), and the one taken out is the one ending at [cursor] when given, else the last.
 */
fun ashaCommandTyped(before: String, after: String, cursor: Int = -1): AshaCommandHit? {
    if (after.length < AshaCommand.length || before == after) return null
    val found = commandStarts(after)
    if (found.isEmpty() || found.size <= commandStarts(before).size) return null
    val start = found.lastOrNull { it + AshaCommand.length == cursor } ?: found.last()
    return AshaCommandHit(after.removeRange(start, start + AshaCommand.length), start)
}

/** Where `/asha` starts as its own word in [text]. */
internal fun commandStarts(text: String): List<Int> {
    val out = mutableListOf<Int>()
    var i = text.indexOf(AshaCommand, ignoreCase = true)
    while (i >= 0) {
        val end = i + AshaCommand.length
        val wordStart = i == 0 || text[i - 1].isWhitespace()
        val wordEnd = end == text.length || !text[end].isLetterOrDigit()
        if (wordStart && wordEnd) out += i
        i = text.indexOf(AshaCommand, startIndex = i + 1, ignoreCase = true)
    }
    return out
}

/**
 * The one place a field learns `/asha`: wraps a field's [onValueChange] so typing the command takes it out of the text
 * and summons Asha. Secret fields ([secret]: PIN, password, codes) and screens without a session get the callback
 * unchanged, so the command never fires there and their text never reaches Asha.
 */
@Composable
fun ashaAware(value: String, onValueChange: (String) -> Unit, secret: Boolean = false): (String) -> Unit {
    val asha = LocalAshaLauncher.current
    if (secret || asha == null) return onValueChange
    return { next ->
        val hit = ashaCommandTyped(value, next)
        if (hit != null) { onValueChange(hit.text); asha.open() } else onValueChange(next)
    }
}

/** [ashaAware] for a field that keeps its cursor ([TextFieldValue]): the cursor stays where the command was. */
@Composable
fun ashaAwareValue(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, secret: Boolean = false): (TextFieldValue) -> Unit {
    val asha = LocalAshaLauncher.current
    if (secret || asha == null) return onValueChange
    return { next ->
        val hit = ashaCommandTyped(value.text, next.text, next.selection.end)
        if (hit != null) { onValueChange(TextFieldValue(hit.text, TextRange(hit.cursor))); asha.open() } else onValueChange(next)
    }
}

/**
 * Counts the secret inputs on screen (PIN pads, code inputs, password fields), so "Show Asha my screen" refuses while
 * any is showing: a capture must never carry a secret, even half-typed.
 */
@Stable
class SecretsOnScreen {
    var count by mutableIntStateOf(0)
        private set
    val any: Boolean get() = count > 0
    internal fun add() { count++ }
    internal fun remove() { count-- }
}

val LocalSecretsOnScreen = staticCompositionLocalOf<SecretsOnScreen?> { null }

/** Put inside any composable that shows a secret input: while it is composed, the screen can't be shown to Asha. */
@Composable
fun SecretOnScreen(active: Boolean = true) {
    val secrets = LocalSecretsOnScreen.current ?: return
    if (!active) return
    DisposableEffect(secrets) {
        secrets.add()
        onDispose { secrets.remove() }
    }
}
