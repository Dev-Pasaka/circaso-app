package com.circaso.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What goes in the server's `ciphertext` field, before base64: one version byte, one type byte, then libsignal's bytes.
 * The server never reads it; both phones must agree on it, so this is the one place it is defined.
 */
object WireFormat {
    const val Version: Byte = 1

    enum class Type(val code: Byte) {
        PreKeyMessage(1), WhisperMessage(2), SenderKeyMessage(3), SenderKeyDistribution(4),

        /**
         * A retry receipt (Signal's "decryption error" message): "I couldn't open your message X, send it again". Not
         * encrypted (the session it would need is the one that broke); the body is [RetryRequest] JSON and says only
         * which message. Older apps don't know the type and drop it quietly (it never travels as a `MESSAGE`).
         */
        RetryRequest(5);

        companion object {
            fun of(code: Byte) = entries.firstOrNull { it.code == code }
        }
    }

    class Frame(val type: Type, val body: ByteArray)

    fun encode(type: Type, body: ByteArray): ByteArray = byteArrayOf(Version, type.code) + body

    /** `null` for bytes this version of the app does not understand (a newer wire version, or an unknown type). */
    fun decode(bytes: ByteArray): Frame? {
        if (bytes.size < 2 || bytes[0] != Version) return null
        val type = Type.of(bytes[1]) ?: return null
        return Frame(type, bytes.copyOfRange(2, bytes.size))
    }

    /** What a [Type.RetryRequest] carries. */
    @Serializable
    data class RetryRequest(val v: Int = 1, val messageId: String)

    private val retryJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun retryRequest(messageId: String): ByteArray =
        encode(Type.RetryRequest, retryJson.encodeToString(RetryRequest.serializer(), RetryRequest(messageId = messageId)).encodeToByteArray())

    /** The message id a retry request names, or null for bytes that aren't one. */
    fun readRetryRequest(body: ByteArray): String? =
        runCatching { retryJson.decodeFromString(RetryRequest.serializer(), body.decodeToString()).messageId }.getOrNull()
            ?.takeIf { it.isNotBlank() && it.length <= 64 }

    fun typeOf(kind: CiphertextKind) = when (kind) {
        CiphertextKind.PreKey -> Type.PreKeyMessage
        CiphertextKind.Whisper -> Type.WhisperMessage
    }
}

/**
 * The plaintext inside every message. Versioned so older phones can skip what they do not know; a `type` this app
 * has no case for is shown as "a message this version can't show". Encoded as JSON, then padded (see [Padding]).
 */
@Serializable
data class MomentQuote(
    val momentId: String,
    /** `TEXT`, `IMAGE`, `VIDEO` or `VOICE`. */
    val kind: String,
    /** The moment's text or caption, cut to 140 characters. */
    val text: String? = null,
    /** The moment's tiny placeholder JPEG (base64), so the quote shows a picture without the media. */
    val thumb: String? = null,
    /** When the moment ends (epoch ms); after it, the quote says it is no longer available and drops the picture. */
    val expiresAt: Long? = null,
)

@Serializable
data class ChatPayload(
    val v: Int = 1,
    val type: String,
    val text: String? = null,
    val replyTo: String? = null,
    /** A reply to a moment (design V8): the moment it answers, quoted so the chat keeps it after the moment expires. */
    val moment: MomentQuote? = null,
    /** Base64 sender-key distribution (type [SenderKey]) handed over a 1:1 session. */
    val senderKey: String? = null,
    /** Base64 group metadata key (type [MetaKey]) and the `metaVersion` it opens, handed over a 1:1 session. */
    val metaKey: String? = null,
    val metaVersion: Int? = null,
    /** The sender's clock when the message was written; the server's `sentAt` is what the thread orders by. */
    val clientSentAt: String? = null,
    /** A payment card (type [Payment]): the wallet transfer it shows; [text] carries the note. The note never reaches the wallet API. */
    val transferId: String? = null,
    val amountMinor: String? = null,
    val asset: String? = null,
    val blockchain: String? = null,
    /** A photo or voice note (types [Image], [Voice]): the encrypted attachment, its AES-256-GCM key and the blob's SHA-256. */
    val attachmentId: String? = null,
    val mediaKey: String? = null,
    val digest: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    /** A shared location (type [Location]). */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val label: String? = null,
    /**
     * Live location (types [LiveLocation], [LiveUpdate], [LiveStop], WhatsApp-style): which share this is, when it
     * ends and when this point was taken, both epoch millis. A share starts as one message; its updates and its stop
     * move that message's pin and never show as messages of their own.
     */
    val liveId: String? = null,
    val liveUntil: Long? = null,
    val liveAt: Long? = null,
    /** A video's first frame (type [Video]): a small base64 JPEG, so the bubble shows before the file loads. */
    val thumb: String? = null,
    /** A moment's body sealed again for one person (type [MomentBody]): which moment; [text] is the body JSON. */
    val momentId: String? = null,
    /**
     * This is my message [resendOf] sent again because the other phone asked (a retry receipt): it replaces that
     * message's "can't show" row, keeping its id and its place in the thread. Only ever sent 1:1, to the phone that asked.
     */
    val resendOf: String? = null,
) {
    companion object {
        const val Text = "text"
        const val Payment = "payment"
        const val Image = "image"
        const val Voice = "voice"
        const val Location = "location"
        const val Video = "video"
        const val LiveLocation = "liveLocation"
        const val LiveUpdate = "liveUpdate"
        const val LiveStop = "liveStop"
        const val SenderKey = "senderKey"
        const val MetaKey = "metaKey"
        /**
         * "This phone has no key for this group's name": sent 1:1 (a `SENDER_KEY` message) to the other members, any of
         * whom answers with a [MetaKey] payload. [metaVersion] is the version the asking phone sees. Never shown; an
         * older phone that doesn't know it drops it quietly.
         */
        const val MetaKeyRequest = "metaKeyRequest"
        /** A moment's body for a new phone of someone in my audience (moments-api.md, key request). Never shown in a chat. */
        const val MomentBody = "momentBody"
        const val Control = "control"
        /** A reaction (WhatsApp-style): [replyTo] is the message reacted to, [text] the emoji, empty to take it back. */
        const val Reaction = "reaction"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun encode(payload: ChatPayload): ByteArray = Padding.pad(json.encodeToString(serializer(), payload).encodeToByteArray())

        /** `null` when the bytes are not a payload at all (not ours, or corrupted). */
        fun decode(bytes: ByteArray): ChatPayload? = runCatching { json.decodeFromString(serializer(), Padding.unpad(bytes).decodeToString()) }.getOrNull()
    }
}

/**
 * Pads plaintext to the next 160-byte bucket (Signal's own padding) so message length tells less. The last byte holds
 * how many bytes were added (1..160), so unpadding never guesses.
 */
object Padding {
    const val Bucket = 160

    fun pad(plain: ByteArray): ByteArray {
        val padded = ((plain.size / Bucket) + 1) * Bucket
        val added = padded - plain.size // 1..160, always room for the count byte
        return ByteArray(padded).also { out ->
            plain.copyInto(out)
            out[padded - 1] = added.toByte()
        }
    }

    fun unpad(padded: ByteArray): ByteArray {
        require(padded.isNotEmpty() && padded.size % Bucket == 0) { "not padded" }
        val added = padded.last().toInt() and 0xFF
        require(added in 1..Bucket && added <= padded.size) { "bad padding" }
        return padded.copyOfRange(0, padded.size - added)
    }
}
