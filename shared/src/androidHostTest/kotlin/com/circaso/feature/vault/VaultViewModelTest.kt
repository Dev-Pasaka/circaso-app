package com.circaso.feature.vault

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.crypto.AndroidSignalCrypto
import com.circaso.core.network.NetworkHealth
import com.circaso.core.network.NoBody
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.platform.PasskeyPrf
import com.circaso.core.platform.PrfResult
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.profile.domain.AvatarImage
import com.circaso.feature.profile.domain.HandleAvailability
import com.circaso.feature.profile.domain.Profile
import com.circaso.feature.profile.domain.ProfileChange
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.profile.domain.PublicProfile
import com.circaso.feature.vault.data.SaveVaultRequest
import com.circaso.feature.vault.data.VaultDto
import com.circaso.feature.vault.data.VaultRemote
import com.circaso.feature.vault.data.VaultRepository
import com.circaso.feature.vault.data.VaultStatusDto
import com.circaso.feature.vault.presentation.VaultStep
import com.circaso.feature.vault.presentation.VaultViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

/** The PIN Vault screen: save, open (passkey before the server), the reveal timer, and the states in between. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val crypto = AndroidSignalCrypto()

    private class Remote : VaultRemote {
        var stored: VaultDto? = null
        var opens = 0
        override suspend fun status() = AppResult.Success(VaultStatusDto(stored != null, stored?.credentialId, stored?.version, stored?.updatedAt))
        override suspend fun open(): AppResult<VaultDto> { opens++; return stored?.let { AppResult.Success(it) } ?: AppResult.Failure(AppError.Api("vault.not_found", 404, null, null, emptyMap())) }
        override suspend fun save(request: SaveVaultRequest, idempotencyKey: String): AppResult<VaultStatusDto> {
            val version = (stored?.version ?: 0) + 1
            stored = VaultDto(request.credentialId, request.salt, request.ciphertext, request.algorithm, version, "2026-11-11T20:00:00Z")
            return AppResult.Success(VaultStatusDto(true, request.credentialId, version, "2026-11-11T20:00:00Z"))
        }
        override suspend fun remove(): AppResult<NoBody> { stored = null; return AppResult.Success(NoBody()) }
    }

    /** A passkey whose PRF secret depends on the salt, like a real one; [cancel] backs out of the sheet. */
    private inner class Passkey : PasskeyPrf {
        var cancel = false
        override suspend fun isSupported() = true
        override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray) =
            if (cancel) PrfResult.Cancelled else PrfResult.Success(byteArrayOf(1, 2, 3), crypto.sha256(prfSalt + 7))
        override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray) =
            if (cancel) PrfResult.Cancelled else PrfResult.Success(credentialId, crypto.sha256(prfSalt + 7))
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

    private val remote = Remote()
    private val passkey = Passkey()

    private fun TestScope.viewModel(): VaultViewModel {
        val repo = VaultRepository(remote, passkey, crypto, userId = { "user-1" }, newIdempotencyKey = { "k" })
        val connection = ConnectionState(Online, NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        return VaultViewModel(repo, Profiles(), connection).also { vm -> backgroundScope.launch { vm.state.collect {} } }
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `no vault shows the intro, and save needs matching 6-digit PINs`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        assertEquals(VaultStep.Intro, vm.state.value.step)

        vm.onStart(); vm.onPin("4829"); vm.onConfirmPin("4829")
        assertFalse(vm.state.value.canSave, "too short")
        vm.onPin("482915"); vm.onConfirmPin("482916")
        assertFalse(vm.state.value.canSave, "don't match")
        vm.onConfirmPin("482915")
        vm.onAddAnswer()
        vm.onQuestion(vm.state.value.answers.single().id, "First school?")
        assertFalse(vm.state.value.canSave, "a question with no answer")
        vm.onAnswer(vm.state.value.answers.single().id, "Moi Avenue")
        assertTrue(vm.state.value.canSave)
    }

    @Test
    fun `saving then opening shows the PIN, which hides itself after a minute`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        vm.onStart(); vm.onPin("482915"); vm.onConfirmPin("482915"); vm.onSave(); advanceUntilIdle()
        assertEquals(VaultStep.Saved, vm.state.value.step)

        vm.onOpen(); advanceTimeBy(1_000)
        assertEquals(VaultStep.Revealed, vm.state.value.step)
        assertEquals("482915", vm.state.value.revealed?.pin)

        advanceTimeBy(VaultViewModel.RevealMillis); advanceUntilIdle()
        assertEquals(VaultStep.Saved, vm.state.value.step)
        assertNull(vm.state.value.revealed)
    }

    @Test
    fun `backing out of the passkey never reaches the server, so sending isn't paused`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        vm.onStart(); vm.onPin("482915"); vm.onConfirmPin("482915"); vm.onSave(); advanceUntilIdle()

        passkey.cancel = true
        vm.onOpen(); advanceUntilIdle()

        assertEquals(0, remote.opens)
        assertEquals(VaultStep.Saved, vm.state.value.step)
        assertNull(vm.state.value.error, "a cancel is a choice, not an error")
    }

    @Test
    fun `removing goes back to the intro`() = runTest(dispatcher) {
        val vm = viewModel(); advanceUntilIdle()
        vm.onStart(); vm.onPin("482915"); vm.onConfirmPin("482915"); vm.onSave(); advanceUntilIdle()
        vm.onRemove(); advanceUntilIdle()
        assertEquals(VaultStep.Intro, vm.state.value.step)
        assertNull(remote.stored)
    }
}
