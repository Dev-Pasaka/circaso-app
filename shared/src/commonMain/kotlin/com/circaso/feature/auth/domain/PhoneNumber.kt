package com.circaso.feature.auth.domain

/** Kenyan numbers for now (the only country the design offers): 9 digits after +254, entered with or without a leading 0. */
object PhoneNumber {
    const val CountryCode = "+254"
    const val NationalDigits = 9

    /** The E.164 number (`+254712345678`), or null while the input is not a complete Kenyan number. */
    fun toE164(input: String): String? {
        var digits = input.filter { it.isDigit() }
        digits = when {
            digits.startsWith("254") && digits.length == 3 + NationalDigits -> digits.drop(3)
            digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        return if (digits.length == NationalDigits && digits[0] != '0') CountryCode + digits else null
    }

    /** True when the person has typed enough digits that "too short" is a fair thing to say. */
    fun looksTooShort(input: String): Boolean {
        val digits = input.filter { it.isDigit() }.removePrefix("254").removePrefix("0")
        return digits.isNotEmpty() && digits.length < NationalDigits
    }

    /**
     * What the field holds: the national digits only (up to 9), with a typed country code or leading 0 dropped.
     * The spaces are drawn by the field's display formatting, so the cursor never jumps.
     */
    fun nationalDigits(input: String): String =
        input.filter { it.isDigit() }.removePrefix("254").removePrefix("0").take(NationalDigits)

    /** `712345678` shown as `712 345 678`. */
    fun format(input: String): String {
        val digits = input.filter { it.isDigit() }.removePrefix("254").removePrefix("0").take(NationalDigits)
        return digits.chunked(3).joinToString(" ")
    }
}
