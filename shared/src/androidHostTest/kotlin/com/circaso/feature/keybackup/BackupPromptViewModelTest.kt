package com.circaso.feature.keybackup

import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.NoBody
import com.circaso.core.platform.AccountPasskey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.core.platform.SecureStorage
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.keybackup.data.BackedUpGroupKey
import com.circaso.feature.keybackup.data.BackupPromptMemory
import com.circaso.feature.keybackup.data.BackupRootDto
import com.circaso.feature.keybackup.data.GroupKeysPage
import com.circaso.feature.keybackup.data.KeyBackupRemote
import com.circaso.feature.keybackup.data.KeyBackupRepository
import com.circaso.feature.keybackup.data.SaveRootRequest
import com.circaso.feature.keybackup.presentation.BackupPromptCopy
import com.circaso.feature.keybackup.presentation.BackupPromptViewModel
import com.circaso.feature.keybackup.presentation.BackupSheet
import com.circaso.feature.keybackup.presentation.BackupStep
import com.circaso.feature.keybackup.presentation.backupPromptLook
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup set up from the word go (2026-11-23): one sheet after signing in (set up, restore or repair), its
 * outcomes inside the same sheet, "Not Now" remembered for a week, offline waiting, and never over anything else.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalEncodingApi::class)
class BackupPromptViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val crypto = AndroidSignalCrypto()

    private class Server : KeyBackupRemote {
        var root: BackupRootDto = BackupRootDto(exists = false)
        val keys = linkedMapOf<Pair<String, Int>, String>()
        var removed = 0
        override suspend fun root() = AppResult.Success(root)
        override suspend fun saveRoot(request: SaveRootRequest): AppResult<BackupRootDto> {
            root = BackupRootDto(true, request.credentialId, request.salt, request.sealedRoot, request.algorithm, (root.version ?: 0) + 1)
            return AppResult.Success(root)
        }
        override suspend fun putGroupKey(chatId: String, metaVersion: Int, ciphertext: String): AppResult<NoBody> { keys[chatId to metaVersion] = ciphertext; return AppResult.Success(NoBody()) }
        override suspend fun groupKeys(after: String?, limit: Int) = AppResult.Success(GroupKeysPage(keys.entries.map { (k, v) -> BackedUpGroupKey(k.first, k.second, v) }))
        override suspend fun remove(): AppResult<NoBody> { removed++; root = BackupRootDto(false); keys.clear(); return AppResult.Success(NoBody()) }
    }

    /** "Use this passkey" answers as set per test (by default every passkey answers); making one always works. */
    private inner class Passkey : PasskeyPrf {
        var use: PrfResult? = null
        var made = 0
        var asked = 0
        override suspend fun isSupported() = true
        override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray): PrfResult { made++; return PrfResult.Success(byteArrayOf(9), crypto.sha256(prfSalt + 9)) }
        override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray): PrfResult { asked++; return use ?: PrfResult.Success(credentialId, crypto.sha256(prfSalt + credentialId)) }
    }

    private class Secure : SecureStorage {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
        override suspend fun remove(key: String) { map.remove(key) }
    }

    private class Memory : BackupPromptMemory {
        val map = mutableMapOf<String, Long>()
        override suspend fun quietUntil(userId: String, kind: String) = map["$userId|$kind"] ?: 0L
        override suspend fun quiet(userId: String, kind: String, until: Long) { map["$userId|$kind"] = until }
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

    private class Network : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

    private val server = Server()
    private val passkey = Passkey()
    private val memory = Memory()
    private val network = Network()
    private var clock = 1_000_000_000L
    private var vaultPasskey: ByteArray? = null
    private val groups = mutableMapOf<Pair<String, Int>, ByteArray>()

    /** One phone's backup; [secure] is its secure storage (a fresh one is a fresh install). */
    private fun repository(secure: Secure = Secure()) = KeyBackupRepository(
        server, passkey, crypto, secure, userId = { "user-1" },
        localKeys = { groups.map { (k, v) -> Triple(k.first, k.second, v) } },
        keepKey = { chat, version, key -> groups[chat to version] = key },
        account = AccountPasskey(passkey, vaults = { AppResult.Success(listOfNotNull(vaultPasskey)) }),
    )

    private fun TestScope.viewModel(backup: KeyBackupRepository): BackupPromptViewModel {
        val connection = ConnectionState(network, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        return BackupPromptViewModel(backup, Chats(), Profiles(), connection, memory, userId = { "user-1" }, now = { clock })
            .also { vm -> backgroundScope.launch { vm.state.collect {} } }
    }

    /** Home's Chats is on top with nothing else open, for long enough. */
    private fun TestScope.calm(vm: BackupPromptViewModel) { vm.onCalm(true); advanceTimeBy(BackupPromptViewModel.SettleMillis + 1); runCurrent() }

    /** Backup was turned on from another phone, under passkey 5; this phone is a fresh install. */
    private suspend fun backupOnElsewhere() {
        groups["chat-1" to 1] = crypto.randomBytes(32)
        repository().onPrfSecret(byteArrayOf(5), crypto.sha256(VaultRepository.PrfSalt + byteArrayOf(5)))
        groups.clear()
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no backup on the account - the set-up sheet makes the passkey and turns backup on in one step`() = runTest(dispatcher) {
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)

        val s = vm.state.value
        assertEquals(BackupSheet.SetUp, s.sheet)
        assertTrue(s.visible)
        assertEquals(BackupPromptCopy.SetUpTitle, backupPromptLook(s, "Fingerprint")?.title)
        assertEquals("Turn On With Fingerprint", backupPromptLook(s, "Fingerprint")?.primary)

        vm.onPrimary(); runCurrent()

        assertEquals(1, passkey.made, "one passkey made, in the same step")
        assertTrue(server.root.exists)
        assertTrue(backup.status.value.onPhone)
        assertEquals(BackupStep.Done, vm.state.value.step)
        assertEquals(BackupSheet.SetUp, vm.state.value.sheet, "held while it says it's done")
        assertEquals(BackupPromptCopy.SetUpDoneTitle, backupPromptLook(vm.state.value, "Fingerprint")?.title)

        advanceTimeBy(BackupPromptViewModel.DoneMillis + 1); runCurrent()
        assertNull(vm.state.value.sheet, "then it closes by itself")
        assertFalse(vm.state.value.visible)
    }

    @Test
    fun `set-up uses the vault's passkey when there is one, never a second`() = runTest(dispatcher) {
        vaultPasskey = byteArrayOf(6)
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)

        vm.onPrimary(); runCurrent()

        assertEquals(0, passkey.made)
        assertEquals(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayOf(6)), server.root.credentialId)
    }

    @Test
    fun `Not Now keeps the set-up sheet quiet for a week, on this phone`() = runTest(dispatcher) {
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)
        assertEquals(BackupSheet.SetUp, vm.state.value.sheet)

        vm.onClose(); runCurrent()
        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.visible)
        assertEquals(0, passkey.made)
        assertEquals(clock + BackupPromptViewModel.QuietMillis, memory.map["user-1|SetUp"])

        // The next launch, a day later: still quiet.
        clock += 24L * 60 * 60 * 1000
        val dayLater = viewModel(backup).also { calm(it) }
        assertNull(dayLater.state.value.sheet)

        // Eight days later: asked again.
        clock += 7L * 24 * 60 * 60 * 1000
        val weekLater = viewModel(backup).also { calm(it) }
        assertEquals(BackupSheet.SetUp, weekLater.state.value.sheet)
    }

    @Test
    fun `fresh install with a backup - the restore sheet brings the chats back and says so`() = runTest(dispatcher) {
        backupOnElsewhere()
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)

        assertEquals(BackupSheet.Restore, vm.state.value.sheet)
        assertEquals("Restore With Face ID", backupPromptLook(vm.state.value, "Face ID")?.primary)

        vm.onPrimary(); runCurrent(); advanceTimeBy(BackupPromptViewModel.SettleListMillis + 1); runCurrent()

        assertEquals(1, groups.size, "the backed-up group key is back")
        assertTrue(backup.status.value.onPhone)
        assertEquals(BackupStep.Done, vm.state.value.step)
        assertEquals(BackupPromptCopy.RestoredTitle, backupPromptLook(vm.state.value, "Face ID")?.title)
        assertFalse(vm.state.value.resumeRestore)

        advanceTimeBy(BackupPromptViewModel.DoneMillis + 1); runCurrent()
        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.resumeRestore, "nothing left to offer on Chats")
    }

    @Test
    fun `the passkey isn't on this phone - the same sheet offers another device, then a new backup after a confirm`() = runTest(dispatcher) {
        backupOnElsewhere()
        val old = server.root
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)
        passkey.use = PrfResult.NoPasskey

        vm.onPrimary(); runCurrent()
        assertEquals(BackupStep.PasskeyNotHere, vm.state.value.step)
        assertNull(vm.state.value.error, "its own state, not an error line")
        val look = backupPromptLook(vm.state.value, "Fingerprint")!!
        assertEquals(BackupPromptCopy.NotHereTitle, look.title)
        assertEquals(BackupPromptCopy.UseAnotherDevice, look.primary)

        // Use Another Device: the same passkey sheet again; still not here.
        vm.onPrimary(); runCurrent()
        assertEquals(BackupStep.PasskeyNotHere, vm.state.value.step)

        vm.onStartNewRequested()
        assertEquals(BackupStep.ConfirmNew, vm.state.value.step)
        assertEquals(0, server.removed, "nothing happens before the person confirms")
        vm.onStartNewCancelled()
        assertEquals(BackupStep.PasskeyNotHere, vm.state.value.step)
        assertEquals(old, server.root)

        passkey.use = null
        vm.onStartNewRequested(); vm.onPrimary(); runCurrent()

        assertEquals(1, server.removed)
        assertTrue(server.root.exists && server.root.sealedRoot != old.sealedRoot)
        assertTrue(backup.status.value.onPhone)
        assertEquals(BackupStep.Done, vm.state.value.step)
        assertEquals(BackupPromptCopy.NewBackupDoneTitle, backupPromptLook(vm.state.value, "Fingerprint")?.title)
    }

    @Test
    fun `Use Another Device that finds nothing says so instead of looking like it did nothing`() = runTest(dispatcher) {
        backupOnElsewhere()
        val vm = viewModel(repository().also { it.refresh() })
        calm(vm)
        passkey.use = PrfResult.NoPasskey
        vm.onPrimary(); runCurrent()
        assertNull(vm.state.value.error)

        vm.onPrimary(); runCurrent()
        assertEquals(BackupStep.PasskeyNotHere, vm.state.value.step)
        assertEquals(BackupPromptCopy.NoOtherDevice, vm.state.value.error)
    }

    @Test
    fun `with a screen reader on, done waits to be closed`() = runTest(dispatcher) {
        val vm = viewModel(repository().also { it.refresh() })
        vm.onScreenReader(true)
        calm(vm)
        vm.onPrimary(); runCurrent()
        advanceTimeBy(BackupPromptViewModel.DoneLongMillis * 2); runCurrent()
        assertEquals(BackupStep.Done, vm.state.value.step)
        assertTrue(vm.state.value.visible)
        vm.onPrimary(); runCurrent()
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `Not Now on Chats' card keeps it away for a week too`() = runTest(dispatcher) {
        backupOnElsewhere()
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)
        vm.onClose(); runCurrent()
        assertTrue(vm.state.value.resumeRestore)

        vm.onCardDismissed(); runCurrent()
        assertFalse(vm.state.value.resumeRestore)
        assertEquals(clock + BackupPromptViewModel.QuietMillis, memory.map["user-1|Card"])

        clock += 24L * 60 * 60 * 1000
        assertFalse(viewModel(backup).also { calm(it) }.state.value.resumeRestore, "the next launch too")
    }

    @Test
    fun `done reached while the sheet stepped aside waits to be seen before it closes`() = runTest(dispatcher) {
        val vm = viewModel(repository().also { it.refresh() })
        calm(vm)
        vm.onPrimary()
        vm.onCalm(false) // a call came in while the passkey answered
        runCurrent()
        assertEquals(BackupStep.Done, vm.state.value.step)
        advanceTimeBy(BackupPromptViewModel.DoneLongMillis * 2); runCurrent()
        assertEquals(BackupStep.Done, vm.state.value.step, "never closed unseen")

        calm(vm)
        assertTrue(vm.state.value.visible)
        advanceTimeBy(BackupPromptViewModel.DoneMillis + 1); runCurrent()
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `a backup turned off elsewhere mid-restore turns into set-up, and says why`() = runTest(dispatcher) {
        backupOnElsewhere()
        val vm = viewModel(repository().also { it.refresh() })
        calm(vm)
        assertEquals(BackupSheet.Restore, vm.state.value.sheet)
        server.remove()

        vm.onPrimary(); runCurrent()

        assertEquals(BackupSheet.SetUp, vm.state.value.sheet)
        assertEquals(BackupPromptCopy.TurnedOffElsewhere, vm.state.value.notice)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `a cancelled restore keeps the sheet, asks quietly, and once put away Chats offers to resume`() = runTest(dispatcher) {
        backupOnElsewhere()
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)
        passkey.use = PrfResult.Cancelled

        vm.onPrimary(); runCurrent()
        assertEquals(BackupStep.Ask, vm.state.value.step)
        assertTrue(vm.state.value.visible)
        assertTrue(vm.state.value.askNotHere)
        assertNull(vm.state.value.error, "a cancel is a choice, not an error")

        vm.onClose(); runCurrent()
        assertFalse(vm.state.value.visible)
        assertTrue(vm.state.value.resumeRestore)

        passkey.use = null
        vm.onResume(); runCurrent(); advanceTimeBy(BackupPromptViewModel.SettleListMillis + 1); runCurrent()
        assertEquals(BackupStep.Done, vm.state.value.step, "one tap on the card restores, in the sheet")
        assertTrue(vm.state.value.visible)
        assertTrue(backup.status.value.onPhone)
    }

    @Test
    fun `offline the sheet waits for the connection and carries on when it is back`() = runTest(dispatcher) {
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)
        network.osStatus.value = ConnectionStatus.Offline; runCurrent()

        assertTrue(vm.state.value.visible)
        assertFalse(vm.state.value.online)
        vm.onPrimary(); runCurrent()
        assertEquals(0, passkey.made, "no passkey asked while it can't finish")
        assertEquals(BackupStep.Ask, vm.state.value.step)

        network.osStatus.value = ConnectionStatus.Online; runCurrent()
        assertTrue(vm.state.value.online)
        vm.onPrimary(); runCurrent()
        assertEquals(BackupStep.Done, vm.state.value.step)
    }

    @Test
    fun `never over anything else - it waits for a calm screen, settles first, and steps aside`() = runTest(dispatcher) {
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        assertEquals(BackupSheet.SetUp, vm.state.value.sheet, "due")
        assertFalse(vm.state.value.visible, "but not shown: a money step, a call or a secret is on screen")

        advanceTimeBy(60_000); runCurrent()
        assertFalse(vm.state.value.visible)

        vm.onCalm(true)
        advanceTimeBy(BackupPromptViewModel.SettleMillis / 2); runCurrent()
        assertFalse(vm.state.value.visible, "a moment after the screen lands, not at once")
        advanceTimeBy(BackupPromptViewModel.SettleMillis); runCurrent()
        assertTrue(vm.state.value.visible)

        // A call rings, or Pay opens: it steps aside, keeping its place.
        vm.onCalm(false)
        assertFalse(vm.state.value.visible)
        assertEquals(BackupSheet.SetUp, vm.state.value.sheet)
        calm(vm)
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun `only one sheet - a sheet under way is held whatever the backup does meanwhile, and nothing follows it`() = runTest(dispatcher) {
        backupOnElsewhere()
        val backup = repository().also { it.refresh() }
        val vm = viewModel(backup)
        calm(vm)
        vm.onPrimary(); runCurrent(); advanceTimeBy(BackupPromptViewModel.SettleListMillis + 1); runCurrent()
        // Restored: this phone needs nothing now, yet the sheet stays on its "done".
        assertNull(BackupPromptViewModel.wanted(backup.status.value))
        assertEquals(BackupSheet.Restore, vm.state.value.sheet)

        vm.onClose(); runCurrent()
        calm(vm)
        assertNull(vm.state.value.sheet)
        assertFalse(vm.state.value.visible)
    }

    /** This phone holds the root under passkey 5 while the account's vault now uses passkey 6. */
    private suspend fun splitAccount(): KeyBackupRepository {
        groups["chat-1" to 1] = crypto.randomBytes(32)
        val backup = repository()
        backup.onPrfSecret(byteArrayOf(5), crypto.sha256(VaultRepository.PrfSalt + byteArrayOf(5)))
        vaultPasskey = byteArrayOf(6)
        backup.refresh()
        return backup
    }

    @Test
    fun `a backup under a passkey the account no longer uses - Finish securing, one answer repairs it`() = runTest(dispatcher) {
        val backup = splitAccount()
        assertTrue(backup.status.value.needsRepair)
        val vm = viewModel(backup)
        calm(vm)
        assertEquals(BackupSheet.Repair, vm.state.value.sheet)
        assertEquals(BackupPromptCopy.RepairTitle, backupPromptLook(vm.state.value, "Fingerprint")?.title)

        vm.onPrimary(); runCurrent()

        assertEquals(BackupStep.Done, vm.state.value.step)
        assertEquals(Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(byteArrayOf(6)), server.root.credentialId)
        assertFalse(backup.status.value.needsRepair)
        assertEquals(0, passkey.made)
    }

    @Test
    fun `the repair happens by itself the next time the passkey answers, and the sheet never asks`() = runTest(dispatcher) {
        val backup = splitAccount()
        val vm = viewModel(backup)
        assertEquals(BackupSheet.Repair, vm.state.value.sheet)

        // The vault opened with passkey 6 (its own prompt): the backup heals with the same answer.
        backup.onPrfSecret(byteArrayOf(6), crypto.sha256(VaultRepository.PrfSalt + byteArrayOf(6))); runCurrent()

        assertFalse(backup.status.value.needsRepair)
        calm(vm)
        assertNull(vm.state.value.sheet)
    }

    @Test
    fun `another person signing in on this phone starts from nothing, not the last person's backup`() = runTest(dispatcher) {
        var person = "user-1"
        val secure = Secure()
        val backup = KeyBackupRepository(server, passkey, crypto, secure, userId = { person }, localKeys = { emptyList() }, keepKey = { _, _, _ -> })
        backup.turnOn("@a", "A")
        assertTrue(backup.status.value.onPhone)

        // Signed out (the cleaner wipes the root from secure storage), then someone else signs in.
        secure.remove(KeyBackupRepository.LocalRoot)
        person = "user-2"
        assertNull(backup.rootOnPhone(), "the last person's root isn't used for this one")
        backup.refresh()
        assertFalse(backup.status.value.onPhone)
        assertEquals(BackupSheet.Restore, BackupPromptViewModel.wanted(backup.status.value), "the account's backup is offered, to restore")
    }
}
