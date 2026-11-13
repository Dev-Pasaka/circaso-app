package com.circaso.core.notifications

import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.platform.LocalSealer
import com.circaso.core.platform.SecureStorage
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.local.SignalDao
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.names.domain.PeopleNames
import com.circaso.feature.wallet.domain.WalletRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * What iOS's Notification Service Extension needs to word a push the way Android does (2026-11-13): the sender's name
 * as this phone shows it, their photo, the group's name, what was said, an amount in the person's currency, without
 * the app's database and without the network.
 *
 * The extension (iosApp/NotificationService) is another process with about 24 MB and 30 seconds, in Swift with
 * libsignal only: it can't run the app, and it must never change the app's Signal state (a ratchet moved there and
 * not here would break the chat). So the app writes this snapshot into the App Group each time it leaves the screen,
 * the last moment its Signal state changes before iOS suspends it. The extension opens a push's ciphertext (which the
 * server sends since 2026-11-13) against an in-memory copy of these records and throws the copy away: a peek. A
 * ratchet can always be stepped forward from an older state, so every message sent after the snapshot opens; the
 * app opens the same messages for real from the inbox when it next runs. One the snapshot can't open (a session
 * started after it) is worded by name only, never wrongly.
 *
 * The whole snapshot is sealed with AES-256-GCM under its own random key ([KeyName] in the Keychain, readable after
 * first unlock, shared with the extension through the Keychain access group). The records inside are the plain
 * libsignal records, so the extension needs one open, not one per row.
 *
 * Keep the shape in step with `Snapshot.swift` in the extension.
 */
@Serializable
data class NotificationSnapshot(
    val v: Int = Version,
    val me: String? = null,
    val identity: Identity,
    val preKeys: List<Record> = emptyList(),
    val signedPreKeys: List<Record> = emptyList(),
    val kyberPreKeys: List<Record> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val senderKeys: List<SenderKey> = emptyList(),
    val chats: List<Chat> = emptyList(),
    val people: List<Person> = emptyList(),
    /** The person's currency and its rate to one US dollar, for money in their own currency ("KES 1,300"). */
    val fiat: Fiat? = null,
) {
    @Serializable data class Identity(val keyPair: String, val registrationId: Int)
    @Serializable data class Record(val id: Int, val record: String)
    @Serializable data class Session(val userId: String, val record: String)
    @Serializable data class SenderKey(val chatId: String, val senderId: String, val distributionId: String, val record: String)

    /** [title] is a group's own name (null when this phone can't read it); [photo] the group photo's file in [PhotosDir]. */
    @Serializable data class Chat(val id: String, val group: Boolean, val title: String? = null, val photo: String? = null, val members: List<String> = emptyList())

    /** [name] is the name I saved for them, else their own; [photo] their profile photo's file in [PhotosDir]. */
    @Serializable data class Person(val id: String, val name: String, val photo: String? = null)
    @Serializable data class Fiat(val currency: String, val rate: String)

    companion object {
        const val Version = 1
        const val Label = "notification-snapshot:1"
        const val FileName = "notification-snapshot.bin"
        const val ArrivedLabel = "notification-arrived:1"
        const val ArrivedFileName = "notification-arrived.bin"
        const val PhotosDir = "NotificationPhotos"
        const val KeyName = "notifications.snapshot_key"
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    }
}

/**
 * Messages the app already opened from the socket while in the background (2026-11-13), worded as their notification.
 * The server pushes them too, and the extension can't open one twice (libsignal says "duplicate" once the app's
 * ratchet moved past it and a later snapshot saw that): it shows this copy instead. The last [Keep] only, sealed
 * under the snapshot's key. Keep in step with `Arrived` in Snapshot.swift.
 */
@Serializable
data class ArrivedMessages(val v: Int = 1, val messages: List<Arrived> = emptyList()) {
    @Serializable
    data class Arrived(
        val messageId: String,
        val chatId: String?,
        val title: String,
        val body: String,
        val senderId: String? = null,
        val group: Boolean = false,
        val groupTitle: String? = null,
        val money: Boolean = false,
        val transferId: String? = null,
    )

    companion object { const val Keep = 40 }
}

/** Where the snapshot is kept for another process (iOS's App Group). Android words pushes in the app: nothing. */
interface NotificationSnapshotFile {
    val enabled: Boolean

    /** Replaces the file with [sealed], or removes it and the photos when null (signed out). */
    suspend fun write(sealed: ByteArray?)

    /** The [ArrivedMessages], sealed; null removes them. Read back with [readArrived]. */
    suspend fun writeArrived(sealed: ByteArray?) {}

    suspend fun readArrived(): ByteArray? = null

    /**
     * Copies the photos the extension may show into the App Group: [sources] maps a person's or group's id to a
     * profile photo link (read from the image cache, where the app keeps every photo it has shown) or a local file.
     * Returns id → file name inside [NotificationSnapshot.PhotosDir] for each it could copy; removes the rest.
     */
    suspend fun photos(sources: Map<String, String>): Map<String, String> = emptyMap()

    /** Runs [block] with the system's leave to finish after Circaso leaves the screen. */
    suspend fun finishing(block: suspend () -> Unit) = block()
}

object NoNotificationSnapshotFile : NotificationSnapshotFile {
    override val enabled = false
    override suspend fun write(sealed: ByteArray?) {}
}

/**
 * Builds and writes the [NotificationSnapshot] each time Circaso leaves the screen. A failure leaves the last good one
 * (pushes are then worded by name only, never wrongly); a sign-out removes it.
 */
class NotificationSnapshotWriter(
    private val signal: SignalDao,
    private val chats: ChatsDao,
    private val chatsRepo: ChatsRepository,
    private val names: PeopleNames,
    private val wallet: WalletRepository,
    private val sealer: LocalSealer,
    private val crypto: SignalCrypto,
    private val storage: SecureStorage,
    private val file: NotificationSnapshotFile,
    private val ownUserId: suspend () -> String?,
    private val scope: CoroutineScope,
    /**
     * My contacts (saved names applied), so a push about someone I have no chat with (a reaction on my moment, a
     * contact who joined) still shows their name and photo (2026-11-14).
     */
    private val contacts: com.circaso.feature.contacts.domain.ContactsRepository? = null,
) {
    private var running: Job? = null

    fun onBackground() {
        if (!file.enabled) return
        running?.cancel()
        running = scope.launch {
            file.finishing {
                runCatching { write() }
                    .onFailure { com.circaso.core.common.Diagnostics.note("notification snapshot: not written (${it::class.simpleName})") }
            }
        }
    }

    /** Signed out: nothing of this person's chats stays for the extension. */
    suspend fun clear() {
        if (!file.enabled) return
        running?.cancel()
        runCatching { file.write(null) }
        runCatching { file.writeArrived(null) }
        runCatching { storage.remove(NotificationSnapshot.KeyName) }
    }

    private val arrivedLock = kotlinx.coroutines.sync.Mutex()

    /**
     * The app worded [notice] for [messageId], which came over the socket while Circaso was in the background: its
     * push may follow, and the extension shows this copy rather than open the message a second time.
     */
    suspend fun remember(notice: Notice, messageId: String) {
        if (!file.enabled) return
        runCatching {
            arrivedLock.withLock {
                val key = key()
                val label = NotificationSnapshot.ArrivedLabel.encodeToByteArray()
                val current = file.readArrived()?.let { sealed ->
                    runCatching { NotificationSnapshot.json.decodeFromString(ArrivedMessages.serializer(), crypto.aesGcmOpen(key, sealed, label).decodeToString()) }.getOrNull()
                } ?: ArrivedMessages()
                val c = notice.conversation
                val entry = ArrivedMessages.Arrived(
                    messageId = messageId, chatId = notice.chatId, title = notice.content.title, body = notice.content.text,
                    senderId = c?.sender?.userId ?: notice.from?.userId, group = c?.group ?: notice.group, groupTitle = c?.title,
                    money = notice.channel == NoticeChannel.Money, transferId = (notice.destination as? NotificationDestination.Payment)?.transferId,
                )
                val next = ArrivedMessages(messages = (current.messages.filter { it.messageId != messageId } + entry).takeLast(ArrivedMessages.Keep))
                val plain = NotificationSnapshot.json.encodeToString(ArrivedMessages.serializer(), next).encodeToByteArray()
                file.writeArrived(crypto.aesGcmSeal(key, plain, label))
            }
        }.onFailure { com.circaso.core.common.Diagnostics.note("notification copy: not kept (${it::class.simpleName})") }
    }

    private suspend fun write() {
        val snapshot = build() ?: return file.write(null)
        val plain = NotificationSnapshot.json.encodeToString(NotificationSnapshot.serializer(), snapshot).encodeToByteArray()
        file.write(crypto.aesGcmSeal(key(), plain, NotificationSnapshot.Label.encodeToByteArray()))
    }

    /** The snapshot's own key, made once; the extension reads it from the shared Keychain item. */
    private suspend fun key(): ByteArray =
        storage.get(NotificationSnapshot.KeyName)?.let { Base64.decode(it) }?.takeIf { it.size == 32 }
            ?: crypto.randomBytes(32).also { storage.put(NotificationSnapshot.KeyName, Base64.encode(it)) }

    private suspend fun build(): NotificationSnapshot? {
        val identity = signal.identity() ?: return null
        fun b64(bytes: ByteArray) = Base64.encode(bytes)
        val ec = SealedRecordStore.Ec
        val kyber = SealedRecordStore.Kyber
        val saved = names.saved.value
        val rows = chats.chats().filter { !it.left }
        val people = linkedMapOf<String, NotificationSnapshot.Person>()
        val photoSources = mutableMapOf<String, String>()
        val snapshotChats = rows.map { chat ->
            val group = chat.kind == MessagingWire.Group
            val members = chats.members(chat.chatId)
            members.forEach { m ->
                if (m.userId !in people) {
                    people[m.userId] = NotificationSnapshot.Person(m.userId, PeopleNames.resolve(saved, m.userId, m.displayName))
                    m.avatarUrl?.let { photoSources[m.userId] = it }
                }
            }
            // A group as the chat list shows it: its name, or its members' names while this phone can't read it.
            val shown = if (group) withTimeoutOrNull(LookupMillis) { chatsRepo.chat(chat.chatId).first() } else null
            shown?.avatarUrl?.let { photoSources["g:${chat.chatId}"] = it }
            NotificationSnapshot.Chat(chat.chatId, group, if (group) chat.name ?: shown?.title else null, members = members.map { it.userId })
        }
        // People in no chat of mine: my contacts, up to [MaxContacts] (their photos are read from the image cache only).
        val known = contacts?.let { repo -> withTimeoutOrNull(LookupMillis) { repo.contacts.first() } }.orEmpty()
        known.asSequence().filter { it.person.userId !in people }.take(MaxContacts).forEach { c ->
            people[c.person.userId] = NotificationSnapshot.Person(c.person.userId, c.person.displayName)
            c.person.avatarUrl?.let { photoSources[c.person.userId] = it }
        }
        val photos = runCatching { file.photos(photoSources) }.getOrDefault(emptyMap())
        val fiat = withTimeoutOrNull(LookupMillis) { wallet.wallet.first()?.fiat }
        return NotificationSnapshot(
            me = ownUserId(),
            identity = NotificationSnapshot.Identity(b64(sealer.open(identity.keyPair, "identity:1")), identity.registrationId),
            preKeys = signal.preKeys().map { NotificationSnapshot.Record(it.keyId, b64(sealer.open(it.record, "prekey:${it.keyId}"))) },
            signedPreKeys = signal.signedPreKeys(ec).map { NotificationSnapshot.Record(it.keyId, b64(sealer.open(it.record, "signed:$ec:${it.keyId}"))) },
            kyberPreKeys = signal.signedPreKeys(kyber).map { NotificationSnapshot.Record(it.keyId, b64(sealer.open(it.record, "signed:$kyber:${it.keyId}"))) },
            sessions = signal.sessions().map { NotificationSnapshot.Session(it.userId, b64(sealer.open(it.record, "session:${it.userId}"))) },
            senderKeys = signal.senderKeys().map {
                NotificationSnapshot.SenderKey(it.chatId, it.senderId, it.distributionId, b64(sealer.open(it.record, "sender:${it.chatId}:${it.senderId}:${it.distributionId}")))
            },
            chats = snapshotChats.map { c -> if (c.group) c.copy(photo = photos["g:${c.id}"]) else c },
            people = people.values.map { p -> p.copy(photo = photos[p.id]) },
            fiat = fiat?.let { NotificationSnapshot.Fiat(it.currency, it.rate) },
        )
    }

    private companion object {
        const val LookupMillis = 1_000L
        /** Enough for anyone's contacts; keeps the snapshot well inside the extension's memory. */
        const val MaxContacts = 500
    }
}
