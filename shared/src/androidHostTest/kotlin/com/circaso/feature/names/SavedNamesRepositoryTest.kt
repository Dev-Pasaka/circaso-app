package com.circaso.feature.names

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.feature.names.data.PutSavedNamesResponse
import com.circaso.feature.names.data.SavedNameEntity
import com.circaso.feature.names.data.SavedNameItem
import com.circaso.feature.names.data.SavedNamesDao
import com.circaso.feature.names.data.SavedNamesDto
import com.circaso.feature.names.data.SavedNamesRemote
import com.circaso.feature.names.data.SavedNamesRepository
import com.circaso.feature.names.domain.PeopleNames
import com.circaso.feature.names.domain.SavedNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Saved names: on the phone first, then sealed with the key-backup root; merged name by name across phones. */
@OptIn(ExperimentalEncodingApi::class)
class SavedNamesRepositoryTest {
    private val crypto = AndroidSignalCrypto()
    private val root = crypto.randomBytes(32)

    private class Dao : SavedNamesDao {
        val rows = MutableStateFlow<Map<String, SavedNameEntity>>(emptyMap())
        override fun observeAll(): Flow<List<SavedNameEntity>> = rows.map { it.values.toList() }
        override suspend fun all() = rows.value.values.toList()
        override suspend fun get(userId: String) = rows.value[userId]
        override suspend fun upsert(rows: List<SavedNameEntity>) { this.rows.value = this.rows.value + rows.associateBy { it.userId } }
        override suspend fun clear() { rows.value = emptyMap() }
    }

    /** The server: one sealed blob and its version; [conflicts] PUTs are refused as stale first; [off] = backup off. */
    private class Server : SavedNamesRemote {
        var blob: String? = null
        var version = 0L
        var conflicts = 0
        var off = false
        var puts = 0
        override suspend fun get() = AppResult.Success(SavedNamesDto(exists = blob != null, version = version, ciphertext = blob))
        override suspend fun put(ciphertext: String, baseVersion: Long): AppResult<PutSavedNamesResponse> {
            puts++
            if (off) return AppResult.Failure(AppError.Api(SavedNamesRemote.BackupOff, 409, null, null, emptyMap()))
            if (conflicts > 0 || baseVersion != version) {
                if (conflicts > 0) { conflicts--; version++ } // another phone wrote in between
                return AppResult.Failure(AppError.Api(SavedNamesRemote.Conflict, 409, null, null, emptyMap()))
            }
            blob = ciphertext; version++
            return AppResult.Success(PutSavedNamesResponse(version))
        }
    }

    private var clock = 1_000L
    private val server = Server()

    private fun TestScope.phone(dao: Dao = Dao(), rootOnPhone: ByteArray? = root, person: String = "me") =
        SavedNamesRepository(dao, server, crypto, userId = { person }, root = { rootOnPhone }, scope = backgroundScope, now = { clock++ })

    private fun SavedNamesRepository.serverNames(person: String = "me") = open(root, person, server.blob!!)!!.associateBy { it.u }

    @Test
    fun `a saved name shows at once, trimmed and at most 40 characters, and is backed up sealed`() = runTest {
        val repo = phone()
        repo.set("amina", "   Shiru   (cousin) ")
        runCurrent()
        assertEquals(mapOf("amina" to "Shiru (cousin)"), repo.names.value)
        assertEquals("Shiru (cousin)", repo.serverNames()["amina"]?.n)
        assertFalse(Base64.decode(server.blob!!).decodeToString().contains("Shiru"), "never the name itself")

        repo.set("brian", "x".repeat(60))
        runCurrent()
        assertEquals(SavedNames.MaxLength, repo.names.value["brian"]?.length)
    }

    @Test
    fun `clearing or a blank name leaves a tombstone so it wins over an older name elsewhere`() = runTest {
        val dao = Dao()
        val repo = phone(dao)
        repo.set("amina", "Shiru")
        runCurrent()
        repo.set("amina", "   ")
        runCurrent()
        assertTrue(repo.names.value.isEmpty())
        assertNull(dao.rows.value["amina"]?.name)
        val item = repo.serverNames()["amina"]!!
        assertNull(item.n)

        // Clearing a name that was never saved writes nothing.
        val puts = server.puts
        repo.clear("nobody")
        runCurrent()
        assertEquals(puts, server.puts)
        assertFalse("nobody" in dao.rows.value)
    }

    @Test
    fun `merging keeps the newer change for each name, tombstones included`() = runTest {
        // Another phone: Amina named at t=5000, Brian cleared at t=6000, Chao named at t=10.
        val other = phone(Dao())
        server.blob = Base64.encode(other.seal(root, "me", listOf(SavedNameItem("amina", "Mama A", 5_000), SavedNameItem("brian", null, 6_000), SavedNameItem("chao", "Chao old", 10))))
        server.version = 4

        val dao = Dao()
        dao.upsert(listOf(SavedNameEntity("amina", "Amina mine", 4_000), SavedNameEntity("brian", "Bri", 2_000), SavedNameEntity("chao", "Chao new", 20)))
        val repo = phone(dao)
        repo.sync()
        runCurrent()

        assertEquals(mapOf("amina" to "Mama A", "chao" to "Chao new"), repo.names.value)
        val up = repo.serverNames()
        assertEquals("Mama A", up["amina"]?.n)
        assertNull(up["brian"]?.n)
        assertEquals("Chao new", up["chao"]?.n)
        assertEquals(5L, server.version)
    }

    @Test
    fun `a stale version is read, merged and written again`() = runTest {
        val repo = phone()
        server.conflicts = 2
        repo.set("amina", "Shiru")
        runCurrent()
        assertEquals("Shiru", repo.serverNames()["amina"]?.n)
        assertEquals(3, server.puts)
    }

    @Test
    fun `it gives up after three conflicts and keeps the name on the phone`() = runTest {
        val repo = phone()
        server.conflicts = 10
        repo.set("amina", "Shiru")
        runCurrent()
        assertEquals(3, server.puts)
        assertNull(server.blob)
        assertEquals("Shiru", repo.names.value["amina"])
    }

    @Test
    fun `with backup off names stay on the phone and nothing is sent or thrown`() = runTest {
        val repo = phone(rootOnPhone = null)
        repo.set("amina", "Shiru")
        runCurrent()
        assertEquals("Shiru", repo.names.value["amina"])
        assertEquals(0, server.puts)

        // The server says backup is off: still no error.
        server.off = true
        val withRoot = phone()
        withRoot.set("brian", "Bri")
        runCurrent()
        assertEquals("Bri", withRoot.names.value["brian"])
        assertNull(server.blob)
    }

    @Test
    fun `sealed names open for the same person and root only`() = runTest {
        val repo = phone()
        val items = listOf(SavedNameItem("amina", "Shiru", 1), SavedNameItem("brian", null, 2))
        val sealed = Base64.encode(repo.seal(root, "me", items))
        assertEquals(items, repo.open(root, "me", sealed))
        assertNull(repo.open(root, "someone-else", sealed), "bound to the person")
        assertNull(repo.open(crypto.randomBytes(32), "me", sealed), "and to the root")
    }

    @Test
    fun `the resolver shows the saved name over the profile name`() {
        val saved = MutableStateFlow(mapOf("amina" to "Shiru"))
        val names = PeopleNames(saved)
        assertEquals("Shiru", names.of("amina", "Amina Wanjiru"))
        assertEquals("Brian Otieno", names.of("brian", "Brian Otieno"))
        assertEquals("Brian Otieno", names.of(null, "Brian Otieno"))
        assertEquals("Shiru", names.orNull("amina", null))
        assertNull(names.orNull("brian", null))
        saved.value = emptyMap()
        assertEquals("Amina Wanjiru", names.of("amina", "Amina Wanjiru"), "follows a cleared name at once")
        assertEquals("Ann", PeopleNames.resolve(mapOf("a" to " "), "a", "Ann"), "a blank saved name never shows")
        assertNull(SavedNames.normalise("   "))
    }

    @Test
    fun `a notice about someone reads with the name I saved for them`() {
        val text = com.circaso.feature.messaging.data.repository.systemText("Amina Wanjiru's security code changed", "Amina Wanjiru", "Shiru")
        assertEquals("Shiru's security code changed", text)
        assertEquals("Amina Wanjiru joined", com.circaso.feature.messaging.data.repository.systemText("Amina Wanjiru joined", "Amina Wanjiru", null))
        assertEquals("You joined", com.circaso.feature.messaging.data.repository.systemText("You joined", "Amina Wanjiru", "Shiru"))
    }
}
