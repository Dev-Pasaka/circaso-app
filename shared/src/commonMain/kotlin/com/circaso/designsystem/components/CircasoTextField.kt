package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme

/** Filled field with the label above and an optional error line below. [error] switches the outline to critical. */
@Composable
fun CircasoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    secret: Boolean = false,
    /**
     * A [secret] that stays readable, so it can be checked as it is typed (a security answer): still kept from Asha,
     * `/asha` and autocorrect, but shown in plain text on an ordinary keyboard.
     */
    masked: Boolean = true,
    /** A prefilled field opens with all its text selected, so one keystroke replaces it (Edit name). */
    selectAllOnStart: Boolean = false,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    // `/asha` summons Asha from any field that is not a secret one; the command text is not kept (AshaLauncher.kt).
    val onChange = ashaAware(value, onValueChange, secret)
    // A password-like field on screen: the screen can't be shown to Asha while it is.
    SecretOnScreen(secret)
    var focused by remember { mutableStateOf(false) }
    // The field keeps its own selection; a prefilled value starts with the cursor after it (or all of it selected),
    // never in front of it, where Backspace does nothing.
    var field by remember { mutableStateOf(TextFieldValue(value, if (selectAllOnStart) TextRange(0, value.length) else TextRange(value.length))) }
    if (field.text != value) field = field.copy(text = value, selection = TextRange(field.selection.start.coerceAtMost(value.length), field.selection.end.coerceAtMost(value.length)))
    val outline = when {
        error != null -> c.critical
        focused -> c.ink
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary))
        // `/` then the start of "asha" at the end of the text: Asha is offered just above the field (2026-11-10). The
        // field hands up a plain String, so its cursor is taken to be at the end, where typing almost always is.
        val asha = LocalAshaLauncher.current
        val ashaTyping = asha != null && !secret && enabled && focused && ashaFragment(value) != null
        Box {
            BasicTextField(
                value = field,
                onValueChange = { next -> field = next; if (next.text != value) onChange(next.text) },
                enabled = enabled,
                singleLine = singleLine,
                textStyle = t.body.copy(color = if (enabled) c.ink else c.inkTertiary),
                cursorBrush = SolidColor(c.ink),
                // A secret keeps a number pad when one was asked for (PINs); every other secret gets the password keyboard.
                keyboardOptions = if (secret && !masked) keyboardOptions.copy(autoCorrectEnabled = false)
                else if (secret) keyboardOptions.copy(
                    keyboardType = if (keyboardOptions.keyboardType == KeyboardType.NumberPassword) KeyboardType.NumberPassword else KeyboardType.Password,
                    autoCorrectEnabled = false,
                ) else keyboardOptions,
                keyboardActions = keyboardActions,
                visualTransformation = if (secret && masked) PasswordVisualTransformation() else visualTransformation,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(CircasoSizes.controlLg)
                            .background(c.fill, CircasoShapes.md)
                            .border(2.dp, outline, CircasoShapes.md)
                            .padding(horizontal = CircasoSpacing.s4),
                        horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leading?.invoke()
                        Box(Modifier.weight(1f)) {
                            if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkTertiary))
                            inner()
                        }
                    }
                },
            )
            AshaSuggestion(
                ashaTyping,
                onSummon = {
                    ashaFragment(value)?.let { onValueChange(takeAshaFragment(value, it).text) }
                    asha?.open()
                },
                Modifier,
            )
        }
        if (error != null) {
            BasicText(error, Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.critical))
        }
    }
}
