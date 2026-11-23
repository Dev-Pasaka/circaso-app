package com.circaso.feature.keybackup

import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.NoBody
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.core.platform.SecureStorage
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.keybackup.data.BackupRootDto
import com.circaso.feature.keybackup.data.GroupKeysPage
import com.circaso.feature.keybackup.data.KeyBackupRemote
import com.circaso.feature.keybackup.data.KeyBackupRepository
import com.circaso.feature.keybackup.data.SaveRootRequest
import com.circaso.feature.keybackup.presentation.KeyBackupCopy
import com.circaso.feature.keybackup.presentation.KeyBackupViewModel
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.GroupCreated
import com.circaso.feature.messaging.domain.GroupDraft
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import com.circaso.feature.vault.data.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The group names backup's card and row when the passkey that locked the backup can't be reached (2026-11-22). */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyBackupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val crypto = AndroidSignalCrypto()

    private class Server : KeyBackupRemote {
        var root: BackupRootDto = BackupRootDto(exists = false)
        var removed = 0
        override suspend fun root() = AppResult.Success(root)
        override suspend fun saveRoot(request: SaveRootRequest): AppResult<BackupRootDto> {
            root = BackupRootDto(true, request.credentialId, request.salt, request.sealedRoot, request.algorithm, (root.version ?: 0) + 1)
            return AppResult.Success(root)
        }
        override suspend fun putGroupKey(chatId: String, metaVersion: Int, ciphertext: String): AppResult<NoBody> = AppResult.Success(NoBody())
        override suspend fun groupKeys(after: String?, limit: Int) = AppResult.Success(GroupKeysPage())
        override suspend fun remove(): AppResult<NoBody> { removed++; root = BackupRootDto(false); return AppResult.Success(NoBody()) }
    }

    /** The password manager's answer to "use this passkey" is set per test; a new passkey always works. */
    private inner class Passkey : PasskeyPrf {
        var use: PrfResult? = null
        override suspend fun isSupported() = true
        override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray) = PrfResult.Success(byteArrayOf(9), crypto.sha256(prfSalt + 9))
        override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray) = use ?: PrfResult.Success(credentialId, crypto.sha256(prfSalt + credentialId))
    }

    private class Secure : SecureStorage {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private class Chats : ChatsRepository {
        override val chats: Flow<List<ChatSummary>> = MutableStateFlow(emptyList())
        override fun chat(chatId: String): Flow<Chat?> = emptyFlow()
        override suspend fun refresh() = AppResult.Success(Unit)
        override suspend fun refreshChat(chatId: String): AppResult<Chat> = error("unused")
        override suspend fun openDirect(userId: String): AppResult<Chat> = error("unused")
        override suspend fun createGroup(draft: GroupDraft, idempotencyKey: String): AppResult<GroupCreated> = error("unused")
        override suspend fun rename(chatId: String, name: String): AppResult<Chat> = error("unused")
        override suspend fun setMembersCanAdd(chatId: String, allowed: Boolean): AppResult<Chat> = error("unused")
        override suspend fun addMembers(chatId: String, userIds: List<String>): AppResult<GroupCreated> = error("unused")
        override suspend fun removeMember(chatId: String, userId: String): AppResult<Unit> = error("unused")
        override suspend fun leave(chatId: String): AppResult<Unit> = error("unused")
        override suspend fun setRole(chatId: String, userId: String, role: MemberRole): AppResult<Chat> = error("unused")
        override suspend fun securityCode(userId: String): SecurityCode? = null
    }

    private class Profiles : ProfileRepository {
        override val profile: Flow<Profile?> = MutableStateFlow(null)
        override suspend fun refresh() = AppResult.Success(Unit)
        override suspend fun update(change: ProfileChange) = true
        override suspend fun checkHandle(handle: String): AppResult<HandleAvailability> = error("unused")
        override suspend fun claimHandle(handle: String) = AppResult.Success(Unit)
        override suspend fun uploadAvatar(image: AvatarImage) = AppResult.Success(Unit)
        override suspend fun removeAvatar() = AppResult.Success(Unit)
        override suspend fun findByHandle(handle: String): AppResult<PublicProfile> = error("unused")
        override suspend fun publicProfile(userId: String): AppResult<PublicProfile> = error("unused")
    }

    private object Online : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

    private val server = Server()
    private val passkey = Passkey()

    private fun repository() = KeyBackupRepository(server, passkey, crypto, Secure(), userId = { "user-1" }, localKeys = { emptyList() }, keepKey = { _, _, _ -> })

    private fun TestScope.viewModel(): KeyBackupViewModel {
        val connection = ConnectionState(Online, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        return KeyBackupViewModel(repository(), Chats(), Profiles(), connection).also { vm -> backgroundScope.launch { vm.state.collect {} } }
    }

    /** Backup is on for the account, made on another phone; this one has signed in again and holds nothing. */
    private suspend fun backupOnElsewhere() = repository().onPrfSecret(byteArrayOf(5), crypto.sha256(VaultRepository.PrfSalt + byteArrayOf(5)))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a restore on a phone without the passkey says where it may be, not a generic failure`() = runTest(dispatcher) {
        backupOnElsewhere()
        val vm = viewModel(); advanceUntilIdle()
        passkey.use = PrfResult.NoPasskey

        vm.onRestore(); advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.passkeyMissing)
        assertNull(s.error, "its own state, with a way forward, instead of an error line")
        assertFalse(s.busy)
        assertEquals(
            "This phone doesn't have the passkey that locked your backup. It may be on another phone, or in another Google or Apple account. " +
                "If Circaso is still signed in there, open Security on that phone and tap Repair. Or tap Restore and choose another device.",
            KeyBackupCopy.PasskeyMissing,
        )
    }

    @Test
    fun `backing out of a restore offers the question without claiming the passkey is gone`() = runTest(dispatcher) {
        backupOnElsewhere()
        val vm = viewModel(); advanceUntilIdle()
        passkey.use = PrfResult.Cancelled

        vm.onRestore(); advanceUntilIdle()
        assertTrue(vm.state.value.askPasskeyMissing)
        assertFalse(vm.state.value.passkeyMissing)
        assertNull(vm.state.value.error, "a cancel is a choice, not an error")
        assertEquals("Passkey not on this phone?", KeyBackupCopy.AskPasskeyMissing)

        vm.onPasskeyMissing()
        assertTrue(vm.state.value.passkeyMissing)
        assertFalse(vm.state.value.askPasskeyMissing)
    }

    @Test
    fun `starting a new backup asks first, says what is lost, then replaces the old one`() = runTest(dispatcher) {
        backupOnElsewhere()
        val old = server.root
        val vm = viewModel(); advanceUntilIdle()
        passkey.use = PrfResult.NoPasskey
        vm.onRestore(); advanceUntilIdle()

        vm.onNewBackupRequested()
        assertTrue(vm.state.value.confirmNew)
        assertEquals(0, server.removed, "nothing happens before the person confirms")
        assertEquals("Start a new backup?", KeyBackupCopy.NewBackupTitle)
        assertEquals(
            "Your old backup is deleted from Circaso, for every phone, and a new one is made with the passkey this phone has. " +
                "Group names on this phone are backed up again. Groups that were only in the old backup show members' names until someone in the group is online, " +
                "and names you saved that were only in the old backup are lost.",
            KeyBackupCopy.NewBackupMessage,
        )
        assertEquals("Start a New Backup", KeyBackupCopy.NewBackup)

        vm.onNewBackupDismissed()
        assertFalse(vm.state.value.confirmNew)
        assertEquals(old, server.root)

        passkey.use = null
        vm.onNewBackupRequested(); vm.onNewBackup(); advanceUntilIdle()

        val s = vm.state.value
        assertEquals(1, server.removed)
        assertTrue(server.root.exists && server.root.sealedRoot != old.sealedRoot)
        assertFalse(s.passkeyMissing)
        assertFalse(s.confirmNew)
        assertTrue(s.onPhone)
        assertNull(s.error)
    }

    @Test
    fun `turning on with a vault whose passkey isn't here names the passkey, not a backup that doesn't exist`() = runTest(dispatcher) {
        val connection = ConnectionState(Online, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        val withVault = KeyBackupRepository(
            server, passkey, crypto, Secure(), userId = { "user-1" }, localKeys = { emptyList() }, keepKey = { _, _, _ -> },
            account = com.circaso.core.platform.AccountPasskey(passkey, vaults = { AppResult.Success(listOf(byteArrayOf(5))) }),
        )
        val vm = KeyBackupViewModel(withVault, Chats(), Profiles(), connection).also { vm -> backgroundScope.launch { vm.state.collect {} } }
        advanceUntilIdle()
        passkey.use = PrfResult.NoPasskey

        vm.onTurnOn(); advanceUntilIdle()

        assertFalse(vm.state.value.passkeyMissing)
        assertEquals(
            "This phone doesn't have your Circaso passkey. It may be on another phone, or in another Google or Apple account. " +
                "Try again and choose another device, or sign in to that account on this phone.",
            vm.state.value.error,
        )
        assertFalse(server.root.exists, "and no passkey was made over the vault's")
    }
}
