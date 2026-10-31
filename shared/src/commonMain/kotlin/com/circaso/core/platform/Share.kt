package com.circaso.core.platform

/** The platform share sheet with one piece of text (an @handle, an address, a receipt). */
expect fun shareText(text: String, subject: String? = null)

/** Makes a simple PDF (a title, then one line per entry, paginated) and opens the share sheet with it. */
expect fun sharePdf(title: String, lines: List<String>)
