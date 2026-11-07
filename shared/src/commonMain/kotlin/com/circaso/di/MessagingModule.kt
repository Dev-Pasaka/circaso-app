package com.circaso.di

import com.circaso.core.database.CircasoDatabase
import com.circaso.core.realtime.RealtimeClient
import com.circaso.core.session.AuthManager
import com.circaso.core.sync.OutboxHandler
import com.circaso.feature.messaging.data.keys.KeyMaintainer
import com.circaso.feature.messaging.data.keys.KeysRepositoryImpl
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.remote.ChatsRemote
import com.circaso.feature.messaging.data.remote.KeysRemote
import com.circaso.feature.messaging.data.remote.KtorChatsRemote
import com.circaso.feature.messaging.data.remote.KtorKeysRemote
import com.circaso.feature.messaging.data.remote.KtorMessagesRemote
import com.circaso.feature.messaging.data.remote.MessagesRemote
import com.circaso.feature.messaging.data.repository.ChatSendHandler
import com.circaso.feature.messaging.data.repository.ChatsRepositoryImpl
import com.circaso.feature.messaging.data.repository.GroupKeys
import com.circaso.feature.messaging.data.repository.InboxProcessor
import com.circaso.feature.messaging.data.repository.MessagesRepositoryImpl
import com.circaso.feature.messaging.data.repository.MessagingFrameHandler
import com.circaso.feature.messaging.data.repository.MessagingStarter
import com.circaso.feature.messaging.data.repository.SessionCipher
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.KeysRepository
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.AddMembersUseCase
import com.circaso.feature.messaging.domain.CreateGroupUseCase
import com.circaso.feature.messaging.domain.DeleteForEveryoneUseCase
import com.circaso.feature.messaging.domain.DiscardMessageUseCase
import com.circaso.feature.messaging.domain.LeaveGroupUseCase
import com.circaso.feature.messaging.domain.MarkReadUseCase
import com.circaso.feature.messaging.domain.MessagingSettingsUseCase
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.messaging.domain.ObserveThreadUseCase
import com.circaso.feature.messaging.domain.ObserveTypingUseCase
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.RefreshChatsUseCase
import com.circaso.feature.messaging.domain.RemoveMemberUseCase
import com.circaso.feature.messaging.domain.RenameGroupUseCase
import com.circaso.feature.messaging.domain.RetryMessageUseCase
import com.circaso.feature.messaging.domain.SecurityCodeUseCase
import com.circaso.feature.messaging.domain.SendMessageUseCase
import com.circaso.feature.messaging.domain.SendPaymentCardUseCase
import com.circaso.feature.messaging.domain.SetMembersCanAddUseCase
import com.circaso.feature.messaging.domain.SetRoleUseCase
import com.circaso.feature.messaging.domain.SetTypingUseCase
import com.circaso.feature.messaging.presentation.ChatInfoViewModel
import com.circaso.feature.messaging.presentation.ChatsViewModel
import com.circaso.feature.messaging.presentation.NewChatViewModel
import com.circaso.feature.messaging.presentation.NewGroupViewModel
import com.circaso.feature.messaging.presentation.ThreadViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Encrypted chats: the key directory (M1–M3), chats and members (M4–M11), messages and delivery (M12–M14, M22). */
val messagingModule = module {
    single { get<CircasoDatabase>().signalDao() }
    single { get<CircasoDatabase>().chatsDao() }
    single { get<CircasoDatabase>().messagesDao() }
    single { get<CircasoDatabase>().reactionsDao() }
    single { SealedRecordStore(get(), get()) }
    single<KeysRemote> { KtorKeysRemote(get()) }
    single<ChatsRemote> { KtorChatsRemote(get()) }
    single<MessagesRemote> { KtorMessagesRemote(get()) }
    single { SignedKeyUpload(get(), get()) }
    single<KeysRepository> { KeysRepositoryImpl(get(), get(), get(), get(), get()) }
    single { KeyMaintainer(get(), get(), get(), get()) }

    single<suspend () -> String?>(named("ownUserId")) { val auth = get<AuthManager>(); { auth.userId } }
    single {
        val chats = get<com.circaso.feature.messaging.data.local.ChatsDao>()
        // A new identity (new phone) holds none of my keys: not my moments key (my next moment carries a fresh copy for
        // them) and not my sender key in any group (my next group message hands it over again). The moments scope is
        // one of the chat ids, so the second covers it; both are kept for clarity.
        SessionCipher(get(), get(), get(), identityChanged = {
            chats.deleteDelivery(com.circaso.feature.status.data.repository.MomentKeys.Scope, it)
            chats.deleteDeliveriesTo(it)
        })
    }
    single { GroupKeys(get(), get(), get(), get(), get(), get(), get(named("ownUserId"))) }
    single {
        com.circaso.feature.messaging.data.repository.GroupPhotos(
            get(), get(named("plain")), get(), get(), get(),
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
        )
    }
    single { ChatsRepositoryImpl(get(), get(), get(), get(), get(), get(named("ownUserId")), photos = get()) } bind ChatsRepository::class
    single { get<CircasoDatabase>().recoveryDao() }
    single { com.circaso.feature.messaging.data.repository.ChatPaymentCards(get(), get(), get(), get(named("ownUserId")), { id -> get<ChatsRepositoryImpl>().refreshChat(id) }) }
    single { com.circaso.feature.messaging.data.repository.RetryReceipts(get(), get(), get(), get(), get(), get(), get(), get(named("ownUserId"))) }
    single {
        InboxProcessor(
            get(), get(), get(), get(), get(), get<RealtimeClient>(), { id -> get<ChatsRepositoryImpl>().refreshChat(id) },
            onPaymentReceived = { id -> get<com.circaso.feature.wallet.domain.WalletRepository>().loadTransfer(id) }, reactionsDao = get(),
            retries = get(), paymentCards = get(),
        )
    }
    single { MessagesRepositoryImpl(get(), get(), get(), get(), get(), get(named("kickOutbox")), get<RealtimeClient>(), get(), get(named("ownUserId")), reactionsDao = get(), paymentCards = get()) } bind MessagesRepository::class
    single { ChatSendHandler(get(), get(), get(), get(), get(), get(), get(named("ownUserId")), { id -> get<ChatsRepositoryImpl>().refreshChat(id) },
            uploadMedia = { id -> get<com.circaso.feature.messaging.data.repository.MediaMessages>().uploadPending(id) },
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads) } bind OutboxHandler::class
    single<com.circaso.feature.messaging.data.repository.AttachmentsRemote> { com.circaso.feature.messaging.data.repository.KtorAttachmentsRemote(get()) }
    single {
        com.circaso.feature.messaging.data.repository.MediaMessages(
            get(), get(), get(), get(named("kickOutbox")), get(), get(), get(), get(named("plain")), get(), get(named("ownUserId")),
            now = { com.circaso.core.sync.nowMillis() },
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
        )
    }
    single { MessagingFrameHandler(get(), get(), get(), get(), get(), get()) }
    // Notifications (2026-11-06): pushes worded on the phone with privacy, and where a tap lands.
    single { com.circaso.core.notifications.PushNotices(get(), get(), get(), get(), get(), get(), get(), get()) }
    factory<com.circaso.core.notifications.NotificationLookups> { com.circaso.core.notifications.AppNotificationLookups(get(), get(), get()) }
    single { MessagingStarter(get<RealtimeClient>(), get(), get(), get(), live = get()) }

    factory { ObserveChatsUseCase(get()) }
    factory { ObserveChatUseCase(get()) }
    factory { RefreshChatsUseCase(get()) }
    factory { OpenDirectChatUseCase(get()) }
    factory { CreateGroupUseCase(get()) }
    factory { RenameGroupUseCase(get()) }
    factory { com.circaso.feature.messaging.domain.SetGroupPhotoUseCase(get()) }
    factory { SetMembersCanAddUseCase(get()) }
    factory { AddMembersUseCase(get()) }
    factory { RemoveMemberUseCase(get()) }
    factory { LeaveGroupUseCase(get()) }
    factory { SetRoleUseCase(get()) }
    factory { SecurityCodeUseCase(get()) }
    factory { ObserveThreadUseCase(get()) }
    factory { SendMessageUseCase(get()) }
    factory { SendPaymentCardUseCase(get()) }
    factory { RetryMessageUseCase(get()) }
    factory { DiscardMessageUseCase(get()) }
    factory { DeleteForEveryoneUseCase(get()) }
    factory { MarkReadUseCase(get()) }
    factory { SetTypingUseCase(get()) }
    factory { ObserveTypingUseCase(get()) }
    factory { MessagingSettingsUseCase(get()) }

    viewModel { ChatsViewModel(get(), get(), get(), get(), get(), get(), get(), getOrNull()) }
    viewModel {
        NewChatViewModel(
            get(), get(), get(), observeChats = get(), connect = get(), createShareLink = get(),
            reader = get(), syncPhoneBook = get(),
        )
    }
    factory {
        com.circaso.feature.profile.presentation.PhotoSources(
            get(), get(), get(),
        )
    }
    viewModel {
        NewGroupViewModel(
            get(), get(), get(), groupWallets = get(), observeWallet = get(), setGroupPhoto = get(),
            encoder = getOrNull<com.circaso.core.platform.GroupPhotoEncoder>() ?: com.circaso.core.platform.NoGroupPhotoEncoder,
            sources = getOrNull(),
        )
    }
    single { com.circaso.feature.messaging.data.repository.MapPictures(get()) }
    single { com.circaso.feature.messaging.data.repository.ChatSealer(get(), get(), get(), get(), get(named("ownUserId"))) }
    single {
        com.circaso.feature.messaging.data.repository.LiveLocations(
            get(), get(), get(), get(),
            { chatId, point, liveId, until -> get<com.circaso.feature.messaging.data.repository.MediaMessages>().sendLive(chatId, point, liveId, until) },
            getOrNull<com.circaso.feature.messaging.data.repository.LiveTracker>() ?: com.circaso.feature.messaging.data.repository.NoLiveTracker,
        )
    }
    factory {
        com.circaso.feature.messaging.presentation.ThreadMedia(get(), get(), get(), get(), get(), get())
    }
    factory { com.circaso.feature.messaging.presentation.ThreadPayments(get()) }
    // The chat's media composer (the moment composer's screens): each item into the chat's own media queue.
    single<com.circaso.feature.messaging.presentation.ChatMediaSender> {
        val media = get<com.circaso.feature.messaging.data.repository.MediaMessages>()
        val library = get<com.circaso.core.platform.MediaLibrary>()
        com.circaso.feature.messaging.presentation.ChatMediaSender { chatId, item, album -> sendPrepared(media, library, chatId, item, album) }
    }
    viewModel { params ->
        com.circaso.feature.messaging.presentation.ChatMediaComposerViewModel(
            params.get<String>(0), com.circaso.feature.messaging.presentation.ChatComposeStart.valueOf(params.get<String>(1)),
            get(), getOrNull(), get(), get(),
            uploads = getOrNull<com.circaso.core.platform.BackgroundUploads>() ?: com.circaso.core.platform.NoBackgroundUploads,
        )
    }
    viewModel { params -> ThreadViewModel(params.get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), contacts = get(), payments = get()) }
    viewModel { params ->
        com.circaso.feature.messaging.presentation.map.LocationMapViewModel(params.get(), params.get(), get(), get(), getOrNull())
    }
    viewModel { params ->
        ChatInfoViewModel(
            params.get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), observeThread = get(), media = getOrNull(),
            groupMoney = get(), myUserId = get<com.circaso.core.session.AuthManager>().userId,
            setGroupPhoto = get(),
            encoder = getOrNull<com.circaso.core.platform.GroupPhotoEncoder>() ?: com.circaso.core.platform.NoGroupPhotoEncoder,
            sources = getOrNull(),
        )
    }
}

/**
 * One prepared item into a chat's queue: a photo, a video (with its first frame as the bubble's poster, the moment's
 * tiny thumb when the phone can't make one) or a document. Queued, encrypted and uploaded exactly as before.
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
private suspend fun sendPrepared(
    media: com.circaso.feature.messaging.data.repository.MediaMessages,
    library: com.circaso.core.platform.MediaLibrary,
    chatId: String,
    item: com.circaso.feature.mediacompose.PreparedItem,
    album: String?,
): Boolean {
    val r = when (item) {
        is com.circaso.feature.mediacompose.PreparedItem.Shot -> {
            val m = item.media
            if (m.mime.startsWith("video")) {
                val poster = m.path?.let { library.poster(it) } ?: m.thumb
                media.sendVideo(chatId, item.bytes, m.mime, m.width, m.height, m.durationMs, poster?.let { kotlin.io.encoding.Base64.encode(it) }, item.caption, album)
            } else media.sendImage(chatId, item.bytes, m.mime, m.width, m.height, item.caption, album)
        }
        is com.circaso.feature.mediacompose.PreparedItem.Document -> media.sendDocument(chatId, item.bytes, item.file.mime, item.file.name, item.caption)
        is com.circaso.feature.mediacompose.PreparedItem.Voice -> media.sendVoice(chatId, item.capture.bytes, item.capture.mime, item.capture.durationMs)
        else -> return false
    }
    return r is com.circaso.core.common.AppResult.Success
}
