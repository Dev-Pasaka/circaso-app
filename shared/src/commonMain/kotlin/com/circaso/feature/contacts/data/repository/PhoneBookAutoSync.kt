package com.circaso.feature.contacts.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.ContactsReader
import com.circaso.core.platform.PhoneBookNumbers
import com.circaso.feature.contacts.domain.SyncPhoneBookUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the person's contacts who are on Circaso in the app without them asking (2026-11-11), and current
 * (2026-11-14): a number saved in the phone book a moment ago is ready to chat with in Circaso, no refresh needed.
 *
 * - The first time Home shows ([onHome]), the system's contacts prompt appears once ([asked] remembers it on this
 *   phone, whatever the answer). A "no" is respected: the prompt doesn't come back, and "Find from contacts" in New
 *   chat still asks again on a tap.
 * - With access allowed, the phone book is read again on Home, whenever the app is signed in and online ([ready]),
 *   every time Circaso comes to the front ([onForeground]) and whenever the phone book itself changes
 *   ([ContactsReader.changes]). Reading it is local and free.
 * - Only numbers go out, never names, and only numbers not sent before ([SyncPhoneBookUseCase] skips the rest). A
 *   phone book with nothing new costs no request at all, so the server's 5 syncs an hour go only to real changes,
 *   spaced at least [MinGapMillis] apart (a change inside the gap goes when it ends). A "too many" answer waits
 *   for the time the server names. Someone who joins Circaso later is matched by the server from the numbers it has.
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
    /** Numbers the server already has from this phone (this run): a phone book within it needs no request. */
    private var sent: Set<String> = emptySet()
    /** When the last request went, and the earliest the next may go. */
    private var lastSentAt: Long? = null
    private var notBefore = 0L
    private var later: Job? = null

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch { ready.distinctUntilChanged().filter { it }.collect { syncIfAllowed() } }
        // A contact being saved fires several changes in a row: one read once they settle.
        scope.launch { reader.changes().debounce(ChangeSettleMillis).collect { syncIfAllowed() } }
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

    /** Circaso came to the front: a number saved while it was away (or access given in Settings) shows now. */
    fun onForeground() {
        scope.launch { syncIfAllowed() }
    }

    private suspend fun syncIfAllowed() = lock.withLock {
        if (!reader.hasAccess()) return@withLock
        val raw = try { reader.readNumbers() } catch (e: CancellationException) { throw e } catch (e: Exception) { return@withLock }
        val numbers = PhoneBookNumbers.normalise(raw, homeCountry())
        if (sent.containsAll(numbers)) return@withLock // nothing new in the phone book
        val wait = maxOf(notBefore, (lastSentAt ?: Long.MIN_VALUE / 2) + MinGapMillis) - now()
        if (wait > 0) { syncLater(wait); return@withLock }
        lastSentAt = now()
        when (val result = sync(numbers)) {
            is AppResult.Success -> sent = numbers.toSet()
            is AppResult.Failure -> {
                val error = result.error
                if (error is AppError.Api && error.httpStatus == 429) {
                    notBefore = now() + (error.retryAfterSeconds?.let { it * 1000L } ?: RateLimitedWaitMillis)
                    syncLater(notBefore - now())
                }
                // Offline or another failure: the next change, foreground or reconnect tries again.
            }
        }
    }

    private fun syncLater(afterMillis: Long) {
        if (later?.isActive == true) return
        later = scope.launch { delay(afterMillis); syncIfAllowed() }
    }

    companion object {
        /** At least this long between two requests: a burst of edits never spends the server's 5 an hour at once. */
        const val MinGapMillis = 2 * 60 * 1000L
        /** A "too many" with no Retry-After: try again after this. */
        const val RateLimitedWaitMillis = 15 * 60 * 1000L
        const val ChangeSettleMillis = 2_000L
    }
}
