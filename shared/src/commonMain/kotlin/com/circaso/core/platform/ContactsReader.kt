package com.circaso.core.platform

/**
 * Reads the phone numbers (only the numbers: names never leave the phone and are never even read) from the phone book.
 * The permission prompt is the platform's own; this app only asks when the person chooses to find friends.
 */
interface ContactsReader {
    /** True when the person has already allowed access. */
    suspend fun hasAccess(): Boolean

    /** Shows the system permission prompt if needed. True when access is allowed afterwards. */
    suspend fun requestAccess(): Boolean

    /** Every number in the phone book, as typed there (unnormalised). Empty when access is not allowed. */
    suspend fun readNumbers(): List<String>
}

/** Turns numbers as people type them into E.164 (`+254712345678`), which is all the server accepts. */
object PhoneBookNumbers {
    private const val DefaultCountry = "254" // Kenya, the only country offered for now
    private val E164 = Regex("""^\+[1-9]\d{7,14}$""")

    fun toE164(raw: String): String? {
        val plus = raw.trim().startsWith("+")
        var digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val candidate = when {
            plus -> "+$digits"
            digits.startsWith("00") -> "+${digits.drop(2)}"
            digits.startsWith("0") && digits.length == 10 -> "+$DefaultCountry${digits.drop(1)}" // 0712 345 678
            digits.startsWith(DefaultCountry) && digits.length == 12 -> "+$digits"                // 254712345678
            digits.length == 9 && digits[0] in "17" -> "+$DefaultCountry$digits"                  // 712345678
            else -> return null
        }
        return candidate.takeIf { E164.matches(it) }
    }

    /** Normalised, valid, no duplicates, in a stable order. */
    fun normalise(raw: List<String>): List<String> = raw.mapNotNull(::toE164).distinct()
}
