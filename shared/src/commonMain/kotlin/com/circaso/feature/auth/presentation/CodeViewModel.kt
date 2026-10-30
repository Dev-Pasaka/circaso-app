package com.circaso.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.feature.auth.domain.StartPhoneVerificationUseCase
import com.circaso.feature.auth.domain.VerifyPhoneCodeUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design ID-06. A verified code moves onboarding to the next step; the app navigates from the session, not from here. */
class CodeViewModel(
    private val phone: String,
    verificationId: String,
    private val codeLength: Int,
    resendAfterSeconds: Int,
    private val verify: VerifyPhoneCodeUseCase,
    private val start: StartPhoneVerificationUseCase,
) : ViewModel() {
    data class UiState(
        val code: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        /** The code field is locked (too many wrong tries); only waiting helps. */
        val locked: Boolean = false,
        val resendInSeconds: Int = 0,
        val resending: Boolean = false,
    ) {
        val canResend get() = resendInSeconds == 0 && !busy && !resending && !locked
    }

    private var verificationId = verificationId
    private val _state = MutableStateFlow(UiState(resendInSeconds = resendAfterSeconds))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var ticker: Job? = null

    init { startCountdown() }

    val maskedPhone: String get() = mask(phone)

    fun onCode(text: String) {
        val s = _state.value
        if (s.busy || s.locked) return
        val digits = text.filter { it.isDigit() }.take(codeLength)
        _state.update { it.copy(code = digits, error = null) }
        if (digits.length == codeLength) submit(digits)
    }

    private fun submit(code: String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            when (val result = verify(verificationId, code)) {
                is AppResult.Success -> _state.update { it.copy(busy = false) } // the session moved on
                is AppResult.Failure -> _state.update { it.copy(busy = false, code = "").withError(result.error) }
            }
        }
    }

    private fun UiState.withError(error: AppError): UiState {
        val code = (error as? AppError.Api)?.code
        return when (code) {
            "identity.code_locked" -> copy(locked = true, error = ErrorMessages.forError(error))
            "identity.code_expired" -> copy(error = ErrorMessages.forError(error), resendInSeconds = 0)
            else -> copy(error = ErrorMessages.forError(error))
        }
    }

    fun onResend() {
        if (!_state.value.canResend) return
        _state.update { it.copy(resending = true, error = null, code = "") }
        viewModelScope.launch {
            when (val result = start(phone)) {
                is AppResult.Success -> {
                    verificationId = result.value.verificationId
                    _state.update { it.copy(resending = false, resendInSeconds = result.value.resendAfterSeconds) }
                    startCountdown()
                }
                is AppResult.Failure -> _state.update { it.copy(resending = false, error = ErrorMessages.forError(result.error)) }
            }
        }
    }

    private fun startCountdown() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (_state.value.resendInSeconds > 0) {
                delay(1_000)
                _state.update { it.copy(resendInSeconds = (it.resendInSeconds - 1).coerceAtLeast(0)) }
            }
        }
    }

    companion object {
        /** `+254712345678` shown as `+254 7•• ••• 678`. */
        fun mask(e164: String): String {
            if (!e164.startsWith("+254") || e164.length < 13) return e164
            val national = e164.drop(4)
            return "+254 ${national.first()}•• ••• ${national.takeLast(3)}"
        }
    }
}
