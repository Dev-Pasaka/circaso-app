package com.circaso.feature.status

import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.status.domain.MomentViewer
import com.circaso.feature.status.domain.MomentsRepository
import com.circaso.feature.status.domain.SeenReactions
import com.circaso.feature.status.presentation.MomentViewerViewModel
import com.circaso.feature.status.presentation.ReactionBurst
import com.circaso.feature.status.presentation.queue
import com.circaso.feature.status.presentation.unseenReactions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MomentReactionSplashTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val monitor = object : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }
    private lateinit var connection: ConnectionState
    private val base = ViewerFakeMoments().apply { list.value = listOf(moment("m1").copy(mine = true, viewCount = 1, reactionCount = 1)) }
    private val repo = SplashFakeMoments(base)
    private val seen = SeenReactions.InMemory()

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher); connection = ConnectionState(monitor, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun mine() = MomentViewerViewModel(
        posterId = null, repository = repo, observeContacts = ObserveContactsUseCase(FakeContacts()), observeProfile = ObserveProfileUseCase(FakeProfile()),
        connection = connection, replyInChat = { _, _, _ -> AppResult.Success("c") }, seenReactions = seen,
    )

    @Test fun reactionsNotYetShownSplashOnceWhenTheMomentOpens() = runTest(dispatcher) {
        repo.views = listOf(MomentViewer("w", 1, true, "😂"), MomentViewer("x", 2, false))
        val vm = mine()
        assertEquals(listOf("😂"), vm.state.value.burst?.emojis)
        assertEquals("Wanjiru reacted", vm.state.value.burst?.line)
        vm.splashDone(vm.state.value.burst!!.id)
        assertNull(vm.state.value.burst)
        // Opened again: already shown, nothing splashes.
        assertNull(mine().state.value.burst)
    }

    @Test fun aReactionArrivingLiveSplashesAndAChangedEmojiSplashesAgain() = runTest(dispatcher) {
        val vm = mine()
        assertNull(vm.state.value.burst)
        repo.views = listOf(MomentViewer("w", 1, true, "❤️"))
        repo.frames.emit("m1")
        assertEquals(listOf("❤️"), vm.state.value.burst?.emojis)
        vm.splashDone(vm.state.value.burst!!.id)
        repo.frames.emit("m1")
        assertNull(vm.state.value.burst)
        repo.views = listOf(MomentViewer("w", 1, true, "🔥"))
        repo.frames.emit("m1")
        assertEquals(listOf("🔥"), vm.state.value.burst?.emojis)
    }

    @Test fun withSeenByOpenTheListTakesThemInsteadOfASplash() = runTest(dispatcher) {
        val vm = mine()
        vm.openViewers(true)
        repo.views = listOf(MomentViewer("w", 1, true, "😂"))
        repo.frames.emit("m1")
        assertNull(vm.state.value.burst)
        assertEquals("😂", vm.state.value.viewers?.single()?.viewer?.reaction)
        vm.openViewers(false)
        repo.frames.emit("m1")
        // Seen in the list, so it doesn't splash afterwards either.
        assertNull(vm.state.value.burst)
    }

    @Test fun burstsLandingWhileOnePlaysMergeIntoOneWaiting() {
        val q = emptyList<ReactionBurst>()
            .queue(ReactionBurst(1, listOf("😂"), listOf("Amina")))
            .queue(ReactionBurst(2, listOf("❤️"), listOf("Juma")))
            .queue(ReactionBurst(3, listOf("🔥"), listOf("Kevin")))
            .queue(ReactionBurst(4, listOf("❤️"), listOf("Juma")))
        assertEquals(2, q.size)
        assertEquals(listOf("❤️", "🔥"), q[1].shown)
        assertEquals("Juma and Kevin reacted", q[1].line)
        assertEquals("Amina and 3 others reacted", ReactionBurst(1, listOf("😂"), listOf("Amina", "Juma", "Kevin", "Mum")).line)
    }

    @Test fun onlyReactionsNotShownBeforeCount() {
        val views = listOf(MomentViewer("a", 1, true, "😂"), MomentViewer("b", 1, false), MomentViewer("c", 1, true, "❤️"))
        assertEquals(listOf("c"), unseenReactions(views, setOf("a|😂")).map { it.userId })
        assertTrue(unseenReactions(views, setOf("a|😂", "c|❤️")).isEmpty())
    }
}

/** [ViewerFakeMoments] with who viewed and the live `status.viewed` frames. */
internal class SplashFakeMoments(private val base: ViewerFakeMoments) : MomentsRepository by base {
    var views: List<MomentViewer> = emptyList()
    val frames = MutableSharedFlow<String>()
    override val viewedFrames: Flow<String> = frames
    override suspend fun viewers(momentId: String): AppResult<List<MomentViewer>> = AppResult.Success(views)
}
