package com.circaso.feature.contacts.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.platform.ContactsReader
import com.circaso.core.platform.PhoneBookNumbers
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the person's contacts who are on Circaso in the app without them asking (2026-11-11): New chat and search show
 * them from the first visit to Home, so no one starts at an empty list.
 *
 * - The first time Home shows ([onHome]), the system's contacts prompt appears once ([asked] remembers it on this
 *   phone, whatever the answer). A "no" is respected: the prompt doesn't come back, and "Find from contacts" in New
 *   chat still asks again on a tap.
 * - With access allowed, the phone book syncs on Home and whenever the app is signed in and online ([ready]), at most
 *   once per [MinGapMillis]. Only numbers go out, never names, and only numbers the server hasn't been sent yet
 *   ([SyncPhoneBookUseCase] skips the rest), so a repeat costs nothing when the phone book hasn't changed. Someone
 *   who joins Circaso later is matched by the server from the numbers it already has.
 */
class PhoneBookAutoSync(
    private val reader: ContactsReader,
    private val sync: SyncPhoneBookUseCase,
    private val ready: Flow<Boolean>,
    private val asked: suspend () -> Boolean,
    private val markAsked: suspend () -> Unit,
    private val scope: CoroutineScope,
    /** The country of the person's own number, for phone book numbers saved without a country code. */
    private val homeCountry: suspend () -> com.circaso.feature.auth.domain.Country = { com.circaso.feature.auth.domain.Countries.Kenya },
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private var lastSyncAt: Long? = null

    fun start() {
        scope.launch { ready.distinctUntilChanged().filter { it }.collect { syncIfAllowed() } }
    }

    /** Home is on screen: ask for contacts once, then sync. Needs a visible screen for the system prompt. */
    suspend fun onHome() {
        if (!reader.hasAccess() && !asked()) {
            markAsked()
            val allowed = try { reader.requestAccess() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
            if (!allowed) return
        }
        syncIfAllowed()
    }

    private suspend fun syncIfAllowed() = lock.withLock {
        if (!reader.hasAccess()) return@withLock
        val last = lastSyncAt
        if (last != null && now() - last < MinGapMillis) return@withLock
        val numbers = try { reader.readNumbers() } catch (e: CancellationException) { throw e } catch (e: Exception) { return@withLock }
        if (sync(PhoneBookNumbers.normalise(numbers, homeCountry())) is AppResult.Success) lastSyncAt = now()
    }

    companion object {
        /** The server allows 5 syncs an hour; one an hour from here leaves the rest for "Find from contacts". */
        const val MinGapMillis = 60 * 60 * 1000L
    }
}
