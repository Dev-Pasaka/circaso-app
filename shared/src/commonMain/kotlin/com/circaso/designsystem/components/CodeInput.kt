package com.circaso.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoTheme

/**
 * One-time code entry: [length] boxes (design `code`: 48 x 58, rounded font) over a single hidden field, so
 * pasting works and the keyboard can offer the code from the SMS (the field is marked as an SMS one-time code for
 * autofill). Digits only. Never announces the digits to screen readers.
 */
@Composable
fun CodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    error: Boolean = false,
    enabled: Boolean = true,
    autoFocus: Boolean = true,
) {
    val c = CircasoTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = { next -> onValueChange(next.filter { it.isDigit() }.take(length)) },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        cursorBrush = SolidColor(Color.Transparent),
        textStyle = CircasoTheme.typography.amountMedium.copy(color = Color.Transparent),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .semantics {
                contentDescription = "${value.length} of $length digits entered"
                contentType = ContentType.SmsOtpCode // lets the keyboard / autofill service offer the code from the message
            },
        decorationBox = { inner ->
            Box {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    repeat(length) { index ->
                        val active = enabled && index == value.length.coerceAtMost(length - 1) && value.length < length
                        Box(
                            Modifier
                                .width(48.dp)
                                .height(58.dp)
                                .background(c.fill, CircasoShapes.sm)
                                .border(
                                    width = 2.dp,
                                    color = when {
                                        error -> c.critical
                                        active -> c.ink
                                        else -> Color.Transparent
                                    },
                                    shape = CircasoShapes.sm,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicText(
                                value.getOrNull(index)?.toString().orEmpty(),
                                style = CircasoTheme.typography.amountMedium.copy(color = c.ink),
                            )
                        }
                    }
                }
                Box(Modifier.width(0.dp).height(0.dp)) { inner() }
            }
        },
    )
}
