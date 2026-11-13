package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.status.data.remote.KtorMomentsRemote
import com.circaso.feature.status.data.remote.MomentsRemote
import com.circaso.feature.status.data.repository.MomentKeyCopiesHandler
import com.circaso.feature.status.data.repository.MomentKeys
import com.circaso.feature.status.data.repository.MomentPostHandler
import com.circaso.feature.status.data.repository.MomentsRepositoryImpl
import com.circaso.feature.status.domain.MomentsRepository
import org.koin.core.qualifier.named
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import kotlinx.coroutines.flow.first
import org.koin.dsl.module

/**
 * Moments (backend `status` module ST1–ST8; shown in Chats since Spaces stopped on 2026-11-05): read from Room, posted through the outbox, end to end
 * encrypted with a sender key of their own in the same Signal store as chats.
 */
val statusModule = module {
    single { get<CircasoDatabase>().momentsDao() }
    single<MomentsRemote> { KtorMomentsRemote(get()) }
    single { MomentKeys(get(), get(), get(), get(), get(named("ownUserId"))) }
    single {
        MomentsRepositoryImpl(
            dao = get(), remote = get(), keys = get(), contacts = get(), crypto = get(), files = get(),
            storage = get(named("plain")), outbox = get(), kickOutbox = get(named("kickOutbox")), json = get(),
            ownUserId = get(named("ownUserId")),
            // A new phone asks for keys only once its own are on the server, or a poster could seal for the old ones.
            keysPublished = { get<com.circaso.feature.messaging.domain.KeysRepository>().maintain() is com.circaso.core.common.AppResult.Success },
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
        )
    }
    // The outbox processor collects every handler while it is built; this one finds the repository only when it
    // sends, so the repository (which kicks the outbox and reads contacts) is never part of that graph.
    single { MomentPostHandler { get<MomentsRepositoryImpl>() } } bind OutboxHandler::class
    single { MomentKeyCopiesHandler { get<MomentsRepositoryImpl>() } } bind OutboxHandler::class
    single<MomentsRepository> { get<MomentsRepositoryImpl>() }
    // Moments fetched (and keys asked for) at launch, sign-in and reconnect, whatever screen shows; started in CoreModule.
    single {
        val auth = get<com.circaso.core.session.AuthManager>()
        val connection = get<com.circaso.core.sync.ConnectionState>()
        com.circaso.feature.status.data.repository.MomentsStarter(
            ready = kotlinx.coroutines.flow.combine(auth.state, connection.status) { state, status ->
                state == com.circaso.core.session.AuthState.Active && status == com.circaso.core.platform.ConnectionStatus.Online
            },
            socket = get<com.circaso.core.realtime.RealtimeClient>().connected,
            repository = { get() },
            scope = get(),
        )
    }
    // Debug builds only: a dummy upload through the real pipeline, never posted (see DebugUploadHandler).
    single {
        com.circaso.feature.status.data.repository.DebugUploadHandler(
            remote = get(), files = get(), crypto = get(),
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
            storage = get(named("plain")), outbox = get(),
            // Looked up when used: the outbox processor collects this handler while it is built, and the kick needs the processor.
            kickOutbox = { get<() -> Unit>(named("kickOutbox")).invoke() }, json = get(),
            enabled = get<com.circaso.core.platform.ClientInfo>().debugBuild, now = { com.circaso.core.sync.nowMillis() },
        )
    } bind OutboxHandler::class
    viewModel { com.circaso.feature.status.presentation.MomentsViewModel(get(), get(), get(), get(), get(), observeBlocks = get()) }
    viewModel { params ->
        com.circaso.feature.status.presentation.MomentComposerViewModel(get(), get(), get(), get(), get(), get(), get(), get(),
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
            // Opened for a share from another app: its photos and videos onto the review.
            shared = if (params.getOrNull<Boolean>() == true) com.circaso.feature.mediacompose.IncomingShares::take else null)
    }
    viewModel { com.circaso.feature.status.presentation.MomentsPrivacyViewModel(get(), get()) }
    // V4 Forward: a moment into chats through the chat's own media path (encrypted, queued, sent by the outbox).
    single<com.circaso.feature.status.presentation.MomentForwarder> {
        val media = get<com.circaso.feature.messaging.data.repository.MediaMessages>()
        val messages = get<com.circaso.feature.messaging.domain.MessagesRepository>()
        val library = get<com.circaso.core.platform.MediaLibrary>()
        com.circaso.feature.status.presentation.MomentForwarder { chatId, m ->
            if (m.kind == com.circaso.feature.status.domain.MomentKind.Text) { messages.send(chatId, m.text.orEmpty()); return@MomentForwarder true }
            val bytes = m.bytes ?: m.path?.let { library.read(it) } ?: return@MomentForwarder false
            val r = when (m.kind) {
                com.circaso.feature.status.domain.MomentKind.Image -> media.sendImage(chatId, bytes, m.mime, m.width, m.height, m.caption)
                com.circaso.feature.status.domain.MomentKind.Video -> media.sendVideo(chatId, bytes, m.mime, m.width, m.height, m.durationMs,
                    m.thumb?.let { kotlin.io.encoding.Base64.encode(it) }, m.caption)
                else -> media.sendVoice(chatId, bytes, m.mime, m.durationMs ?: 0)
            }
            r is com.circaso.core.common.AppResult.Success
        }
    }
    viewModel { com.circaso.feature.status.presentation.MomentForwardViewModel(get(), get()) }
    viewModel { params ->
        val chats = get<com.circaso.feature.messaging.domain.ChatsRepository>()
        val messages = get<com.circaso.feature.messaging.domain.MessagesRepository>()
        com.circaso.feature.status.presentation.MomentViewerViewModel(
            params.getOrNull<String>(), get(), get(), get(), get(),
            startAt = params.values.getOrNull(1) as? String,
            observeBlocks = get(),
            replyInChat = { posterId, text, moment ->
                // The 1:1 chat on this phone if there is one (so a reply waits offline like any message), else open it.
                val existing = chats.chats.first().firstOrNull { it.chat.peerId == posterId && it.chat.viaSpaceId == null }?.chat?.chatId
                val chatId = existing ?: when (val r = chats.openDirect(posterId)) {
                    is com.circaso.core.common.AppResult.Success -> r.value.chatId
                    is com.circaso.core.common.AppResult.Failure -> return@MomentViewerViewModel r
                }
                val quote = com.circaso.core.crypto.MomentQuote(moment.momentId, moment.kind.name.uppercase(), (moment.text ?: moment.caption)?.take(140), moment.thumb, moment.expiresAt)
                messages.sendMomentReply(chatId, text, quote)
                com.circaso.core.common.AppResult.Success(chatId)
            },
        )
    }
}
