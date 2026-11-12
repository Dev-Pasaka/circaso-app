package com.circaso.designsystem.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoMotion
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoTheme

/**
 * The 36 dp search pill (design C8), with a clear button once something is typed and a focus handle so "Find by
 * Username" can put the cursor in it. Shared by New chat, New group and the sign-up country picker.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    focus: FocusRequester? = null,
    modifier: Modifier = Modifier,
    /** The keyboard's Search key; by default it just closes the keyboard. */
    onSearch: (() -> Unit)? = null,
) {
    val c = CircasoTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val t = CircasoTheme.typography
    Row(
        modifier.fillMaxWidth().heightIn(min = 36.dp).clip(CircasoShapes.pill).background(c.fill).padding(start = 12.dp, end = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        CircasoIconImage(CircasoIcon.Search, c.inkSecondary, Modifier.size(18.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value, onValueChange = ashaAware(value, onValueChange), singleLine = true,
                textStyle = t.body.copy(color = c.ink), cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() ?: keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().then(if (focus != null) Modifier.focusRequester(focus) else Modifier).semantics { contentDescription = placeholder },
            )
            if (value.isEmpty()) BasicText(placeholder, style = t.body.copy(color = c.inkSecondary), maxLines = 1)
        }
        val reduce = CircasoTheme.reduceMotion
        AnimatedVisibility(
            value.isNotEmpty(),
            enter = if (reduce) fadeIn(snap()) else fadeIn(tween(CircasoMotion.QuickMs)),
            exit = if (reduce) fadeOut(snap()) else fadeOut(tween(CircasoMotion.QuickMs)),
        ) {
            Box(
                Modifier.size(CircasoSizes.iconButton).clip(CircasoShapes.circle).pressable(role = Role.Button, onClick = { onValueChange("") }).semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(18.dp).background(c.inkTertiary, CircasoShapes.circle), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.Close, c.fill, Modifier.size(10.dp), strokeWidth = 3f)
                }
            }
        }
    }
}
