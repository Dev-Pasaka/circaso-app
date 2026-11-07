package com.circaso.core.platform

/** The platform share sheet with one piece of text (an @handle, an address, a receipt). */
expect fun shareText(text: String, subject: String? = null)

/** Makes a simple PDF (a title, then one line per entry, paginated) and opens the share sheet with it. */
expect fun sharePdf(title: String, lines: List<String>)

/**
 * Opens a chat's document with the apps on this phone that can show it (Android's "Open with", iPhone's share sheet,
 * which offers Save to Files and the apps that open it). The decrypted copy is written to the app's cache under its
 * own name first. False when nothing could take it.
 */
expect suspend fun openFile(bytes: ByteArray, name: String, mime: String): Boolean
