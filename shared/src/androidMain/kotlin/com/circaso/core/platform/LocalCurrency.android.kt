package com.circaso.core.platform

import java.util.Currency
import java.util.Locale

actual fun localCurrencyCode(): String? = try {
    val locale = Locale.getDefault()
    if (locale.country.isNullOrEmpty()) null else Currency.getInstance(locale).currencyCode
} catch (e: Exception) { null }
