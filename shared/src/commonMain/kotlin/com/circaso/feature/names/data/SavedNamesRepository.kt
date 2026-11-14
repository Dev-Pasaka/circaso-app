package com.circaso.feature.names.data

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.SignalCrypto
import com.circaso.feature.names.domain.SavedNames
import com.circaso.feature.vault.data.Hkdf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** The sealed payload: every name with when it last changed, cleared ones included (n absent), so phones merge. */
@Serializable internal data class SavedNamesPayload(val v: Int = 1, val names: List<SavedNameItem> = emptyList())

@Serializable internal data class SavedNameItem(val u: String, val n: String? = null, val t: Long)

/**
 * Saved names (2026-11-12). Every change is written to Room first (the screens follow it at once, offline too), then
 * the whole set goes to the server sealed with the key-backup root, which only my passkey opens:
 * AES-256-GCM under HKDF-SHA256(root, "circaso-saved-names", "keybackup/names/v1|<me>"), bound to me by its AAD.
 *
 * [sync] reads the server's copy, merges it name by name (the newer change wins, a cleared name included), and writes
 * back on the version it read; when another phone wrote in between it reads and merges again (up to three times).
 * With backup off ([root] null, or the server says it's off) names stay on this phone and nothing is said. It never
 * throws: a failed sync is tried again on the next start.
 */
@OptIn(ExperimentalEncodingApi::class)
class SavedNamesRepository(
    private val dao: SavedNamesDao,
    private val remote: SavedNamesRemote,
    private val crypto: SignalCrypto,
    private val userId: () -> String?,
    /** The key-backup root when this phone holds it (backup on here), else null. */
    private val root: suspend () -> ByteArray?,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) : SavedNames {
    private val syncLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private val _loaded = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** True once [names] holds what this phone has (read from Room), so a screen never shows "no saved names" first. */
    val loaded: StateFlow<Boolean> = _loaded

    override val names: StateFlow<Map<String, String>> =
        dao.observeAll().map { rows -> rows.mapNotNull { r -> r.name?.let { r.userId to it } }.toMap() }
            .onEach { _loaded.value = true }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun set(userId: String, name: String) {
        val kept = SavedNames.normalise(name)
        if (kept == null) return clear(userId)
        write(userId, kept)
    }

    override suspend fun clear(userId: String) {
        if (dao.get(userId)?.name == null) return // nothing saved: no tombstone to send
        write(userId, null)
    }

    private suspend fun write(userId: String, name: String?) {
        dao.upsert(listOf(SavedNameEntity(userId, name, now())))
        scope.launch { sync() }
    }

    /** Merges the server's copy into this phone and sends the result back. A no-op while backup is off here. */
    suspend fun sync() {
        try { syncLock.withLock { syncOnce() } } catch (e: CancellationException) { throw e } catch (e: Exception) { /* next start */ }
    }

    private suspend fun syncOnce() {
        val person = userId() ?: return
        repeat(MaxAttempts) {
            val key = root() ?: return
            val server = when (val r = remote.get()) {
                is AppResult.Success -> r.value
                is AppResult.Failure -> return
            }
            val theirs = server.ciphertext?.takeIf { server.exists }?.let { open(key, person, it) }.orEmpty()
            val mine = dao.all().associateBy { it.userId }
            // Each name: the newer change wins; a tie keeps this phone's.
            val newer = theirs.filter { t -> mine[t.u].let { it == null || t.t > it.updatedAt } }
            if (newer.isNotEmpty()) dao.upsert(newer.map { SavedNameEntity(it.u, it.n?.let(SavedNames::normalise), it.t) })
            val merged = dao.all().map { SavedNameItem(it.userId, it.name, it.updatedAt) }.sortedBy { it.u }
            if (server.exists && merged == theirs.sortedBy { it.u }) return // the server already has exactly this
            if (!server.exists && merged.isEmpty()) return
            val sealed = Base64.encode(seal(key, person, merged))
            // The server takes at most 512 KiB (identity.saved_names_ciphertext_invalid): tens of thousands of names.
            // Past that the names stay on this phone rather than send a blob that's refused every time.
            if (sealed.length > MaxCiphertext) return
            when (val put = remote.put(sealed, server.version)) {
                is AppResult.Success -> return
                is AppResult.Failure -> if ((put.error as? AppError.Api)?.code != SavedNamesRemote.Conflict) return
            }
        }
    }

    internal fun seal(root: ByteArray, person: String, items: List<SavedNameItem>): ByteArray {
        val plain = json.encodeToString(SavedNamesPayload.serializer(), SavedNamesPayload(1, items)).encodeToByteArray()
        val k = key(root, person)
        return try { crypto.aesGcmSeal(k, plain, aad(person)) } finally { k.fill(0) }
    }

    /** The server's names, or null when they can't be opened (another person's, or not from this backup). */
    internal fun open(root: ByteArray, person: String, ciphertext: String): List<SavedNameItem>? {
        val sealed = runCatching { Base64.decode(ciphertext) }.getOrNull() ?: return null
        val k = key(root, person)
        val plain = try { runCatching { crypto.aesGcmOpen(k, sealed, aad(person)) }.getOrNull() } finally { k.fill(0) } ?: return null
        return runCatching { json.decodeFromString(SavedNamesPayload.serializer(), plain.decodeToString()).names }.getOrNull()
    }

    private fun key(root: ByteArray, person: String) = Hkdf.sha256(root, Salt, aad(person), 32, crypto::sha256)
    private fun aad(person: String) = "keybackup/names/v1|$person".encodeToByteArray()

    companion object {
        private val Salt = "circaso-saved-names".encodeToByteArray()
        private const val MaxAttempts = 3
        private const val MaxCiphertext = 512 * 1024
    }
}
