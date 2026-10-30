package com.circaso.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.session.AuthManager
import com.circaso.feature.auth.domain.PhoneNumber
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.StartPhoneVerificationUseCase
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design ID-04 (add your number) and ID-07 (number already registered). */
class PhoneViewModel(
    private val start: StartPhoneVerificationUseCase,
    private val auth: AuthManager,
) : ViewModel() {
    data class UiState(
        val input: String = "",
        val busy: Boolean = false,
        val error: String? = null,
        /** True after the server says this number belongs to another account (ID-07). */
        val numberTaken: Boolean = false,
        /** The account's masked number when a known account is verifying on a new phone. */
        val accountNumberHint: String? = null,
    )

    sealed interface Event {
        data class CodeSent(val phone: String, val verification: PhoneVerification) : Event
    }

    private val _state = MutableStateFlow(
        UiState(accountNumberHint = auth.stored.value.maskedPhone.takeIf { auth.stored.value.onboardingStep == "REVERIFY_PHONE" }),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val events = Channel<Event>(Channel.BUFFERED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    fun onInput(text: String) {
        _state.update { it.copy(input = PhoneNumber.nationalDigits(text), error = null) }
    }

    fun onSendCode() {
        val current = _state.value
        if (current.busy) return
        val phone = PhoneNumber.toE164(current.input)
        if (phone == null) {
            _state.update { it.copy(error = "That number looks too short. Kenyan numbers have ${PhoneNumber.NationalDigits} digits after ${PhoneNumber.CountryCode}.") }
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = start(phone)) {
                is AppResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    events.send(Event.CodeSent(phone, result.value))
                }
                is AppResult.Failure -> _state.update { it.copy(busy = false).withError(result.error) }
            }
        }
    }

    private fun UiState.withError(error: AppError): UiState {
        if (error is AppError.Api && error.code == "identity.phone_taken") return copy(numberTaken = true)
        if (error is AppError.Api && error.code == "request.rate_limited" && error.retryAfterSeconds != null) {
            val minutes = (error.retryAfterSeconds + 59) / 60
            return copy(error = "Too many codes sent. Try again in $minutes ${if (minutes == 1) "minute" else "minutes"}.")
        }
        return copy(error = ErrorMessages.forError(error))
    }

    /** "Use a different number" on ID-07. */
    fun onUseDifferentNumber() = _state.update { it.copy(numberTaken = false, input = "", error = null) }

    /** Back out of sign-up: the person signs in again from the start. */
    fun onCancel() {
        viewModelScope.launch { auth.signOutLocally() }
    }
}
