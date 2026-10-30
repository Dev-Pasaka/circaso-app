package com.circaso.feature.messaging.domain

import com.circaso.core.common.AppResult
import com.circaso.core.crypto.PeerBundle

/**
 * This phone's encryption keys on the server (integration guide, encryption keys M1–M3). Online-only: nothing here
 * is queued, because every call is a check or an upload the server must answer, and a failed one is simply tried
 * again at the next trigger (start, sign-in, reconnect).
 */
interface KeysRepository {
    /**
     * Makes sure the server has this phone's current keys: publishes on first use or after a reset, rotates the
     * signed prekeys every 7 days, tops up one-time prekeys below 20. Safe to call often; does nothing when all is well.
     */
    suspend fun maintain(): AppResult<KeyHealth>

    /** A person's bundle to start a session with (uses up one of their one-time prekeys on the server). */
    suspend fun peerBundle(userId: String): AppResult<FetchedPeer>
}

data class KeyHealth(val oneTimePreKeysLeft: Int, val published: Boolean)

/** [identityChanged] is true when the person's identity key differs from the one this phone trusted before. */
class FetchedPeer(val bundle: PeerBundle, val identityChanged: Boolean)
