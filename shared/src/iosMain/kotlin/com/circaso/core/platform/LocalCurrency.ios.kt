package com.circaso.core.platform

import platform.Foundation.NSLocale
import platform.Foundation.currencyCode
import platform.Foundation.currentLocale

actual fun localCurrencyCode(): String? = NSLocale.currentLocale.currencyCode?.takeIf { it.isNotBlank() }
