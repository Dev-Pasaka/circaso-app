package com.circaso.core.common

/**
 * The server masks numbers with "•" (`+254 7•• ••• 678`). Some phone fonts give that bullet a very wide advance, so the
 * number spreads out and wraps. The middle dot is narrow and centred in every font we ship on, so it is what we show.
 */
fun String.maskedForDisplay(): String = replace('•', '·')
