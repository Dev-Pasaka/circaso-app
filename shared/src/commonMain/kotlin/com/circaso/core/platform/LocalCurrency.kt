package com.circaso.core.platform

/**
 * The ISO 4217 code of the phone's region (Kenya → `KES`), which the wallet asks the server to price balances in.
 * Null when the region has no currency the server would know; the wallet then shows USDC only.
 */
expect fun localCurrencyCode(): String?
