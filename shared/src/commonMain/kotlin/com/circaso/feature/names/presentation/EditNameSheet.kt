package com.circaso.feature.names.presentation

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoTextField
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.names.domain.SavedNames
import com.circaso.feature.support.Support
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Edit name sheet for one person: [shown] is the name on screen now, [profileName] their own. */
data class EditName(
    val userId: String,
    val profileName: String,
    val shown: String,
    /** I saved a name for them: the sheet offers their own back. */
    val hasSaved: Boolean,
    val draft: String = shown,
) {
    /** Save does something: a new name, or a blank one that clears the saved name. */
    val canSave: Boolean get() = draft.trim() != shown.trim() && (draft.isNotBlank() || hasSaved)

    /** Shown under the field once the name nears the limit, so a pasted name never loses its end silently. */
    val counter: String? get() = draft.length.takeIf { it >= CounterFrom }?.let { "$it/${SavedNames.MaxLength}" }

    companion object {
        const val CounterFrom = 30

        /** Whether [userId] may be renamed: never Circaso Support, whose name is what proves it's them. */
        fun allowedFor(userId: String?): Boolean = userId != null && userId != Support.UserId
    }
}

/**
 * The Edit name sheet's state and its two writes, shared by every screen that offers it (contact sheet, contact info,
 * group member). Saving is local first ([SavedNames]): the sheet closes and the new name is everywhere at once. When
 * it was opened from a person's sheet, that sheet comes back after (with the new name), as in WhatsApp.
 */
class EditNameController(
    private val names: SavedNames,
    private val scope: CoroutineScope,
    /** How long the name sheet takes to leave before the person's sheet comes back (no scrim dip between them). */
    private val returnDelayMillis: Long = CircasoMotion.QuickMs.toLong(),
) {
    private val _state = MutableStateFlow<EditName?>(null)
    val state: StateFlow<EditName?> = _state
    private var returnTo: (() -> Unit)? = null

    /** Said when the name couldn't be kept (the phone's store failed): the screen shows it as a toast. */
    var onProblem: (String) -> Unit = {}

    /** Opens the sheet for [userId], prefilled with the name shown now; [returnTo] reopens what it was opened from. */
    fun open(userId: String, profileName: String, shown: String, returnTo: (() -> Unit)? = null) {
        if (!EditName.allowedFor(userId)) return
        this.returnTo = returnTo
        _state.value = EditName(userId, profileName, shown, hasSaved = userId in names.names.value)
    }

    fun onDraft(text: String) = _state.update { it?.copy(draft = text.take(SavedNames.MaxLength)) }

    fun onSave() {
        val s = _state.value ?: return
        if (!s.canSave) return
        // Their own name typed back (or nothing) is the same as using theirs: no saved name is kept for it.
        val same = s.draft.isBlank() || SavedNames.normalise(s.draft) == SavedNames.normalise(s.profileName)
        close { if (same) names.clear(s.userId) else names.set(s.userId, s.draft) }
    }

    fun onUseTheirs() {
        val s = _state.value ?: return
        close { names.clear(s.userId) }
    }

    fun onDismiss() = close(null)

    private fun close(write: (suspend () -> Unit)?) {
        _state.value = null
        val back = returnTo
        returnTo = null
        scope.launch {
            if (write != null) {
                try { write() } catch (e: CancellationException) { throw e } catch (e: Exception) { onProblem(CouldNotSave) }
            }
            if (back != null) { delay(returnDelayMillis); back() }
        }
    }

    companion object {
        const val CouldNotSave = "Couldn't save the name. Try again."

        /** For previews: nothing is saved. */
        fun preview(scope: CoroutineScope) = EditNameController(
            object : SavedNames {
                override val names: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
                override suspend fun set(userId: String, name: String) = Unit
                override suspend fun clear(userId: String) = Unit
            },
            scope,
        )
    }
}

/**
 * Edit name (2026-11-12; not in the canvas, built from the Rename group sheet): a title, the name field prefilled with
 * the name shown now (all of it selected), "Only you see this name.", Save, and, when I saved one, Use Their Name with
 * their own name said in the helper line above it (so a long name is never clipped in a button).
 */
@Composable
fun BoxScope.EditNameSheet(
    state: EditName?,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    onUseTheirs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // The last one is kept while the sheet slides away, so it never empties on the way out.
    var last by remember { mutableStateOf(state) }
    state?.let { last = it }
    CircasoSheet(visible = state != null, onDismiss = onDismiss, label = "Edit name") {
        val s = last ?: return@CircasoSheet
        val focus = remember { FocusRequester() }
        // The keyboard comes up once the sheet has landed, so the sheet doesn't jump halfway in.
        LaunchedEffect(state != null) { if (state != null) { delay(CircasoMotion.BaseMs.toLong()); runCatching { focus.requestFocus() } } }
        BasicText("Edit name", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink))
        CircasoTextField(
            value = s.draft, onValueChange = onDraft, label = "Name", placeholder = s.profileName,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            selectAllOnStart = true,
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s1)) {
            val help = if (s.hasSaved) "Only you see this name. Their own name is ${s.profileName}." else "Only you see this name."
            BasicText(help, Modifier.weight(1f), style = t.footnote.copy(color = c.inkSecondary))
            s.counter?.let { BasicText(it, Modifier.padding(start = CircasoSpacing.s2), style = t.footnote.copy(color = c.inkTertiary)) }
        }
        CircasoButton("Save", onSave, size = ButtonSize.Large, block = true, enabled = s.canSave)
        if (s.hasSaved) CircasoButton("Use Their Name", onUseTheirs, style = ButtonStyle.Secondary, size = ButtonSize.Large, block = true)
        CircasoButton("Cancel", onDismiss, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
    }
}

/**
 * The line under a name I saved, WhatsApp-style: their own name, "~ John Kamau". Read aloud as "Their name, John
 * Kamau", never "tilde".
 */
@Composable
fun ProfileNameLine(profileName: String, style: TextStyle, modifier: Modifier = Modifier) {
    BasicText("~ $profileName", modifier.clearAndSetSemantics { contentDescription = "Their name, $profileName" }, style = style)
}
