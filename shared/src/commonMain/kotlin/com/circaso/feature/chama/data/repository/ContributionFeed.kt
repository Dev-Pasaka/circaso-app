package com.circaso.feature.chama.data.repository

import com.circaso.feature.chama.domain.GroupContribution
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Contributions into a group's wallet as they happen, from the `chama` frames (2026-11-09), for the screens that show
 * them landing live (a group call). Hot: a screen hears what arrives while it listens; nothing is kept or replayed,
 * because a contribution that landed before the screen opened is already in the group's balance.
 *
 * [resyncs] fires when the server says this phone's `chama` frames can't be trusted (a `resync`): the screens reload
 * the group's money then, as they do when the socket comes back, so a missed frame still heals.
 */
class ContributionFeed {
    private val _contributions = MutableSharedFlow<GroupContribution>(extraBufferCapacity = 32)
    val contributions: SharedFlow<GroupContribution> = _contributions.asSharedFlow()

    private val _resyncs = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resyncs: SharedFlow<Unit> = _resyncs.asSharedFlow()

    fun onFrame(type: String, data: JsonObject?) {
        if (type == "resync") { _resyncs.tryEmit(Unit); return }
        contributionOf(type, data)?.let { _contributions.tryEmit(it) }
    }
}

/** The contribution a `chama` frame tells of, or null for every other frame (payouts, goals, requests). */
internal fun contributionOf(type: String, data: JsonObject?): GroupContribution? {
    if (data == null) return null
    val confirmed = when (type) {
        "contribution.submitted" -> false
        "money.updated" -> if (data.text("kind") == "CONTRIBUTION") true else return null
        else -> return null
    }
    val chatId = data.text("chatId") ?: return null
    val transferId = data.text("transferId") ?: return null
    val paid = data["paid"] as? JsonObject
    val dollars = data["amount"] as? JsonObject
    return GroupContribution(
        chatId = chatId,
        transferId = transferId,
        userId = data.text("userId"),
        displayName = data.text("displayName"),
        coin = paid?.text("asset") ?: dollars?.text("asset"),
        coinAmount = paid?.text("amount") ?: dollars?.text("amount"),
        usdMinor = dollars?.text("amountMinor"),
        confirmed = confirmed,
    )
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
