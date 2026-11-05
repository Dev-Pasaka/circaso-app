package com.circaso.feature.spaces.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.core.sync.nowMillis
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.spaces.domain.MembershipState
import com.circaso.feature.spaces.domain.MuteDuration
import com.circaso.feature.spaces.domain.PostSendState
import com.circaso.feature.spaces.domain.PostingMode
import com.circaso.feature.spaces.domain.Relation
import com.circaso.feature.spaces.domain.Space
import com.circaso.feature.spaces.domain.SpacePost
import com.circaso.feature.spaces.domain.SpaceRole
import com.circaso.feature.spaces.domain.SpacesRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the composer area shows (SH1, SH1b, SH1e and the cases a member can't post in). */
sealed interface ChatFoot {
    data object Composer : ChatFoot
    /** SH1b: only admins post; members read, react and can message the owner. */
    data object AdminsOnly : ChatFoot
    /** SH1e: muted by a moderator until [until]. */
    data class Muted(val until: Long) : ChatFoot
    /** Not following (a visitor of a public space): follow to post. */
    data object Visitor : ChatFoot
    /** SM5: the membership ended; the chat is locked until paid again. */
    data object Ended : ChatFoot
    /** The space went (deleted or removed) or I was banned: nothing to post into. */
    data object Closed : ChatFoot
}

/** The post menu (SH1c): opened by a long press on one post; [muting] shows the mute durations inline. */
data class PostMenu(val localKey: String, val muting: Boolean = false, val confirmRemoveMember: Boolean = false)

data class SpaceChatUiState(
    val spaceId: String,
    val space: Space? = null,
    val posts: List<SpacePost> = emptyList(),
    val myUserId: String? = null,
    val online: Boolean = true,
    /** The first load: nothing on the phone and nothing back yet (skeleton rows, no spinner). */
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    /** No more older posts on the server. */
    val atStart: Boolean = false,
    /** SH1f: a page of older posts didn't load; Try Again and the reference, in place. */
    val olderFailed: Boolean = false,
    val olderRef: String? = null,
    val draft: String = "",
    val replyTo: SpacePost? = null,
    val menu: PostMenu? = null,
    /** An action is going (remove, mute) so its button shows progress. */
    val busy: Boolean = false,
    val now: Long = nowMillis(),
) {
    val myRole: SpaceRole? get() = space?.me?.role
    val memberCount: Int get() = space?.summary?.memberCount ?: 0
    val canMessageOwner: Boolean get() = space?.canMessageOwner == true

    val foot: ChatFoot
        get() {
            val s = space ?: return ChatFoot.Composer
            val me = s.me
            return when {
                s.state != com.circaso.feature.spaces.domain.SpaceState.Live || me.relation == Relation.Banned -> ChatFoot.Closed
                me.relation == Relation.Ended || me.membership?.state == MembershipState.Ended -> ChatFoot.Ended
                me.relation != Relation.Member -> ChatFoot.Visitor
                me.mutedAt(now) -> ChatFoot.Muted(me.mutedUntil!!)
                s.postingMode == PostingMode.AdminsOnly && !me.isAdmin -> ChatFoot.AdminsOnly
                else -> ChatFoot.Composer
            }
        }

    val canReact: Boolean get() = space?.me?.relation == Relation.Member && foot != ChatFoot.Ended && foot != ChatFoot.Closed
    val canSend: Boolean get() = foot == ChatFoot.Composer && draft.isNotBlank()
    val menuPost: SpacePost? get() = menu?.let { m -> posts.firstOrNull { it.localKey == m.localKey } }

    /** What I may do to this post's author: the role rule (only someone below me, never the owner, never myself). */
    fun canModerate(post: SpacePost): Boolean {
        val mine = myRole ?: return false
        if (!mine.isModerator || post.mine || post.author.userId == myUserId) return false
        val theirs = post.author.role ?: SpaceRole.Member
        return theirs != SpaceRole.Owner && theirs.rank < mine.rank
    }
    fun canRemovePost(post: SpacePost): Boolean = post.postId != null && post.removed == null && (post.mine || canModerate(post))
    fun canRemoveMember(post: SpacePost): Boolean = myRole?.isAdmin == true && canModerate(post)
}

sealed interface SpaceChatEvent {
    data class OpenChat(val chatId: String) : SpaceChatEvent
    data class Toast(val text: String) : SpaceChatEvent
}

/**
 * The space chat (SH1–SH1f). Posts come from Room and refresh on open and on reconnect; sending and reacting are queued
 * (they wait offline with a clock); removing, muting and removing a member are live and disabled offline. The read
 * marker moves as the newest post shows; the chat is "open" for the repository while this lives, so its own posts don't
 * count as unread.
 */
class SpaceChatViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    private val chats: ChatsRepository,
    connection: ConnectionState,
    private val ownUserId: suspend () -> String?,
    private val now: () -> Long = ::nowMillis,
) : ViewModel() {
    private val local = MutableStateFlow(SpaceChatUiState(spaceId))
    private val _events = Channel<SpaceChatEvent>(Channel.BUFFERED)
    val events: Flow<SpaceChatEvent> = _events.receiveAsFlow()
    private var lastRead: String? = null

    val state: StateFlow<SpaceChatUiState> = combine(local, spaces.space(spaceId), spaces.posts(spaceId), connection.status) { ui, space, posts, c ->
        ui.copy(space = space, posts = posts, online = c == ConnectionStatus.Online, loading = ui.loading && posts.isEmpty(), now = now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    init {
        spaces.setOpen(spaceId)
        viewModelScope.launch { local.update { it.copy(myUserId = ownUserId()) } }
        refresh()
        viewModelScope.launch {
            var wasOnline = true
            connection.status.collect { s ->
                val online = s == ConnectionStatus.Online
                if (online && !wasOnline) refresh() // back online: catch up
                wasOnline = online
            }
        }
        // The read marker follows the newest post that came from the server (never back).
        viewModelScope.launch {
            combine(spaces.posts(spaceId), spaces.space(spaceId)) { list, space -> list to (space?.me?.relation == Relation.Member) }.collect { (list, member) ->
                val newest = list.lastOrNull { it.postId != null }?.postId ?: return@collect
                if (member && newest != lastRead) { lastRead = newest; spaces.markRead(spaceId, newest) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { spaces.refreshSpace(spaceId) }
        viewModelScope.launch {
            spaces.refreshPosts(spaceId)
            local.update { it.copy(loading = false) }
        }
    }

    fun onLoadOlder() {
        val s = state.value
        if (s.loadingOlder || s.atStart || !s.online) return
        local.update { it.copy(loadingOlder = true, olderFailed = false) }
        viewModelScope.launch {
            when (val r = spaces.loadOlder(spaceId)) {
                is AppResult.Success -> local.update { it.copy(loadingOlder = false, atStart = !r.value) }
                is AppResult.Failure -> local.update {
                    // Offline is not a failure here: the banner says it; the page loads when the connection is back.
                    if (r.error is AppError.Network) it.copy(loadingOlder = false)
                    else it.copy(loadingOlder = false, olderFailed = true, olderRef = ErrorMessages.reference(r.error))
                }
            }
        }
    }

    fun onDraft(text: String) = local.update { it.copy(draft = text.take(4000)) }

    /** The screen's state with what was just typed or chosen (the shared state follows a moment later). */
    private fun current() = local.value.let { l -> state.value.copy(draft = l.draft, replyTo = l.replyTo, menu = l.menu, busy = l.busy) }

    fun onSend() {
        val s = current()
        if (!s.canSend) return
        val text = s.draft
        val reply = s.replyTo
        local.update { it.copy(draft = "", replyTo = null) }
        viewModelScope.launch { spaces.sendPost(spaceId, text, reply) }
    }

    fun onLongPress(post: SpacePost) = local.update { it.copy(menu = PostMenu(post.localKey)) }
    fun onDismissMenu() = local.update { it.copy(menu = null) }
    fun onMuteChoices() = local.update { it.copy(menu = it.menu?.copy(muting = true)) }
    fun onConfirmRemoveMember() = local.update { it.copy(menu = it.menu?.copy(confirmRemoveMember = true)) }

    fun onReply(post: SpacePost) = local.update { it.copy(replyTo = post.takeIf { p -> p.postId != null }, menu = null) }
    fun onCancelReply() = local.update { it.copy(replyTo = null) }

    /** A reaction is queued (it shows at once with a clock offline); tapping mine takes it back. */
    fun onReact(post: SpacePost, emoji: String?) {
        local.update { it.copy(menu = null) }
        if (!state.value.canReact || post.postId == null) return
        viewModelScope.launch { spaces.react(spaceId, post.localKey, emoji) }
    }

    fun onRemovePost(post: SpacePost) {
        val id = post.postId ?: return
        if (!state.value.online) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = spaces.removePost(spaceId, id)
            local.update { it.copy(busy = false, menu = null) }
            _events.send(SpaceChatEvent.Toast(if (r is AppResult.Success) "Post removed" else spacesErrorText((r as AppResult.Failure).error)))
        }
    }

    fun onMute(post: SpacePost, duration: MuteDuration) {
        if (!state.value.online) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = spaces.mute(spaceId, post.author.userId, duration)
            local.update { it.copy(busy = false, menu = null) }
            _events.send(SpaceChatEvent.Toast(if (r is AppResult.Success) "${post.author.displayName.substringBefore(' ')} is muted ${if (duration == MuteDuration.UntilUnmuted) "until you unmute them" else "for ${duration.label}"}" else spacesErrorText((r as AppResult.Failure).error)))
        }
    }

    fun onRemoveMember(post: SpacePost) {
        if (!state.value.online) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = spaces.remove(spaceId, post.author.userId)
            local.update { it.copy(busy = false, menu = null) }
            _events.send(SpaceChatEvent.Toast(if (r is AppResult.Success) "${post.author.displayName.substringBefore(' ')} was removed" else spacesErrorText((r as AppResult.Failure).error)))
        }
    }

    fun onRetry(post: SpacePost) { local.update { it.copy(menu = null) }; viewModelScope.launch { spaces.retryPost(post.localKey) } }
    fun onDiscard(post: SpacePost) { local.update { it.copy(menu = null) }; viewModelScope.launch { spaces.discardPost(post.localKey) } }

    /** Message Privately (SH1b, SH1e): the end-to-end encrypted chat with the owner, made on the server if new. */
    fun onMessagePrivately() {
        val owner = state.value.space?.owner?.userId ?: return
        if (!state.value.online) return
        viewModelScope.launch {
            when (val r = chats.openViaSpace(owner, spaceId)) {
                is AppResult.Success -> _events.send(SpaceChatEvent.OpenChat(r.value.chatId))
                is AppResult.Failure -> _events.send(SpaceChatEvent.Toast(spacesErrorText(r.error)))
            }
        }
    }

    override fun onCleared() { spaces.setOpen(null) }

    /** For a post waiting or refused: whether its own menu (Retry, Discard) applies. */
    fun pending(post: SpacePost) = post.sendState != PostSendState.Sent
}
