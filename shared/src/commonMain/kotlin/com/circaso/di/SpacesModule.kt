package com.circaso.di

import com.circaso.core.common.AppResult
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.PaymentCard
import com.circaso.feature.profile.domain.ObserveProfileUseCase
import com.circaso.feature.spaces.data.remote.KtorSpacesRemote
import com.circaso.feature.spaces.data.remote.SpacesRemote
import com.circaso.feature.spaces.data.repository.KtorSpaceMediaUploader
import com.circaso.feature.spaces.data.repository.SpacePostHandler
import com.circaso.feature.spaces.data.repository.SpaceReactionHandler
import com.circaso.feature.spaces.data.repository.SpacesRepositoryImpl
import com.circaso.feature.spaces.domain.PostAuthor
import com.circaso.feature.spaces.domain.SpacesRepository
import kotlinx.coroutines.flow.first
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Each area's ViewModels live with its screens, so the areas never edit the same module. Declared before
 * [spacesModule], which includes it: top-level values initialise in file order, and a module included before it
 * exists is null at startup (it crashed the app on launch, 2026-11-05).
 */
val spacesViewModels = module {
    includes(
        com.circaso.feature.spaces.presentation.discover.spacesDiscoverModule,
        com.circaso.feature.spaces.presentation.page.spacePageModule,
        com.circaso.feature.spaces.presentation.create.spaceCreateModule,
        com.circaso.feature.spaces.presentation.chat.spaceChatModule,
    )
}

/**
 * Spaces core (phase 3, references/spaces-api.md): read from Room, posts and reactions through the outbox, everything
 * else live. Behind `SpacesFlag` (CoreModule). The screens' ViewModels are declared in [spacesViewModels].
 */
val spacesModule = module {
    single { get<CircasoDatabase>().spacesDao() }
    single<SpacesRemote> { KtorSpacesRemote(get(), get()) }
    single {
        val observeProfile = get<ObserveProfileUseCase>()
        val chats = get<ChatsRepository>()
        val messages = get<MessagesRepository>()
        SpacesRepositoryImpl(
            dao = get(), remote = get(), flag = get(), outbox = get(), kickOutbox = get(named("kickOutbox")), json = get(),
            ownUserId = get(named("ownUserId")),
            me = { observeProfile().first()?.let { PostAuthor(it.userId, it.displayName, it.handle, it.avatarUrl, null) } },
            uploader = KtorSpaceMediaUploader(get(), get(named("plain"))),
            // SM2: the membership receipt lands in the private chat with the owner (end-to-end encrypted, as any card).
            postReceipt = { space, receipt ->
                val owner = space.owner?.userId ?: return@SpacesRepositoryImpl false
                val chat = (chats.openViaSpace(owner, space.spaceId) as? AppResult.Success)?.value ?: return@SpacesRepositoryImpl false
                messages.sendPayment(chat.chatId, PaymentCard(receipt.transferId, receipt.amountMinor, "USDC", receipt.blockchain, receipt.note))
                true
            },
        )
    }
    single<SpacesRepository> { get<SpacesRepositoryImpl>() }
    // The outbox collects every handler while it is built; these find the repository only when they send.
    single { SpacePostHandler { get<SpacesRepositoryImpl>() } } bind OutboxHandler::class
    single { SpaceReactionHandler { get<SpacesRepositoryImpl>() } } bind OutboxHandler::class
    includes(spacesViewModels)
}
