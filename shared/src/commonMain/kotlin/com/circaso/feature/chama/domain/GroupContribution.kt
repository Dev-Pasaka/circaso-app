package com.circaso.feature.chama.domain

/**
 * A member put money into a group's wallet, as the server tells every member (2026-11-09): `chama` /
 * `contribution.submitted` the moment it is signed and on its way ([confirmed] false), and `money.updated`
 * `CONTRIBUTION` when the chain confirms it ([confirmed] true). The same [transferId] in both. A group call shows it
 * landing; the group's balance only moves with the confirmed one (the screens reload CH7 then).
 */
data class GroupContribution(
    val chatId: String,
    val transferId: String,
    val userId: String?,
    /** The server's name for them; the call prefers the name in its own people list. */
    val displayName: String?,
    /** The coin actually paid ("USDC", "ETH"); null when the server didn't say (an older server's `money.updated`). */
    val coin: String?,
    /** The coin amount in its own units, as the server wrote it ("0.01"). */
    val coinAmount: String?,
    /** Its dollar value in USDC minor units (6 decimals), null when the coin had no price. */
    val usdMinor: String?,
    val confirmed: Boolean,
)
