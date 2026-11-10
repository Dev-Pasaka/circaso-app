package com.circaso.feature.chama.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.chama.domain.SetApprovalPolicyUseCase
import com.circaso.feature.chama.domain.SetGoalUseCase
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.wallet.domain.AddWalletNetworksUseCase
import com.circaso.feature.wallet.domain.AwaitGroupWalletUseCase
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.StartGroupWalletUseCase
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the owner chose in New Group (GW1b), carried into the set-up so it is saved once the wallet exists. */
data class GroupWalletPlan(
    val approvalPolicy: ApprovalPolicy? = null,
    /** The target in USDC minor units, or null for none. */
    val targetMinor: String? = null,
    val targetName: String? = null,
    val deadline: String? = null,
    /** Opened from "Add a Target" (GW7): end on the target form (GW7b) instead of the group. */
    val thenTarget: Boolean = false,
)

/** The steps of setting up a group wallet (GW2–GW5), in order. A failed step is retried from where it stopped. */
enum class SetupStep { Pin, Creating, Biometrics, Networks, Rules, Done }

enum class SetupProblem {
    /** GW5b: a step failed on the server (5xx, an error code). */
    Failed,
    /** GW5c: the connection went mid-set-up; nothing retries silently. */
    Offline,
    /** The owner backed out of the PIN screen: nothing is wrong, the wallet waits for them. */
    Cancelled,
}

data class GroupWalletSetupState(
    val groupName: String = "",
    val avatarUrl: String? = null,
    val step: SetupStep = SetupStep.Pin,
    val problem: SetupProblem? = null,
    /** Words for the failure, keyed on the code; never the server's message. */
    val message: String? = null,
    /** The request id, shown so support can find it ("Ref req_…"). */
    val reference: String? = null,
    val online: Boolean = true,
    /** GW4's wording: "Fingerprint" / "Face ID". Null when the phone has no biometrics (the offer is skipped). */
    val biometricLabel: String? = null,
    val turningOnBiometrics: Boolean = false,
    /** The wallet's settings (who approves, the target) didn't save, though the wallet itself is ready. */
    val rulesFailed: Boolean = false,
    val walletId: String? = null,
) {
    /** The PIN is set once the set-up has gone past it (GW5's first tick). */
    val pinSet get() = step.ordinal > SetupStep.Pin.ordinal
    val created get() = step.ordinal > SetupStep.Creating.ordinal
}

/**
 * Setting up a group's wallet (designs GW2–GW5c, guide W14 → Circle PIN → W15 → biometrics → W13 → CH9 → CH2).
 * Online-only, never queued: every step needs the server, and the PIN is Circle's. The group already exists when
 * this starts, so a failure never loses it: GW5b and GW5c say so and offer Try Again or the group itself, where
 * Finish Wallet Setup carries on (GW6b).
 */
class GroupWalletSetupViewModel(
    private val chatId: String,
    private val plan: GroupWalletPlan,
    observeChat: ObserveChatUseCase,
    private val start: StartGroupWalletUseCase,
    private val await: AwaitGroupWalletUseCase,
    private val enableBiometrics: EnableWalletBiometricsUseCase,
    private val addNetworks: AddWalletNetworksUseCase,
    private val setPolicy: SetApprovalPolicyUseCase,
    private val setGoal: SetGoalUseCase,
    private val refreshMoney: RefreshGroupMoneyUseCase,
    private val prefs: AppPreferences,
    private val connection: ConnectionState,
    /** "Fingerprint", "Face ID"; null when the phone has none set up. */
    private val biometricLabel: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(GroupWalletSetupState(biometricLabel = biometricLabel))
    val state: StateFlow<GroupWalletSetupState> = _state.asStateFlow()

    /** CH9 is sent with one key across retries, so a slow first answer can't be applied twice. */
    private val policyKey = newIdempotencyKey()
    private var started = false

    init {
        viewModelScope.launch { observeChat(chatId).collect { chat -> _state.update { it.copy(groupName = chat?.title ?: it.groupName, avatarUrl = chat?.avatarUrl) } } }
        viewModelScope.launch { connection.status.collect { s -> _state.update { it.copy(online = s == ConnectionStatus.Online) } } }
    }

    /** The screen is on: begin (once). The PIN screen opens over it, so the steps show behind it. */
    fun onStart() {
        if (started) return
        started = true
        run()
    }

    fun onTryAgain() {
        if (!_state.value.online) { _state.update { it.copy(problem = SetupProblem.Offline) }; return }
        _state.update { it.copy(problem = null, message = null, reference = null) }
        run()
    }

    private fun subject(): SignSubject = SignSubject.group(_state.value.walletId.orEmpty(), name())

    private fun name(): String = _state.value.groupName.ifEmpty { "the group" }

    private fun run() {
        viewModelScope.launch {
            // The group's name names the wallet on Circle's screens: wait a moment for the chat to be read.
            if (_state.value.groupName.isEmpty()) runCatching { kotlinx.coroutines.withTimeoutOrNull(1_500) { state.first { it.groupName.isNotEmpty() } } }
            while (true) {
                val s = _state.value
                val next = when (s.step) {
                    SetupStep.Pin -> stepPin()
                    SetupStep.Creating -> stepCreating()
                    SetupStep.Biometrics -> return@launch // waits for the person (GW4)
                    SetupStep.Networks -> stepNetworks()
                    SetupStep.Rules -> stepRules()
                    SetupStep.Done -> return@launch
                }
                if (!next) return@launch
            }
        }
    }

    /** W14 then the group wallet's own PIN in the Circle SDK (GW2/GW3). */
    private suspend fun stepPin(): Boolean = when (val r = start(chatId, name())) {
        is AppResult.Success -> {
            val active = r.value.status == WalletStatus.Active
            _state.update { it.copy(walletId = r.value.wallet?.walletId ?: it.walletId, step = if (active) afterCreated() else SetupStep.Creating) }
            true
        }
        is AppResult.Failure -> { fail(r.error); false }
    }

    /** W15 until the wallet is active (or `wallet.ready`). */
    private suspend fun stepCreating(): Boolean = when (val r = await(chatId)) {
        is AppResult.Success -> {
            if (r.value.status == WalletStatus.Active) {
                _state.update { it.copy(walletId = r.value.wallet?.walletId ?: it.walletId, step = afterCreated()) }
                true
            } else {
                // Still on its way after a minute: not an error, but the person shouldn't wait on a spinner forever.
                _state.update { it.copy(problem = SetupProblem.Failed, message = "Circle is still making the wallet. Try again in a moment; your PIN is already set.") }
                false
            }
        }
        is AppResult.Failure -> { fail(r.error); false }
    }

    private fun afterCreated() = if (biometricLabel != null) SetupStep.Biometrics else SetupStep.Networks

    /** GW4: "Approve Kilimani Chama payments with fingerprint?" for this wallet only. */
    fun onTurnOnBiometrics() {
        val s = _state.value
        if (s.step != SetupStep.Biometrics || s.turningOnBiometrics) return
        if (!s.online) { _state.update { it.copy(problem = SetupProblem.Offline) }; return }
        _state.update { it.copy(turningOnBiometrics = true) }
        viewModelScope.launch {
            markOffered(s.walletId)
            val r = enableBiometrics(subject())
            if (r is AppResult.Success) prefs.setWalletBiometrics(s.walletId, true)
            // Not turned on (cancelled, or Circle couldn't): the wallet still works with its PIN; carry on either way.
            _state.update { it.copy(turningOnBiometrics = false, step = SetupStep.Networks) }
            run()
        }
    }

    fun onNotNow() {
        if (_state.value.step != SetupStep.Biometrics) return
        val walletId = _state.value.walletId
        _state.update { it.copy(step = SetupStep.Networks) }
        viewModelScope.launch { markOffered(walletId) }
        run()
    }

    /** GW4 is this wallet's one offer: no second one after its first PIN approval (group info has the switch). */
    private suspend fun markOffered(walletId: String?) {
        if (walletId != null) prefs.setFlag(com.circaso.core.datastore.AppPreferences.biometricOfferedFlag(walletId), true)
    }

    /**
     * W13 `?walletId=`: the group wallet on every network, with its own PIN. A failure here doesn't stop the wallet
     * (it works on its first network); it can be done again from the wallet's networks later.
     */
    private suspend fun stepNetworks(): Boolean {
        val r = addNetworks(subject())
        if (r is AppResult.Failure && isOffline(r.error)) { fail(r.error); return false }
        _state.update { it.copy(step = SetupStep.Rules) }
        return true
    }

    /** CH9 then CH2: what was chosen in New Group. Both online-only; a failure keeps the wallet and says so. */
    private suspend fun stepRules(): Boolean {
        plan.approvalPolicy?.takeIf { it != ApprovalPolicy.TwoAdmins }?.let { policy ->
            val r = setPolicy(chatId, policy, policyKey)
            if (r is AppResult.Failure) { _state.update { it.copy(rulesFailed = true) }; fail(r.error); return false }
        }
        val target = plan.targetMinor
        if (target != null) {
            val r = setGoal(chatId, plan.targetName ?: name(), target, plan.deadline)
            if (r is AppResult.Failure) { _state.update { it.copy(rulesFailed = true) }; fail(r.error); return false }
        }
        refreshMoney(chatId)
        _state.update { it.copy(step = SetupStep.Done, rulesFailed = false) }
        return false
    }

    private fun isOffline(error: AppError) = error == AppError.Network || !_state.value.online

    private fun fail(error: AppError) {
        val api = error as? AppError.Api
        val problem = when {
            api?.code == "wallet.cancelled" -> SetupProblem.Cancelled
            isOffline(error) -> SetupProblem.Offline
            else -> SetupProblem.Failed
        }
        _state.update { it.copy(problem = problem, message = if (problem == SetupProblem.Failed) ErrorMessages.forError(error) else null, reference = ErrorMessages.reference(error)) }
    }

    val wantsTarget get() = plan.thenTarget
}
