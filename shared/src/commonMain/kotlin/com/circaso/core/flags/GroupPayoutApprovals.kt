package com.circaso.core.flags

/**
 * Option A's payout requests and admin approvals (CH4 "Request Payout", GW9c, approving in the chat), parked by
 * Pasaka on 2026-11-06: the group wallet's owner sends straight from it, to anyone, themselves or an outside address,
 * and everyone else only contributes. The code stays, dormant behind this switch; the server has the same one
 * (`WALLET_GROUP_PAYOUT_APPROVALS`), and both must be turned on together.
 */
object GroupPayoutApprovals {
    const val Enabled: Boolean = false
}
