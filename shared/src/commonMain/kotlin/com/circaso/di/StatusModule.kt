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
 * Moments (Spaces phase 1, backend `status` module ST1–ST8): read from Room, posted through the outbox, end to end
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
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
        )
    }
    // The outbox processor collects every handler while it is built; this one finds the repository only when it
    // sends, so the repository (which kicks the outbox and reads contacts) is never part of that graph.
    single { MomentPostHandler { get<MomentsRepositoryImpl>() } } bind OutboxHandler::class
    single { MomentKeyCopiesHandler { get<MomentsRepositoryImpl>() } } bind OutboxHandler::class
    single<MomentsRepository> { get<MomentsRepositoryImpl>() }
    viewModel { com.circaso.feature.status.presentation.SpacesViewModel(get(), get(), get(), get(), get()) }
    viewModel { com.circaso.feature.status.presentation.MomentComposerViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { com.circaso.feature.status.presentation.MomentsPrivacyViewModel(get(), get()) }
    viewModel { params ->
        val chats = get<com.circaso.feature.messaging.domain.ChatsRepository>()
        val messages = get<com.circaso.feature.messaging.domain.MessagesRepository>()
        com.circaso.feature.status.presentation.MomentViewerViewModel(
            params.getOrNull<String>(), get(), get(), get(), get(),
            startAt = params.values.getOrNull(1) as? String,
            replyInChat = { posterId, text, moment ->
                // The 1:1 chat on this phone if there is one (so a reply waits offline like any message), else open it.
                val existing = chats.chats.first().firstOrNull { it.chat.peerId == posterId }?.chat?.chatId
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
