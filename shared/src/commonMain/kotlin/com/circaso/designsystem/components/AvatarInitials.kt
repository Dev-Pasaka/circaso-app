package com.circaso.designsystem.components

/**
 * What an avatar without a photo shows (2026-11-12): the first letter or digit of the first two words ("Shiru (cousin)"
 * gives "SC"; "🌸 Wanjiru Kamau" gives "WK"). A name with no plain letters at all ("🔥🔥", "𝓙𝓪𝓷𝓮") shows its first
 * emoji or symbol whole, never half of one (that drew a broken box). Blank only for a blank name.
 */
fun avatarInitials(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val letters = words.mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.take(2)
    if (letters.isNotEmpty()) return letters.joinToString("") { it.uppercase() }
    return firstGrapheme(name.trim())
}

/** The first user-perceived character of [text]: a whole emoji with its skin tone, joiners, keycap or flag pair. */
internal fun firstGrapheme(text: String): String {
    if (text.isEmpty()) return ""
    var i = 0
    fun codePointAt(at: Int): Pair<Int, Int> {
        val c = text[at]
        if (c.isHighSurrogate() && at + 1 < text.length && text[at + 1].isLowSurrogate()) {
            return (((c.code - 0xD800) shl 10) + (text[at + 1].code - 0xDC00) + 0x10000) to 2
        }
        return c.code to 1
    }
    val (first, firstLen) = codePointAt(0)
    i += firstLen
    // A flag is two regional indicator letters.
    if (first in 0x1F1E6..0x1F1FF && i < text.length) {
        val (next, len) = codePointAt(i)
        if (next in 0x1F1E6..0x1F1FF) i += len
        return text.substring(0, i)
    }
    while (i < text.length) {
        val (cp, len) = codePointAt(i)
        when {
            cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F -> i += len
            cp == 0x200D && i + len < text.length -> { i += len; i += codePointAt(i).second } // joined: 👩‍💻
            else -> break
        }
    }
    return text.substring(0, i)
}

/** The first word of [name] that has a letter or digit ("🌸 Wanjiru Kamau" → "Wanjiru"), for "Call Wanjiru" and the like. */
fun firstNameWord(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return words.firstOrNull { w -> w.any { it.isLetterOrDigit() } } ?: words.firstOrNull().orEmpty()
}

/**
 * The letter a name is filed under in a people list, like the avatar: emoji and punctuation in front are skipped and
 * accents dropped ("🌸 Wanjiru" and "Émile" go under W and E); "#" when there's no letter at all.
 */
fun sectionLetter(name: String): String {
    val letter = name.firstOrNull { it.isLetter() } ?: return "#"
    val upper = letter.uppercaseChar()
    return (Accents[upper] ?: upper).toString()
}

/** What a people list sorts by within a letter: the name from its first letter on, lowercased. */
fun sortKey(name: String): String = name.dropWhile { !it.isLetterOrDigit() }.lowercase()

private val Accents: Map<Char, Char> = buildMap {
    fun add(base: Char, all: String) = all.forEach { put(it, base) }
    add('A', "ÀÁÂÃÄÅĀĂĄ"); add('C', "ÇĆĈĊČ"); add('D', "ĎĐ"); add('E', "ÈÉÊËĒĔĖĘĚ"); add('G', "ĜĞĠĢ"); add('H', "ĤĦ")
    add('I', "ÌÍÎÏĨĪĬĮİ"); add('J', "Ĵ"); add('K', "Ķ"); add('L', "ĹĻĽĿŁ"); add('N', "ÑŃŅŇ"); add('O', "ÒÓÔÕÖØŌŎŐ")
    add('R', "ŔŖŘ"); add('S', "ŚŜŞŠ"); add('T', "ŢŤŦ"); add('U', "ÙÚÛÜŨŪŬŮŰŲ"); add('W', "Ŵ"); add('Y', "ÝŶŸ"); add('Z', "ŹŻŽ")
}
