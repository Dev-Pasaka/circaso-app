package com.circaso.feature.keybackup

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.network.NoBody
import com.circaso.core.platform.AccountPasskey
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
import com.circaso.feature.vault.data.SaveVaultRequest
import com.circaso.feature.vault.data.VaultDto
import com.circaso.feature.vault.data.VaultOutcome
import com.circaso.feature.vault.data.VaultProblem
import com.circaso.feature.vault.data.VaultRemote
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.data.VaultStatusDto
import com.circaso.feature.vault.domain.VaultContents
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One passkey per account (2026-11-22): the PIN Vault and the group key backup never make a second passkey (a password
 * manager would replace the first with it and orphan what it sealed), an account already split across two heals from
 * a phone that holds the root, and a backup whose passkey is gone says so and can be started again on purpose.
 */
@OptIn(ExperimentalEncodingApi::class)
class OnePasskeyTest {
    private val crypto = AndroidSignalCrypto()

    private class BackupServer : KeyBackupRemote {
        var root: BackupRootDto = BackupRootDto(exists = false)
        val keys = linkedMapOf<Pair<String, Int>, String>()
        var down = false
        var removed = 0
        val saves = mutableListOf<SaveRootRequest>()
        override suspend fun root(): AppResult<BackupRootDto> = if (down) AppResult.Failure(AppError.Network) else AppResult.Success(root)
        override suspend fun saveRoot(request: SaveRootRequest): AppResult<BackupRootDto> {
            // KB2: a replace that names a version other than the stored one is refused.
            if (request.expectedVersion != null && request.expectedVersion != root.version) return AppResult.Failure(AppError.Api("key_backup.version_conflict", 409, null, null, emptyMap()))
            saves += request
            root = BackupRootDto(true, request.credentialId, request.salt, request.sealedRoot, request.algorithm, (root.version ?: 0) + 1)
            return AppResult.Success(root)
        }
        override suspend fun putGroupKey(chatId: String, metaVersion: Int, ciphertext: String): AppResult<NoBody> { keys[chatId to metaVersion] = ciphertext; return AppResult.Success(NoBody()) }
        override suspend fun groupKeys(after: String?, limit: Int): AppResult<GroupKeysPage> =
            AppResult.Success(GroupKeysPage(keys.entries.map { (k, v) -> BackedUpGroupKey(k.first, k.second, v) }))
        override suspend fun remove(): AppResult<NoBody> { removed++; root = BackupRootDto(false); keys.clear(); return AppResult.Success(NoBody()) }
    }

    private class VaultServer : VaultRemote {
        var stored: VaultDto? = null
        override suspend fun status() = AppResult.Success(VaultStatusDto(stored != null, stored?.credentialId, stored?.version, stored?.updatedAt))
        override suspend fun open(): AppResult<VaultDto> = stored?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Api("vault.not_found", 404, null, null, emptyMap()))
        override suspend fun save(request: SaveVaultRequest, idempotencyKey: String): AppResult<VaultStatusDto> {
            val version = (stored?.version ?: 0) + 1
            stored = VaultDto(request.credentialId, request.salt, request.ciphertext, request.algorithm, version, "2026-11-22T10:00:00Z")
            return AppResult.Success(VaultStatusDto(true, request.credentialId, version, stored?.updatedAt))
        }
        override suspend fun remove(): AppResult<NoBody> { stored = null; return AppResult.Success(NoBody()) }
    }

    /**
     * A password manager as Google's and Apple's behave: one passkey for circaso.com and this person. Making another
     * replaces it, and the replaced one is gone for good. Each passkey has its own PRF secret.
     */
    private inner class PasswordManager : PasskeyPrf {
        var current: Byte? = null
        var made = 0
        val asked = mutableListOf<Byte>()
        override suspend fun isSupported() = true
        override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray): PrfResult {
            made++
            val id = (10 + made).toByte()
            current = id
            return PrfResult.Success(byteArrayOf(id), secretOf(id, prfSalt))
        }
        override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray): PrfResult {
            asked += credentialId.single()
            return if (credentialId.single() == current) PrfResult.Success(credentialId, secretOf(credentialId.single(), prfSalt)) else PrfResult.NoPasskey
        }
    }

    private fun secretOf(id: Byte, salt: ByteArray = VaultRepository.PrfSalt) = crypto.sha256(salt + id)

    private class Secure : SecureStorage {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private val backupServer = BackupServer()
    private val vaultServer = VaultServer()
    private val manager = PasswordManager()
    private val keyA = crypto.randomBytes(32)
    private val keyB = crypto.randomBytes(32)

    private fun decode(id: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(id)
    private fun encode(id: Byte) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayOf(id))

    /** The rule, wired to the two server records as the app wires it. */
    private fun account() = AccountPasskey(
        manager,
        vaults = { AppResult.Success(listOfNotNull(vaultServer.stored?.credentialId?.let(::decode))) },
        backupRoot = {
            when (val r = backupServer.root()) {
                is AppResult.Success -> AppResult.Success(r.value.credentialId?.takeIf { r.value.exists }?.let(::decode))
                is AppResult.Failure -> r
            }
        },
    )

    /** One phone: its own secure storage and group keys, the account's servers and password manager. */
    private inner class Phone(val groups: MutableMap<Pair<String, Int>, ByteArray> = mutableMapOf(), val secure: Secure = Secure()) {
        val backup = KeyBackupRepository(
            backupServer, manager, crypto, secure, userId = { "user-1" },
            localKeys = { groups.map { (k, v) -> Triple(k.first, k.second, v) } },
            keepKey = { chat, version, key -> groups[chat to version] = key },
            account = account(),
        )
        val vault = VaultRepository(
            vaultServer, manager, crypto, userId = { "user-1" }, newIdempotencyKey = { "k" },
            afterSecret = { id, secret -> backup.onPrfSecret(id, secret) }, account = account(),
        )
        suspend fun saveVault() = vault.save(VaultContents("482915", emptyList()), "@amina", "Amina · PIN Vault")
        suspend fun turnOn() = backup.turnOn("@amina", "Amina · Circaso key")
        suspend fun startNew() = backup.startNew("@amina", "Amina · Circaso key")
    }

    /**
     * The account as the old app could leave it: the backup root sealed under a first passkey (id 5), then a second
     * passkey (id 6) made for the vault, which replaced the first in the password manager.
     */
    private suspend fun diverge(first: Phone) {
        manager.current = 5
        first.backup.onPrfSecret(byteArrayOf(5), secretOf(5))
        assertEquals(encode(5), backupServer.root.credentialId)
        manager.current = 6
        val sealed = com.circaso.feature.vault.data.VaultCrypto(crypto).seal(secretOf(6), "user-1", VaultContents("482915", emptyList()))
        vaultServer.stored = VaultDto(encode(6), Base64.encode(VaultRepository.PrfSalt), Base64.encode(sealed), com.circaso.feature.vault.data.VaultCrypto.Algorithm, 1, null)
    }

    @Test
    fun `saving a vault when a backup root exists uses the root's passkey and never makes one`() = runTest {
        val phone = Phone(mutableMapOf(("g1" to 1) to keyA))
        assertNull(phone.turnOn())
        assertEquals(1, manager.made)
        val rootPasskey = backupServer.root.credentialId

        // The vault was never saved (or was removed): its record names no passkey, the root's does.
        assertIs<VaultOutcome.Done<Unit>>(phone.saveVault())

        assertEquals(1, manager.made, "no second passkey: it would replace the one the backup is sealed under")
        assertEquals(rootPasskey, vaultServer.stored?.credentialId)
        assertEquals(rootPasskey, backupServer.root.credentialId)
        assertNull(Phone().backup.restore(), "the backup still opens")
        assertIs<VaultOutcome.Done<VaultContents>>(phone.vault.open())
    }

    @Test
    fun `turning backup on when a vault exists uses the vault's passkey and never makes one`() = runTest {
        val phone = Phone(mutableMapOf(("g1" to 1) to keyA))
        // The vault first, with the backup's own hand-over switched off: only turnOn decides here.
        val vaultOnly = VaultRepository(vaultServer, manager, crypto, userId = { "user-1" }, newIdempotencyKey = { "k" }, account = account())
        assertIs<VaultOutcome.Done<Unit>>(vaultOnly.save(VaultContents("482915", emptyList()), "@amina", "Amina · PIN Vault"))
        assertEquals(1, manager.made)
        assertFalse(backupServer.root.exists)

        assertNull(phone.turnOn())

        assertEquals(1, manager.made)
        assertEquals(vaultServer.stored?.credentialId, backupServer.root.credentialId)
    }

    @Test
    fun `an account with neither gets exactly one passkey, shared by both`() = runTest {
        val phone = Phone(mutableMapOf(("g1" to 1) to keyA))
        assertIs<VaultOutcome.Done<Unit>>(phone.saveVault())
        assertEquals(1, manager.made)
        assertTrue(backupServer.root.exists, "the vault's answer turns backup on")
        assertEquals(vaultServer.stored?.credentialId, backupServer.root.credentialId)

        assertIs<VaultOutcome.Done<Unit>>(phone.saveVault())
        assertNull(phone.turnOn())
        assertEquals(1, manager.made, "saving again and turning on again reuse it")
    }

    @Test
    fun `no passkey is made while a record can't be read`() = runTest {
        val phone = Phone()
        assertNull(phone.turnOn())
        backupServer.down = true

        val saved = phone.saveVault()

        assertIs<VaultProblem.Server>((saved as VaultOutcome.Problem).problem)
        assertEquals(1, manager.made, "not knowing whether the backup names a passkey is not 'it names none'")
        assertNull(vaultServer.stored)
    }

    @Test
    fun `a split account heals when the phone that holds the root hears the passkey in use`() = runTest {
        val old = Phone(mutableMapOf(("g1" to 1) to keyA, ("g2" to 3) to keyB))
        diverge(old)
        assertEquals(KeyBackupProblem.PasskeyMissing, Phone().backup.restore(), "before: a new phone can't restore")

        // Opening the vault on the old phone hands its passkey's answer to the backup.
        assertIs<VaultOutcome.Done<VaultContents>>(old.vault.open())

        assertEquals(encode(6), backupServer.root.credentialId, "the root is now sealed under the passkey in use")
        assertEquals(2, backupServer.root.version)
        assertEquals(1, backupServer.saves.last().expectedVersion, "a replace names the version it read")
        assertEquals(0, manager.made)
        assertEquals(0, backupServer.removed, "nothing was deleted: the same root, sealed again")

        val fresh = Phone()
        assertNull(fresh.backup.restore())
        assertContentEquals(keyA, fresh.groups["g1" to 1])
        assertContentEquals(keyB, fresh.groups["g2" to 3])
    }

    @Test
    fun `the phone that holds the root is told it needs repair, and Repair heals it`() = runTest {
        val old = Phone(mutableMapOf(("g1" to 1) to keyA))
        diverge(old)

        old.backup.refresh()
        assertTrue(old.backup.status.value.needsRepair)

        assertNull(old.turnOn())
        assertEquals(encode(6), backupServer.root.credentialId)
        assertEquals(listOf<Byte>(6), manager.asked, "one answer, from the passkey in use; the lost one is never asked")
        assertFalse(old.backup.status.value.needsRepair)
        old.backup.refresh()
        assertFalse(old.backup.status.value.needsRepair)
        assertNull(Phone().backup.restore())
    }

    @Test
    fun `a split account is left alone by a phone without the root`() = runTest {
        diverge(Phone(mutableMapOf(("g1" to 1) to keyA)))
        val before = backupServer.root
        val fresh = Phone()

        assertIs<VaultOutcome.Done<VaultContents>>(fresh.vault.open())
        assertIs<VaultOutcome.Done<Unit>>(fresh.saveVault())

        assertEquals(before, backupServer.root, "no root on this phone: nothing to seal, nothing changed")
        assertFalse(KeyBackupRepository.LocalRoot in fresh.secure.map)
        assertEquals(0, manager.made)
        assertFalse(fresh.backup.status.value.needsRepair)
    }

    @Test
    fun `a root this phone kept from an older backup is never sealed over the server's`() = runTest {
        // This phone holds a root, then backup is turned off and on again elsewhere (another root) without it noticing.
        val stale = Phone(mutableMapOf(("g1" to 1) to keyA))
        manager.current = 5
        stale.backup.onPrfSecret(byteArrayOf(5), secretOf(5))
        val other = Phone(mutableMapOf(("g2" to 1) to keyB))
        other.backup.turnOff()
        other.backup.onPrfSecret(byteArrayOf(5), secretOf(5))
        val before = backupServer.root
        manager.current = 6

        stale.backup.onPrfSecret(byteArrayOf(6), secretOf(6))

        assertEquals(before, backupServer.root, "none of the server's keys open with this phone's root: it isn't the server's")
    }

    @Test
    fun `a version another phone saved meanwhile is not overwritten`() = runTest {
        val old = Phone(mutableMapOf(("g1" to 1) to keyA))
        diverge(old)
        // This phone reads version 1; by the time its replace arrives another phone has saved version 4.
        val moved = backupServer.root.copy(version = 4)
        val readsOld = object : KeyBackupRemote by backupServer {
            override suspend fun root(): AppResult<BackupRootDto> = AppResult.Success(moved.copy(version = 1))
        }
        backupServer.root = moved
        val repo = KeyBackupRepository(readsOld, manager, crypto, old.secure, userId = { "user-1" }, localKeys = { emptyList() }, keepKey = { _, _, _ -> })

        repo.onPrfSecret(byteArrayOf(6), secretOf(6))

        assertEquals(moved, backupServer.root)
    }

    @Test
    fun `restoring without the passkey says so and changes nothing`() = runTest {
        diverge(Phone(mutableMapOf(("g1" to 1) to keyA)))
        val before = backupServer.root
        val fresh = Phone()

        assertEquals(KeyBackupProblem.PasskeyMissing, fresh.backup.restore())

        assertEquals(before, backupServer.root)
        assertTrue(fresh.groups.isEmpty())
        assertEquals(0, manager.made)
    }

    @Test
    fun `starting a new backup replaces the old one under the vault's passkey, on purpose`() = runTest {
        diverge(Phone(mutableMapOf(("g1" to 1) to keyA)))
        val fresh = Phone(mutableMapOf(("g2" to 1) to keyB))

        assertNull(fresh.startNew())

        assertEquals(1, backupServer.removed)
        assertEquals(encode(6), backupServer.root.credentialId)
        assertEquals(0, manager.made, "the vault names a passkey: it is used, none is made")
        assertEquals(setOf("g2" to 1), backupServer.keys.keys, "this phone's keys are backed up again; the old backup's are gone")
        val next = Phone()
        assertNull(next.backup.restore())
        assertContentEquals(keyB, next.groups["g2" to 1])
    }

    @Test
    fun `starting a new backup deletes nothing until a passkey has answered`() = runTest {
        diverge(Phone(mutableMapOf(("g1" to 1) to keyA)))
        val before = backupServer.root
        manager.current = null // the vault's passkey isn't on this phone either

        assertEquals(KeyBackupProblem.PasskeyMissing, Phone().startNew())

        assertEquals(0, backupServer.removed)
        assertEquals(before, backupServer.root)
        assertEquals(0, manager.made, "a vault names a passkey, so none is made over it")
    }

    @Test
    fun `starting a new backup keeps the old one when its passkey answers after all`() = runTest {
        // Not split: vault and root under one passkey, which this phone has (the person had only backed out).
        val old = Phone(mutableMapOf(("g1" to 1) to keyA))
        assertIs<VaultOutcome.Done<Unit>>(old.saveVault())
        val before = backupServer.root
        val fresh = Phone()

        assertNull(fresh.startNew())

        assertEquals(0, backupServer.removed)
        assertEquals(before, backupServer.root)
        assertEquals(1, manager.made)
        assertContentEquals(keyA, fresh.groups["g1" to 1], "it was a restore")
    }

    @Test
    fun `starting a new backup with no vault makes the one new passkey`() = runTest {
        // Backup only, its passkey gone (another Google account's): the person chooses to start again.
        manager.current = 5
        Phone(mutableMapOf(("g1" to 1) to keyA)).backup.onPrfSecret(byteArrayOf(5), secretOf(5))
        manager.current = null
        val fresh = Phone(mutableMapOf(("g2" to 1) to keyB))
        assertEquals(KeyBackupProblem.PasskeyMissing, fresh.backup.restore())

        assertNull(fresh.startNew())

        assertEquals(1, manager.made)
        assertEquals(1, backupServer.removed)
        assertEquals(encode(11), backupServer.root.credentialId)
        // And the vault, saved later, shares it.
        assertIs<VaultOutcome.Done<Unit>>(fresh.saveVault())
        assertEquals(1, manager.made)
        assertEquals(encode(11), vaultServer.stored?.credentialId)
    }
}
