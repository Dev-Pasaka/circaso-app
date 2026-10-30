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
import androidx.compose.ui.text.input.KeyboardType
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
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val asha = LocalAshaLauncher.current
    var focused by remember { mutableStateOf(false) }
    val outline = when {
        error != null -> c.critical
        focused -> c.ink
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.inkSecondary))
        BasicTextField(
            value = value,
            onValueChange = { next ->
                // `/asha` opens the assistant from any field that is not a secret one; the command text is not kept.
                val rest = if (!secret && asha != null) textWithoutAshaCommand(next) else null
                if (rest != null) {
                    onValueChange(rest)
                    asha?.open()
                } else onValueChange(next)
            },
            enabled = enabled,
            singleLine = singleLine,
            textStyle = t.body.copy(color = if (enabled) c.ink else c.inkTertiary),
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = if (secret) keyboardOptions.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (secret) PasswordVisualTransformation() else visualTransformation,
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
        if (error != null) {
            BasicText(error, Modifier.padding(horizontal = CircasoSpacing.s1), style = t.footnote.copy(color = c.critical))
        }
    }
}
