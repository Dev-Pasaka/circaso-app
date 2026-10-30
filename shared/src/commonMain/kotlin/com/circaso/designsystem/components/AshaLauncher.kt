package com.circaso.designsystem.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Typing this in any text field that is not secret opens the Asha widget. */
const val AshaCommand = "/asha"

/** Opens the Asha widget. Provided at the app root once Asha exists; until then the command does nothing and is left in the field. */
fun interface AshaLauncher {
    fun open()
}

val LocalAshaLauncher = staticCompositionLocalOf<AshaLauncher?> { null }

/** The text without the trailing `/asha` command, or null when the text does not end with it. */
fun textWithoutAshaCommand(text: String): String? =
    if (text.endsWith(AshaCommand, ignoreCase = true)) text.dropLast(AshaCommand.length) else null
