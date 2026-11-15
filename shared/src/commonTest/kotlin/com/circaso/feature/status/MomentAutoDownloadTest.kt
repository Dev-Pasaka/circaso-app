package com.circaso.feature.status

import com.circaso.feature.status.data.repository.MomentAutoDownload
import com.circaso.feature.status.domain.Moment
import com.circaso.feature.status.domain.MomentKind
import com.circaso.feature.status.domain.MomentMedia
import com.circaso.feature.status.domain.MomentMediaLoad
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.autoDownloads
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Other people's moments are fetched as they arrive so they open at once (Pasaka, 2026-11-15), with a switch to
 * turn it off; on mobile data only photos, voice and small videos are.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MomentAutoDownloadTest {
    private val mib = 1024L * 1024

    private fun m(id: String, kind: MomentKind = MomentKind.Image, size: Long = 300_000, poster: String = "u1", at: Long = 1_000, mine: Boolean = false, viewed: Boolean = false) =
        Moment(id, poster, kind, media = MomentMedia("media-$id", "k", "d", "x/y", size), postedAt = at, expiresAt = 100_000_000, mine = mine, viewed = viewed, reacted = false)

    private class Repo(start: List<Moment>, base: ViewerFakeMoments = ViewerFakeMoments()) : MomentsRepository by base {
        val all = MutableStateFlow(start)
        val mutedIds = MutableStateFlow(emptySet<String>())
        val onPhone = mutableMapOf<String, Long>()
        val fetched = mutableListOf<String>()
        var answer: (Moment) -> MomentMediaLoad = { MomentMediaLoad.Loaded(ByteArray(0), null) }
        override val moments: Flow<List<Moment>> = all
        override val muted: Flow<Set<String>> = mutedIds
        override suspend fun media(moment: Moment): MomentMediaLoad {
            fetched += moment.momentId
            return answer(moment).also { if (it is MomentMediaLoad.Loaded) onPhone[moment.momentId] = moment.media!!.sizeBytes }
        }
        override suspend fun mediaSizeOnPhone(moment: Moment): Long? = onPhone[moment.momentId]
    }

    private val ready = MutableStateFlow(true)
    private val enabled = MutableStateFlow(true)
    private val transport = MutableStateFlow<String?>("wifi")
    private var clock = 2_000L

    private fun start(repo: Repo, scope: kotlinx.coroutines.CoroutineScope) =
        MomentAutoDownload({ repo }, ready, enabled, transport, scope, now = { clock }).start()

    @Test fun onWifiEveryMomentOfOthersIsFetchedAndMineAndTextAreNot() = runTest(UnconfinedTestDispatcher()) {
        val text = Moment("t", "u1", MomentKind.Text, text = "hi", postedAt = 1, expiresAt = 100_000, mine = false, viewed = false, reacted = false)
        val repo = Repo(listOf(m("a"), m("big", MomentKind.Video, 60 * mib), m("own", mine = true), text))
        start(repo, backgroundScope)
        assertEquals(setOf("a", "big"), repo.fetched.toSet())
        assertEquals(2, repo.fetched.size, "each once")
    }

    @Test fun onMobileDataALargeVideoWaitsUntilItIsOpened() = runTest(UnconfinedTestDispatcher()) {
        transport.value = "cellular"
        val repo = Repo(listOf(m("photo"), m("small", MomentKind.Video, 8 * mib), m("big", MomentKind.Video, 8 * mib + 1)))
        start(repo, backgroundScope)
        assertEquals(setOf("photo", "small"), repo.fetched.toSet())
        transport.value = "wifi" // home again
        assertTrue("big" in repo.fetched)
    }

    @Test fun whenThePhoneCannotSayTheNetworkItCountsAsMobileData() {
        assertFalse(autoDownloads(m("big", MomentKind.Video, 20 * mib), null))
        assertTrue(autoDownloads(m("photo"), null))
        assertTrue(autoDownloads(m("big", MomentKind.Video, 20 * mib), "ethernet"))
    }

    @Test fun turnedOffNothingIsFetchedUntilItIsTurnedOnAgain() = runTest(UnconfinedTestDispatcher()) {
        enabled.value = false
        val repo = Repo(listOf(m("a")))
        start(repo, backgroundScope)
        assertEquals(emptyList(), repo.fetched)
        enabled.value = true
        assertEquals(listOf("a"), repo.fetched)
    }

    @Test fun offlineOrSignedOutNothingIsFetched() = runTest(UnconfinedTestDispatcher()) {
        ready.value = false
        val repo = Repo(listOf(m("a")))
        start(repo, backgroundScope)
        assertEquals(emptyList(), repo.fetched)
        ready.value = true
        assertEquals(listOf("a"), repo.fetched)
    }

    @Test fun aMomentThatArrivesLaterIsFetchedAndOneAlreadyHereIsNot() = runTest(UnconfinedTestDispatcher()) {
        val repo = Repo(listOf(m("here")))
        repo.onPhone["here"] = 300_000
        start(repo, backgroundScope)
        assertEquals(emptyList(), repo.fetched)
        repo.all.value = listOf(m("here"), m("new"))
        assertEquals(listOf("new"), repo.fetched)
    }

    @Test fun theFrontOfTheRowComesFirst() {
        val list = listOf(
            m("muted", poster = "quiet", at = 900), m("seen", poster = "u1", at = 800, viewed = true),
            m("older-poster", poster = "u2", at = 100), m("u1-second", poster = "u1", at = 500), m("u1-first", poster = "u1", at = 400),
        )
        assertEquals(
            listOf("u1-first", "u1-second", "older-poster", "seen", "muted"),
            MomentAutoDownload.inOrder(list, setOf("quiet")).map { it.momentId },
        )
    }

    @Test fun aFailedDownloadIsNotTriedInALoopButIsTriedAgainLater() = runTest(UnconfinedTestDispatcher()) {
        val repo = Repo(listOf(m("a")))
        repo.answer = { MomentMediaLoad.NeedsConnection }
        start(repo, backgroundScope)
        assertEquals(listOf("a"), repo.fetched, "once, then it waits")
        repo.all.value = listOf(m("a"), m("b", at = 1_500)) // the list changes straight away
        assertEquals(listOf("a", "b"), repo.fetched, "the new one is tried; the failed one waits")
        clock += MomentAutoDownload.RetryAfterMillis
        repo.answer = { MomentMediaLoad.Loaded(ByteArray(0), null) }
        repo.all.value = listOf(m("a"), m("b", at = 1_500), m("c", at = 1_600))
        assertEquals(setOf("a", "b", "c"), repo.fetched.drop(2).toSet())
    }

    @Test fun aMomentTheServerSaysIsGoneIsNeverAskedForAgain() = runTest(UnconfinedTestDispatcher()) {
        val repo = Repo(listOf(m("a")))
        repo.answer = { MomentMediaLoad.Failed(null, gone = true) }
        start(repo, backgroundScope)
        clock += 10 * MomentAutoDownload.RetryAfterMillis
        repo.answer = { MomentMediaLoad.Loaded(ByteArray(0), null) }
        repo.all.value = listOf(m("a"), m("a2", at = 1_500))
        assertEquals(listOf("a", "a2"), repo.fetched, "the gone one is left alone; a new one is still fetched")
    }

    @Test fun fetchingStopsAtTheSpaceMomentsMayTake() = runTest(UnconfinedTestDispatcher()) {
        val repo = Repo(listOf(
            m("held", MomentKind.Video, 64 * mib, poster = "u9", viewed = true),
            m("v1", MomentKind.Video, 60 * mib, at = 10), m("v2", MomentKind.Video, 60 * mib, at = 20), m("p", at = 30),
        ))
        repo.onPhone["held"] = 440 * mib
        start(repo, backgroundScope)
        // 440 on the phone: one 60 fits (500), the second doesn't, the photo doesn't either.
        assertEquals(listOf("v1"), repo.fetched)
    }
}
