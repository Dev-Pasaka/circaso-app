package com.circaso.feature.auth.domain

/**
 * A phone number as typed on the number screen, for the [Country] picked there (Kenya unless the person picks another,
 * 2026-11-12): the digits after the calling code, entered with or without the country's leading 0 or the calling code.
 */
object PhoneNumber {
    const val CountryCode = "+254"
    const val NationalDigits = 9

    /** The E.164 number (`+254712345678`), or null while the input is not a complete number for [country]. */
    fun toE164(input: String, country: Country = Countries.Kenya, pasted: Boolean = true): String? {
        val digits = stripped(input, country, pasted)
        val ok = digits.length in country.lengths && (country.keepsLeadingZero || !digits.startsWith('0'))
        return if (ok) "+${country.dialCode}$digits" else null
    }

    /** True when the person has typed enough digits that "too short" is a fair thing to say. */
    fun looksTooShort(input: String, country: Country = Countries.Kenya, pasted: Boolean = true): Boolean {
        val digits = stripped(input, country, pasted)
        return digits.isNotEmpty() && digits.length < country.lengths.first
    }

    /**
     * What the field holds: the digits after the calling code (at most the country's longest), with a typed calling
     * code or leading 0 dropped. The spaces are drawn by the field's display formatting, so the cursor never jumps.
     */
    fun nationalDigits(input: String, country: Country = Countries.Kenya, pasted: Boolean = true): String =
        stripped(input, country, pasted).take(country.lengths.last)

    /** `712345678` shown as `712 345 678`. */
    fun format(input: String, country: Country = Countries.Kenya): String = nationalDigits(input, country).chunked(3).joinToString(" ")

    private fun stripped(input: String, country: Country, pasted: Boolean = true): String {
        var digits = input.filter { it.isDigit() }
        // A calling code in front ("+254 712…", "254712…"). Kenyan numbers never start with 254, so it always goes. Elsewhere
        // only in a paste, and only when what follows still makes a whole number: typing key by key never strips it, so a
        // German 0492 1… isn't mistaken for +49.
        if (digits.startsWith(country.dialCode) &&
            (country == Countries.Kenya || (pasted && digits.length - country.dialCode.length >= country.lengths.first))
        ) {
            digits = digits.drop(country.dialCode.length)
        }
        return if (country.keepsLeadingZero) digits else digits.removePrefix("0")
    }
}
