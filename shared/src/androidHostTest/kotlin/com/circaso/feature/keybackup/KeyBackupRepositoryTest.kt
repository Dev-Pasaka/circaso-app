package com.circaso.feature.keybackup

import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.network.NoBody
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.core.platform.SecureStorage
import com.circaso.feature.keybackup.data.BackedUpGroupKey
import com.circaso.feature.keybackup.data.BackupRootDto
import com.circaso.feature.keybackup.data.GroupKeysPage
import com.circaso.feature.keybackup.data.KeyBackupProblem
import com.circaso.feature.keybackup.data.KeyBackupRemote
import com.circaso.feature.keybackup.data.KeyBackupRepository
import com.circaso.feature.keybackup.data.SaveRootRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Group key backup: keys sealed under a root that only the person's passkey opens, and back on a new phone. */
class KeyBackupRepositoryTest {
    private val crypto = AndroidSignalCrypto()

    /** The server: it only ever holds what it's given. */
    private class Server : KeyBackupRemote {
        var root: BackupRootDto = BackupRootDto(exists = false)
        val keys = linkedMapOf<Pair<String, Int>, String>()
        override suspend fun root() = AppResult.Success(root)
        override suspend fun saveRoot(request: SaveRootRequest): AppResult<BackupRootDto> {
            root = BackupRootDto(true, request.credentialId, request.salt, request.sealedRoot, request.algorithm, (root.version ?: 0) + 1)
            return AppResult.Success(root)
        }
        override suspend fun putGroupKey(chatId: String, metaVersion: Int, ciphertext: String): AppResult<NoBody> { keys[chatId to metaVersion] = ciphertext; return AppResult.Success(NoBody()) }
        override suspend fun groupKeys(after: String?, limit: Int): AppResult<GroupKeysPage> {
            val all = keys.entries.map { (k, v) -> BackedUpGroupKey(k.first, k.second, v) }
            val start = after?.toInt() ?: 0
            val page = all.drop(start).take(limit)
            return AppResult.Success(GroupKeysPage(page, next = (start + page.size).takeIf { it < all.size }?.toString()))
        }
        override suspend fun remove(): AppResult<NoBody> { root = BackupRootDto(false); keys.clear(); return AppResult.Success(NoBody()) }
    }

    /** A passkey whose PRF secret depends on the salt and on which passkey it is ([seed]). */
    private inner class Passkey(private val seed: Byte = 7) : PasskeyPrf {
        override suspend fun isSupported() = true
        override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray) = PrfResult.Success(byteArrayOf(1, 2, 3), crypto.sha256(prfSalt + seed))
        override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray) = PrfResult.Success(credentialId, crypto.sha256(prfSalt + seed))
    }

    private class Secure : SecureStorage {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private val server = Server()
    private val keyA = crypto.randomBytes(32)
    private val keyB = crypto.randomBytes(32)

    private fun phone(local: MutableMap<Pair<String, Int>, ByteArray>, passkey: PasskeyPrf = Passkey(), secure: Secure = Secure()) =
        KeyBackupRepository(
            server, passkey, crypto, secure, userId = { "user-1" },
            localKeys = { local.map { (k, v) -> Triple(k.first, k.second, v) } },
            keepKey = { chat, version, key -> local[chat to version] = key },
        )

    @Test
    fun `the vault's passkey turns backup on and sends every group key, sealed`() = runTest {
        val oldPhone = mutableMapOf(("g1" to 1) to keyA, ("g2" to 3) to keyB)
        val repo = phone(oldPhone)
        repo.onPrfSecret(byteArrayOf(1, 2, 3), crypto.sha256(com.circaso.feature.vault.data.VaultRepository.PrfSalt + 7))

        assertTrue(server.root.exists)
        assertEquals(setOf("g1" to 1, "g2" to 3), server.keys.keys)
        assertFalse(server.keys.values.any { it.contains(kotlin.io.encoding.Base64.encode(keyA)) }, "never the key itself")

        // A key learned later goes up at once.
        val keyC = crypto.randomBytes(32)
        repo.onKeyKept("g1", 2, keyC)
        assertTrue(("g1" to 2) in server.keys)
    }

    @Test
    fun `after signing in on a new phone one passkey answer brings every group key back`() = runTest {
        val oldPhone = mutableMapOf(("g1" to 1) to keyA, ("g2" to 3) to keyB)
        phone(oldPhone).onPrfSecret(byteArrayOf(1, 2, 3), crypto.sha256(com.circaso.feature.vault.data.VaultRepository.PrfSalt + 7))

        val newPhone = mutableMapOf<Pair<String, Int>, ByteArray>()
        val repo = phone(newPhone)
        repo.refresh()
        assertTrue(repo.status.value.onServer == true && !repo.status.value.onPhone, "offer the restore")

        assertNull(repo.restore())
        assertContentEquals(keyA, newPhone["g1" to 1])
        assertContentEquals(keyB, newPhone["g2" to 3])
        assertTrue(repo.status.value.onPhone)
    }

    @Test
    fun `another passkey can't open the backup`() = runTest {
        phone(mutableMapOf(("g1" to 1) to keyA)).onPrfSecret(byteArrayOf(1, 2, 3), crypto.sha256(com.circaso.feature.vault.data.VaultRepository.PrfSalt + 7))
        val stranger = mutableMapOf<Pair<String, Int>, ByteArray>()
        assertEquals(KeyBackupProblem.WrongPasskey, phone(stranger, passkey = Passkey(seed = 9)).restore())
        assertTrue(stranger.isEmpty())
    }

    @Test
    fun `turning it off clears the server, and a phone that sees it off stops backing up`() = runTest {
        val secure = Secure()
        val local = mutableMapOf(("g1" to 1) to keyA)
        val repo = phone(local, secure = secure)
        repo.onPrfSecret(byteArrayOf(1, 2, 3), crypto.sha256(com.circaso.feature.vault.data.VaultRepository.PrfSalt + 7))
        assertTrue(KeyBackupRepository.LocalRoot in secure.map)

        repo.turnOff()
        assertFalse(server.root.exists)
        assertTrue(server.keys.isEmpty())
        assertFalse(KeyBackupRepository.LocalRoot in secure.map)
        repo.onKeyKept("g1", 2, keyB)
        assertTrue(server.keys.isEmpty(), "off: nothing goes up")
    }
}
