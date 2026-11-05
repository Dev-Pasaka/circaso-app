package com.circaso.feature.spaces.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.spaces.domain.SpacesRepository
import com.circaso.feature.spaces.domain.SpaceVisibility
import com.circaso.feature.spaces.domain.SpacePrice
import com.circaso.feature.wallet.domain.AddWalletNetworksUseCase
import com.circaso.feature.wallet.domain.AwaitSpaceWalletUseCase
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.StartSpaceWalletUseCase
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The steps of setting up a space's wallet (SN4–SN5), in order; a failed one resumes where it stopped. */
enum class SpaceSetupStep { Pin, Creating, Biometrics, Networks, Live, Done }

enum class SpaceSetupProblem { Failed, Offline, Cancelled }

data class SpaceWalletSetupState(
    val spaceName: String = "",
    val photoUrl: String? = null,
    val handle: String = "",
    val visibility: SpaceVisibility = SpaceVisibility.Public,
    val price: SpacePrice? = null,
    val step: SpaceSetupStep = SpaceSetupStep.Pin,
    val problem: SpaceSetupProblem? = null,
    val message: String? = null,
    val reference: String? = null,
    val online: Boolean = true,
    /** GW4's wording ("Fingerprint", "Face ID"); null skips the offer. */
    val biometricLabel: String? = null,
    val turningOnBiometrics: Boolean = false,
    val walletId: String? = null,
) {
    val pinSet get() = step.ordinal > SpaceSetupStep.Pin.ordinal
    val created get() = step.ordinal > SpaceSetupStep.Creating.ordinal
    val networksAdded get() = step.ordinal > SpaceSetupStep.Networks.ordinal
}

/**
 * The space wallet (SN4–SN5b, W18 → the space wallet's own PIN in Circle's SDK → W19 → biometrics → W13). Online-only,
 * never queued. The space already exists (SETTING_UP) when this starts, so a failure keeps it and says so (SN5b):
 * Finish Wallet Setup carries on where it stopped, and the PIN isn't asked again once set. The space goes LIVE when
 * its wallet is active; the last step reads it again so the page has it.
 */
class SpaceWalletSetupViewModel(
    private val spaceId: String,
    private val spaces: SpacesRepository,
    private val start: StartSpaceWalletUseCase,
    private val await: AwaitSpaceWalletUseCase,
    private val enableBiometrics: EnableWalletBiometricsUseCase,
    private val addNetworks: AddWalletNetworksUseCase,
    private val prefs: AppPreferences?,
    connection: ConnectionState,
    private val biometricLabel: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(SpaceWalletSetupState(biometricLabel = biometricLabel))
    val state: StateFlow<SpaceWalletSetupState> = _state.asStateFlow()
    private var started = false

    init {
        viewModelScope.launch {
            spaces.space(spaceId).collect { s ->
                if (s != null) _state.update { it.copy(spaceName = s.name, photoUrl = s.summary.photoUrl, handle = s.handle, visibility = s.visibility, price = s.summary.price) }
            }
        }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
    }

    fun onStart() {
        if (started) return
        started = true
        viewModelScope.launch {
            // The name goes on Circle's PIN screen: read the space first if the phone doesn't have it.
            if (_state.value.spaceName.isEmpty()) spaces.refreshSpace(spaceId)
            run()
        }
    }

    fun onTryAgain() {
        if (!_state.value.online) { _state.update { it.copy(problem = SpaceSetupProblem.Offline) }; return }
        _state.update { it.copy(problem = null, message = null, reference = null) }
        viewModelScope.launch { run() }
    }

    private fun name() = _state.value.spaceName.ifEmpty { "the space" }
    private fun subject() = SignSubject.space(_state.value.walletId.orEmpty(), name())

    private suspend fun run() {
        if (_state.value.spaceName.isEmpty()) runCatching { kotlinx.coroutines.withTimeoutOrNull(1_500) { state.first { it.spaceName.isNotEmpty() } } }
        while (true) {
            val ok = when (_state.value.step) {
                SpaceSetupStep.Pin -> stepPin()
                SpaceSetupStep.Creating -> stepCreating()
                SpaceSetupStep.Biometrics -> return
                SpaceSetupStep.Networks -> stepNetworks()
                SpaceSetupStep.Live -> stepLive()
                SpaceSetupStep.Done -> return
            }
            if (!ok) return
        }
    }

    /** W18 then the space wallet's own PIN (SN4); a null challenge means the PIN was already set. */
    private suspend fun stepPin(): Boolean = when (val r = start(spaceId, name())) {
        is AppResult.Success -> {
            val active = r.value.status == WalletStatus.Active
            _state.update { it.copy(walletId = r.value.wallet?.walletId ?: it.walletId, step = if (active) afterCreated() else SpaceSetupStep.Creating) }
            true
        }
        is AppResult.Failure -> { fail(r.error); false }
    }

    private suspend fun stepCreating(): Boolean = when (val r = await(spaceId)) {
        is AppResult.Success -> if (r.value.status == WalletStatus.Active) {
            _state.update { it.copy(walletId = r.value.wallet?.walletId ?: it.walletId, step = afterCreated()) }
            true
        } else {
            _state.update { it.copy(problem = SpaceSetupProblem.Failed, message = "Circle is still making the wallet. Try again in a moment; its PIN is already set.") }
            false
        }
        is AppResult.Failure -> { fail(r.error); false }
    }

    private fun afterCreated() = if (biometricLabel != null) SpaceSetupStep.Biometrics else SpaceSetupStep.Networks

    /** GW4 for the space wallet only: each wallet is its own Circle user with its own setting. */
    fun onTurnOnBiometrics() {
        val s = _state.value
        if (s.step != SpaceSetupStep.Biometrics || s.turningOnBiometrics) return
        if (!s.online) { _state.update { it.copy(problem = SpaceSetupProblem.Offline) }; return }
        _state.update { it.copy(turningOnBiometrics = true) }
        viewModelScope.launch {
            val r = enableBiometrics(subject())
            if (r is AppResult.Success) prefs?.setWalletBiometrics(s.walletId, true)
            _state.update { it.copy(turningOnBiometrics = false, step = SpaceSetupStep.Networks) }
            run()
        }
    }

    fun onNotNow() {
        if (_state.value.step != SpaceSetupStep.Biometrics) return
        _state.update { it.copy(step = SpaceSetupStep.Networks) }
        viewModelScope.launch { run() }
    }

    /** W13 for the space wallet. A failure here doesn't stop it (it works on its first network). */
    private suspend fun stepNetworks(): Boolean {
        val r = addNetworks(subject())
        if (r is AppResult.Failure && isOffline(r.error)) { fail(r.error); return false }
        _state.update { it.copy(step = SpaceSetupStep.Live) }
        return true
    }

    /** The space went LIVE with its wallet: read it again (and "Your spaces"), so every screen has it. */
    private suspend fun stepLive(): Boolean {
        spaces.refreshSpace(spaceId)
        spaces.refreshMine()
        _state.update { it.copy(step = SpaceSetupStep.Done) }
        return false
    }

    private fun isOffline(error: AppError) = error == AppError.Network || !_state.value.online

    private fun fail(error: AppError) {
        val api = error as? AppError.Api
        val problem = when {
            api?.code == "wallet.cancelled" -> SpaceSetupProblem.Cancelled
            isOffline(error) -> SpaceSetupProblem.Offline
            else -> SpaceSetupProblem.Failed
        }
        _state.update {
            it.copy(problem = problem, message = if (problem == SpaceSetupProblem.Failed) ErrorMessages.forError(error, mapOf("wallet.not_space_owner" to "Only the space’s owner can set up its wallet.")) else null, reference = ErrorMessages.reference(error))
        }
    }
}
