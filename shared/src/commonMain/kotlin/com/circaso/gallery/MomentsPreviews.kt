package com.circaso.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.circaso.feature.messaging.presentation.ChatFilter
import com.circaso.feature.messaging.presentation.ChatsContent
import com.circaso.feature.messaging.presentation.ChatsUiState
import com.circaso.feature.status.presentation.momentsList

/**
 * Debug previews of Moments in Chats (`-preview=chats-moments…`, `chats-rings`). Kept out of [PreviewScreens], whose
 * one `when` is at the JVM's method size limit.
 */
@Composable
internal fun MomentsChatsPreview(name: String): Boolean {
    when (name) {
        // Moments in Chats (the Moments chip, S1 and its states; Spaces stopped 2026-11-05): sample rings, no session.
        "chats-moments" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments()))
        "chats-moments-muted" -> MomentsMutedOpenPreview()
        "chats-moments-empty" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(com.circaso.feature.status.presentation.MomentsUiState(myName = "Amina Wanjiru", myAvatarUrl = DemoPhotos.You, loaded = true, refreshed = true)))
        "chats-moments-loading" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(com.circaso.feature.status.presentation.MomentsUiState(myName = "Amina Wanjiru", myAvatarUrl = DemoPhotos.You, loaded = true)))
        "chats-moments-offline" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments, online = false), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(com.circaso.feature.status.presentation.MomentsUiState(myName = "Amina Wanjiru", myAvatarUrl = DemoPhotos.You, loaded = true, refreshed = true, online = false)))
        "chats-moments-error" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(com.circaso.feature.status.presentation.MomentsUiState(myName = "Amina Wanjiru", myAvatarUrl = DemoPhotos.You, loaded = true, refreshed = true, failed = true, requestId = "req_7Hq2kD")))
        "chats-moments-posting" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments().copy(myMoments = emptyList(), pending = listOf(com.circaso.feature.status.domain.PendingMoment("p1", com.circaso.feature.status.domain.MomentKind.Image, com.circaso.feature.status.domain.PendingState.Posting, null, 0, progress = 0.4f)))))
        "chats-moments-failed" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments().copy(pending = listOf(com.circaso.feature.status.domain.PendingMoment("p1", com.circaso.feature.status.domain.MomentKind.Image, com.circaso.feature.status.domain.PendingState.Failed, null, 0)))))
        // The chat list with rings on the people who have a moment I haven't seen (Mum), and the chip's dot.
        "chats-rings" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments()))
        "chats-moments-waiting" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments, online = false), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments().copy(online = false, myMoments = emptyList(), pending = listOf(com.circaso.feature.status.domain.PendingMoment("p1", com.circaso.feature.status.domain.MomentKind.Text, com.circaso.feature.status.domain.PendingState.Waiting, null, 0)))))
        "chats-moments-starting" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments().copy(myMoments = emptyList(), pending = listOf(com.circaso.feature.status.domain.PendingMoment("p1", com.circaso.feature.status.domain.MomentKind.Text, com.circaso.feature.status.domain.PendingState.Posting, null, 0)))))
        "chats-moments-no-contacts" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(com.circaso.feature.status.presentation.MomentsUiState(myName = "Amina Wanjiru", myAvatarUrl = DemoPhotos.You, loaded = true, refreshed = true, hasContacts = false)))
        "chats-moments-no-match" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments, query = "Zawadi"), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments()))
        "chats-moments-stale" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(sampleMoments().copy(failed = true, checkFailedWithData = true, requestId = "req_7Hq2kD")))
        // A new phone (2026-11-08): rings whose moments can't be opened here yet, and one row opened to say why.
        "chats-moments-new-phone" -> ChatsContent(ChatsUiState(chats = chatList, loaded = true, filter = ChatFilter.Moments), PaddingValues(bottom = 90.dp), {}, {}, {}, onNewChat = {}, moments = momentsSlot(newPhoneMoments()))
        "chats-moments-new-phone-open" -> {
            val c = com.circaso.designsystem.theme.CircasoTheme.colors
            androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.fillMaxSize().background(c.bg)) {
                newPhoneMoments().rings.filter { it.allLocked }.forEach { r ->
                    item { com.circaso.feature.status.presentation.LockedRingRow(r, androidx.compose.ui.Modifier, initiallyOpen = true) }
                }
            }
        }
        else -> return false
    }
    return true
}

internal val noMomentsActions = com.circaso.feature.status.presentation.MomentsActions({}, {}, {}, {})

internal fun momentsSlot(state: com.circaso.feature.status.presentation.MomentsUiState) =
    com.circaso.feature.messaging.presentation.MomentsSlot(state, noMomentsActions)

/** The Moments list with Muted opened (it starts folded): the list itself, as the chip draws it. */
@Composable
internal fun MomentsMutedOpenPreview() {
    val c = com.circaso.designsystem.theme.CircasoTheme.colors
    androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.fillMaxSize().background(c.bg)) {
        momentsList(sampleMoments(), "", mutedOpen = true, onToggleMuted = {}, actions = noMomentsActions, now = com.circaso.core.sync.nowMillis())
    }
}

/** A new phone: Baraka's moments wait for his phone, Njeri's can't be opened here, Wanjiru has one open and two on the way. */
internal fun newPhoneMoments(): com.circaso.feature.status.presentation.MomentsUiState {
    val now = com.circaso.core.sync.nowMillis()
    fun lock(id: String, who: String, state: com.circaso.feature.status.domain.LockState, minutesAgo: Long) =
        com.circaso.feature.status.domain.LockedMoment(id, who, now - minutesAgo * 60_000, now + 3_600_000, state)
    val waiting = com.circaso.feature.status.domain.LockState.Waiting
    val readable = sampleMoments().rings.first()
    return sampleMoments().copy(
        rings = listOf(
            readable.copy(locked = listOf(lock("w1", readable.posterId, waiting, 5), lock("w2", readable.posterId, waiting, 8))),
            com.circaso.feature.status.domain.MomentRing("b", "Baraka Otieno", DemoPhotos.Baraka, emptyList(), muted = false, locked = listOf(lock("b1", "b", waiting, 15))),
            com.circaso.feature.status.domain.MomentRing("n", "Njeri", DemoPhotos.Njeri, emptyList(), muted = false,
                locked = listOf(lock("n1", "n", com.circaso.feature.status.domain.LockState.Unavailable, 600))),
        ),
    )
}

/** Moments from people in the sample contacts: three new (Mum among them, so her chat row is ringed), one seen, one muted. */
internal fun sampleMoments(): com.circaso.feature.status.presentation.MomentsUiState {
    val now = com.circaso.core.sync.nowMillis()
    fun moment(id: String, who: String, viewed: Boolean, minutesAgo: Long) = com.circaso.feature.status.domain.Moment(
        momentId = id, posterId = who, kind = com.circaso.feature.status.domain.MomentKind.Text, text = "Sunday market haul",
        postedAt = now - minutesAgo * 60_000, expiresAt = now + 3_600_000, mine = false, viewed = viewed, reacted = false,
    )
    fun ring(who: String, name: String, viewed: Boolean, minutesAgo: Long, muted: Boolean = false) =
        com.circaso.feature.status.domain.MomentRing(who, name, DemoPhotos.of(name), listOf(moment("m-$who", who, viewed, minutesAgo)), muted = muted)
    return com.circaso.feature.status.presentation.MomentsUiState(
        myName = "Amina Wanjiru", myAvatarUrl = DemoPhotos.You, loaded = true, refreshed = true,
        myMoments = listOf(moment("mine", "me", true, 50).copy(mine = true, viewCount = 12)),
        rings = listOf(
        ring("3", "Wanjiru Kamau", false, 34), ring("2", "Mum", false, 120), ring("f", "Faith Chebet", false, 200),
        ring("5", "Otieno", true, 300), ring("6", "Achieng", false, 400, muted = true),
        ),
    )
}
