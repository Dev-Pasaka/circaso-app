package com.circaso.core.platform

import com.circaso.feature.auth.domain.Countries
import com.circaso.feature.auth.domain.Country

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

/**
 * Turns numbers as people save them in their phone book into E.164 (`+254712345678`), which is all the server accepts.
 * A number saved without its country code is read as one from [home], the country of the person's own Circaso number
 * (2026-11-12; Kenya until we know): a UK member's "07700 900123" is +44, a Kenyan's "0712 345 678" is +254.
 */
object PhoneBookNumbers {
    private val E164 = Regex("""^\+[1-9]\d{7,14}$""")

    fun toE164(raw: String, home: Country = Countries.Kenya): String? {
        val plus = raw.trim().startsWith("+")
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val code = home.dialCode
        val national = when {
            plus -> return "+$digits".takeIf { E164.matches(it) }
            digits.startsWith("00") -> return "+${digits.drop(2)}".takeIf { E164.matches(it) }
            // The country's own trunk 0: 0712 345 678, 07700 900123 (Italy keeps its 0 after the code).
            digits.startsWith("0") && !home.keepsLeadingZero && digits.length - 1 in home.lengths -> digits.drop(1)
            digits.startsWith("0") && home.keepsLeadingZero && digits.length in home.lengths -> digits
            // The calling code without "+": 254712345678, 12025550123.
            digits.startsWith(code) && digits.length - code.length in home.lengths -> digits.drop(code.length)
            // Just the national number: 712345678. In Kenya only mobile numbers (7…, 1…) are taken this way.
            digits.length in home.lengths && digits[0] != '0' && (home != Countries.Kenya || digits[0] in "17") -> digits
            else -> return null
        }
        return "+$code$national".takeIf { E164.matches(it) }
    }

    /** Normalised, valid, no duplicates, in a stable order. */
    fun normalise(raw: List<String>, home: Country = Countries.Kenya): List<String> = raw.mapNotNull { toE164(it, home) }.distinct()
}

/**
 * The country of the person's own number, remembered on this phone when it is verified (or seen on sign-in), so phone
 * book numbers saved without a country code are read as theirs. Kenya until known.
 */
class HomeCountry(private val read: suspend () -> String?, private val write: suspend (String) -> Unit) {
    suspend fun get(): Country = read()?.let { iso -> Countries.byIso(iso) } ?: Countries.Kenya

    /** From a full or masked number ("+447700900123", "+44 7•• ••• 123"). */
    suspend fun rememberFrom(number: String?) {
        val digits = number?.trim()?.takeIf { it.startsWith("+") }?.substringBefore(' ')?.filter { it.isDigit() } ?: return
        val country = if (number.contains(' ')) Countries.all.filter { it.dialCode == digits }.let { same ->
            same.firstOrNull { it.iso in setOf("KE", "US", "GB", "RU") } ?: same.firstOrNull()
        } else Countries.forInternational(digits)
        country?.let { write(it.iso) }
    }
}
