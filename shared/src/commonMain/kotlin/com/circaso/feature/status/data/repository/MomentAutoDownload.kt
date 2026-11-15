package com.circaso.feature.status.data.repository

import com.circaso.core.sync.nowMillis
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentAutoDownloadMaxBytes
import com.circaso.feature.status.domain.MomentMediaLoad
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.autoDownloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Other people's moments are fetched as soon as this phone learns of them, so they open at once instead of loading
 * when tapped (Pasaka, 2026-11-15: "a background job that downloads moments and auto deletes them after 24hrs, that
 * way people don't have to stream moments, they are always on the device"). The deleting is the repository's (a
 * moment's file goes with it when it ends); this is the fetching.
 *
 * One file at a time, the people at the front of the row first, while someone is signed in and online and the
 * setting (You → Moments → Download automatically) is on. What is fetched on which network is [autoDownloads]; all
 * of it stops at [MomentAutoDownloadMaxBytes] on the phone. A download is never cut short by the list changing: the
 * next one is chosen from the newest list when it ends.
 */
class MomentAutoDownload(
    private val repository: () -> MomentsRepository,
    /** True while a signed-in phone is online. */
    private val ready: Flow<Boolean>,
    /** The setting; on unless turned off. */
    private val enabled: Flow<Boolean>,
    /** `wifi`, `cellular`, `ethernet`, `other` or `none`; null where the phone can't say (then it counts as mobile data). */
    private val transport: Flow<String?>,
    private val scope: CoroutineScope,
    private val now: () -> Long = ::nowMillis,
) {
    private data class Inputs(val moments: List<Moment>, val muted: Set<String>, val on: Boolean, val transport: String?)

    private val latest = MutableStateFlow<Inputs?>(null)

    /** Downloads that failed and when, by media id: tried again after [RetryAfterMillis], not in a loop. */
    private val failedAt = mutableMapOf<String, Long>()
    /** Moments the server said are gone (or whose file wouldn't open): never tried again. */
    private val gone = mutableSetOf<String>()
    /** Fetched by this run of the app: never chosen twice, whatever the phone then says about the file. */
    private val done = mutableSetOf<String>()

    fun start() {
        scope.launch {
            val repo = repository()
            combine(repo.moments, repo.muted, ready, enabled, transport) { moments, muted, ready, enabled, transport ->
                Inputs(moments, muted, ready && enabled, transport)
            }.collect { latest.value = it }
        }
        // A StateFlow hands a slow collector only the newest value, so a run in progress is followed by one more at most.
        scope.launch { latest.collect { run() } }
    }

    private suspend fun run() {
        while (true) {
            val inputs = latest.value?.takeIf { it.on } ?: return
            val next = next(inputs) ?: return
            val mediaId = next.media?.mediaId ?: return
            when (val r = repository().media(next)) {
                is MomentMediaLoad.Loaded -> { failedAt.remove(mediaId); done += mediaId }
                is MomentMediaLoad.NeedsConnection -> failedAt[mediaId] = now()
                is MomentMediaLoad.Failed -> if (r.gone) gone += mediaId else failedAt[mediaId] = now()
            }
        }
    }

    /** The next moment to fetch, or null when there is nothing (left) to fetch. */
    private suspend fun next(inputs: Inputs): Moment? {
        val at = now()
        val repo = repository()
        val theirs = inputs.moments.filter { !it.mine && it.media != null && it.expiresAt > at }
        var onPhone = 0L
        val missing = mutableListOf<Moment>()
        for (m in theirs) {
            val size = repo.mediaSizeOnPhone(m)
            if (size != null) onPhone += size else missing += m
        }
        return inOrder(missing, inputs.muted).firstOrNull { m ->
            val media = m.media ?: return@firstOrNull false
            media.mediaId !in gone && media.mediaId !in done && failedAt[media.mediaId].let { it == null || at - it >= RetryAfterMillis } &&
                autoDownloads(m, inputs.transport) && onPhone + media.sizeBytes.coerceAtLeast(0) <= MomentAutoDownloadMaxBytes
        }
    }

    companion object {
        const val RetryAfterMillis = 2 * 60_000L

        /**
         * The order the row shows them in: not yet viewed before viewed, muted people last, the person who posted
         * most recently first, and each person's own from the oldest (the order the viewer plays them).
         */
        fun inOrder(moments: List<Moment>, muted: Set<String>): List<Moment> {
            val lastPost = moments.groupBy { it.posterId }.mapValues { (_, own) -> own.maxOf { it.postedAt } }
            return moments.sortedWith(
                compareBy<Moment> { it.posterId in muted }.thenBy { it.viewed }
                    .thenByDescending { lastPost[it.posterId] ?: 0L }.thenBy { it.posterId }.thenBy { it.postedAt },
            )
        }
    }
}
