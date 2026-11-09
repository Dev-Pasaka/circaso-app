package com.circaso.core.notifications

import com.circaso.core.crypto.ChatPayload
import com.circaso.core.crypto.CiphertextKind
import com.circaso.core.crypto.GroupBytes
import com.circaso.core.crypto.OwnIdentity
import com.circaso.core.crypto.OwnKeys
import com.circaso.core.crypto.PeerAddress
import com.circaso.core.crypto.SignalCrypto
import com.circaso.core.crypto.WireFormat
import com.circaso.core.platform.LocalSealer
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.SealedRecordStore
import com.circaso.feature.messaging.data.local.SignalDao
import com.circaso.feature.messaging.data.remote.MessagingWire
import com.circaso.feature.messaging.data.repository.toMediaDetails
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * What iOS's Notification Service Extension needs to word a chat push the way Android does (sender, photo, group,
 * what was said), without the app's database and without the network (2026-11-09).
 *
 * The extension is another process with a few seconds and about 24 MB: it can't run the app, and it must never
 * change the app's Signal state (a ratchet moved by the extension and not by the app would break the chat). So the
 * app writes this snapshot to the App Group each time it leaves the screen, which is the last moment its Signal
 * state can change before it is suspended. The extension opens a push's ciphertext (which the server now carries,
 * ids and ciphertext only) against a copy of that state in memory and throws the copy away: a peek. The app later
 * pulls the same message from the inbox and opens it for real. A message the snapshot can't open (a new sender's
 * first message with a prekey made after it) is worded by name only.
 *
 * Keys stay sealed as they are in the database (each under its row's label) and the whole snapshot is sealed again
 * under [Label], with the same Keychain key ([LocalSealer]); the extension shares that Keychain item.
 */
@Serializable
data class NotificationSnapshot(
    val v: Int = Version,
    val identity: SealedIdentity? = null,
    val preKeys: Map<Int, String> = emptyMap(),
    val signedPreKeys: Map<Int, String> = emptyMap(),
    val kyberPreKeys: Map<Int, String> = emptyMap(),
    /** userId → sealed session (label `session:<userId>`). */
    val sessions: Map<String, String> = emptyMap(),
    val senderKeys: List<SealedSenderKey> = emptyList(),
    val chats: List<SnapshotChat> = emptyList(),
) {
    @Serializable data class SealedIdentity(val keyPair: String, val publicKey: String, val registrationId: Int)
    @Serializable data class SealedSenderKey(val chatId: String, val senderId: String, val distributionId: String, val record: String)
    @Serializable data class SnapshotChat(val chatId: String, val group: Boolean, val name: String?, val members: List<SnapshotMember>)
    @Serializable data class SnapshotMember(val userId: String, val name: String, val photo: String?)

    companion object {
        const val Version = 1
        const val Label = "notification-snapshot:1"
        /** The file in the App Group container. */
        const val FileName = "notification-snapshot.bin"
        internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        /** Sealed snapshot bytes for the extension; null when nobody is signed in (no identity: nothing to open). */
        suspend fun build(signal: SignalDao, chats: ChatsDao, sealer: LocalSealer): ByteArray? {
            val identity = signal.identity() ?: return null
            fun b64(bytes: ByteArray) = Base64.encode(bytes)
            val snapshot = NotificationSnapshot(
                identity = SealedIdentity(b64(identity.keyPair), b64(identity.publicKey), identity.registrationId),
                preKeys = signal.preKeys().associate { it.keyId to b64(it.record) },
                signedPreKeys = signal.signedPreKeys(SealedRecordStore.Ec).associate { it.keyId to b64(it.record) },
                kyberPreKeys = signal.signedPreKeys(SealedRecordStore.Kyber).associate { it.keyId to b64(it.record) },
                sessions = signal.sessions().associate { it.userId to b64(it.record) },
                senderKeys = signal.senderKeys().map { SealedSenderKey(it.chatId, it.senderId, it.distributionId, b64(it.record)) },
                chats = chats.chats().filter { !it.left }.map { chat ->
                    SnapshotChat(
                        chat.chatId, chat.kind == MessagingWire.Group, chat.name,
                        chats.members(chat.chatId).map { SnapshotMember(it.userId, it.displayName, it.avatarUrl) },
                    )
                },
            )
            return sealer.seal(json.encodeToString(serializer(), snapshot).encodeToByteArray(), Label)
        }
    }
}

/**
 * One chat push, worded for iOS's extension: [content] is the title and body (the same words as Android's), and the
 * rest makes it a communication notification (the sender's name and photo, the group). [conversation] is null with
 * privacy on, and when the push names a chat this phone doesn't know.
 */
data class WordedPush(val content: NotificationContent, val conversation: NoticeConversation?)

/** Opens a chat push against a [NotificationSnapshot] in memory and words it; nothing is written anywhere. */
class NotificationPeek(private val crypto: SignalCrypto, private val sealer: LocalSealer) {

    /**
     * [data] is the push's ids (`chatId`, `messageId`, `senderId`, `ciphertext`, `sentAt`); [sealed] the snapshot file.
     * Null when it isn't a chat message push, so the server's own words stay.
     */
    suspend fun word(data: Map<String, String>, sealed: ByteArray?, private: Boolean): WordedPush? {
        val chatId = data["chatId"]?.takeIf { it.isNotBlank() } ?: return null
        if (data["messageId"].isNullOrBlank() || data["kind"] != null) return null
        if (private) return WordedPush(NotificationText.compose(NotificationEvent.ChatMessage(null, false, null, null), true, null), null)
        val snapshot = sealed?.let { open(it) } ?: return null
        val chat = snapshot.chats.firstOrNull { it.chatId == chatId } ?: return null
        val senderId = data["senderId"]?.takeIf { it.isNotBlank() }
        val sender = senderId?.let { id -> chat.members.firstOrNull { it.userId == id } }
        val senderName = sender?.name ?: if (!chat.group) chat.name else null
        val payload = runCatching { payload(snapshot, chatId, senderId, data["ciphertext"]) }.getOrNull()
        val at = com.circaso.feature.messaging.data.repository.isoToMillis(data["sentAt"]) ?: com.circaso.core.sync.nowMillis()

        if (payload?.type == ChatPayload.Payment) {
            val amount = payload.amountMinor?.let { Money(it, payload.asset ?: "USDC") }
            // No rate in the extension: the coin's own amount ("20.00 USDC"), as Android does without one.
            val event = when {
                !chat.group -> NotificationEvent.MoneyReceived(senderName, amount, payload.text)
                payload.text?.startsWith("Paid from") == true -> NotificationEvent.MoneyReceived(senderName, amount, null, groupName = chat.name ?: "Your group")
                else -> NotificationEvent.GroupContribution(chat.name, senderName, amount)
            }
            return WordedPush(NotificationText.compose(event, false, null), null)
        }
        val (event, line) = when (payload?.type) {
            ChatPayload.Reaction -> {
                val emoji = payload.text.orEmpty().ifEmpty { return null } // a reaction taken back: nothing to show
                NotificationEvent.Reaction(chat.name, chat.group, senderName, emoji, null).let { it to NotificationText.compose(it.copy(chatName = null, group = false), false, null).text }
            }
            else -> NotificationEvent.ChatMessage(chat.name, chat.group, senderName, payload?.let(::preview), momentReply = payload?.moment != null)
                .let { it to NotificationText.chatLine(it) }
        }
        // An unknown sender is never made up: the plain words show (see PushNotices.conversation).
        val conversation = senderName?.let { name ->
            NoticeConversation(
                chatId = chatId, group = chat.group, title = if (chat.group) chat.name ?: "Group" else null,
                senderId = senderId, senderName = name, senderPhoto = sender?.photo, groupPhoto = null, line = line, at = at,
                messageId = data["messageId"],
            )
        }
        return WordedPush(NotificationText.compose(event, false, null), conversation)
    }

    private suspend fun open(sealed: ByteArray): NotificationSnapshot? = runCatching {
        val snapshot = NotificationSnapshot.json.decodeFromString(NotificationSnapshot.serializer(), sealer.open(sealed, NotificationSnapshot.Label).decodeToString())
        snapshot.takeIf { it.v == NotificationSnapshot.Version }
    }.getOrNull()

    /** The message's payload, opened in memory; null when the snapshot can't open it (then: name only). */
    private suspend fun payload(snapshot: NotificationSnapshot, chatId: String, senderId: String?, ciphertext: String?): ChatPayload? {
        senderId ?: return null
        val frame = WireFormat.decode(Base64.decode(ciphertext ?: return null)) ?: return null
        val plaintext = when (frame.type) {
            WireFormat.Type.PreKeyMessage, WireFormat.Type.WhisperMessage -> {
                val kind = if (frame.type == WireFormat.Type.PreKeyMessage) CiphertextKind.PreKey else CiphertextKind.Whisper
                val sealedIdentity = snapshot.identity ?: return null
                val identity = OwnIdentity(sealer.open(Base64.decode(sealedIdentity.keyPair), "identity:1"), Base64.decode(sealedIdentity.publicKey), sealedIdentity.registrationId)
                val session = snapshot.sessions[senderId]?.let { sealer.open(Base64.decode(it), "session:$senderId") }
                crypto.decrypt(identity, PeerAddress(senderId), session, kind, frame.body, if (kind == CiphertextKind.PreKey) ownKeys(snapshot, kind, frame.body) else OwnKeys()).plaintext
            }
            WireFormat.Type.SenderKeyMessage -> {
                val distributionId = crypto.distributionIdOf(GroupBytes.Message, frame.body)
                val key = snapshot.senderKeys.firstOrNull { it.chatId == chatId && it.senderId == senderId && it.distributionId == distributionId } ?: return null
                val record = sealer.open(Base64.decode(key.record), "sender:$chatId:$senderId:$distributionId")
                crypto.groupDecrypt(PeerAddress(senderId), distributionId, record, frame.body).plaintext
            }
            else -> return null
        }
        return ChatPayload.decode(plaintext)
    }

    /** Only the prekeys this message names are opened (the extension's memory is small). */
    private suspend fun ownKeys(snapshot: NotificationSnapshot, kind: CiphertextKind, body: ByteArray): OwnKeys {
        val needs = crypto.inspect(kind, body)
        suspend fun one(map: Map<Int, String>, id: Int?, label: (Int) -> String): Map<Int, ByteArray> =
            id?.let { i -> map[i]?.let { mapOf(i to sealer.open(Base64.decode(it), label(i))) } } ?: emptyMap()
        return OwnKeys(
            preKeys = one(snapshot.preKeys, needs.preKeyId) { "prekey:$it" },
            signedPreKeys = one(snapshot.signedPreKeys, needs.signedPreKeyId) { "signed:${SealedRecordStore.Ec}:$it" },
            kyberPreKeys = one(snapshot.kyberPreKeys, needs.kyberPreKeyId) { "signed:${SealedRecordStore.Kyber}:$it" },
        )
    }

    private fun preview(payload: ChatPayload): String? = when (payload.type) {
        ChatPayload.Text -> payload.text?.trim()?.takeIf { it.isNotEmpty() }
        ChatPayload.Image, ChatPayload.Voice, ChatPayload.Video, ChatPayload.File, ChatPayload.Location, ChatPayload.LiveLocation ->
            payload.toMediaDetails()?.let(MessagePreview::media)
        else -> null
    }
}

/** Where the [NotificationSnapshot] is kept for another process (iOS's extension). Android words pushes in the app. */
interface NotificationSnapshotFile {
    val enabled: Boolean

    /** Replaces the file with [sealed], or removes it when null (signed out). */
    suspend fun write(sealed: ByteArray?)

    /** Runs [block] with the system's leave to finish after Circaso leaves the screen. */
    suspend fun finishing(block: suspend () -> Unit) = block()
}

object NoNotificationSnapshotFile : NotificationSnapshotFile {
    override val enabled = false
    override suspend fun write(sealed: ByteArray?) {}
}

/**
 * Writes the snapshot each time Circaso leaves the screen (see [NotificationSnapshot] for why then). A failure leaves
 * the last good one: pushes are then worded by name only, never wrongly.
 */
class NotificationSnapshotWriter(
    private val signal: SignalDao,
    private val chats: ChatsDao,
    private val sealer: LocalSealer,
    private val file: NotificationSnapshotFile,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private var running: kotlinx.coroutines.Job? = null

    fun onBackground() {
        if (!file.enabled) return
        running?.cancel()
        running = scope.launch {
            file.finishing {
                runCatching { file.write(NotificationSnapshot.build(signal, chats, sealer)) }
                    .onFailure { com.circaso.core.common.Diagnostics.note("notification snapshot: not written (${it::class.simpleName})") }
            }
        }
    }
}
