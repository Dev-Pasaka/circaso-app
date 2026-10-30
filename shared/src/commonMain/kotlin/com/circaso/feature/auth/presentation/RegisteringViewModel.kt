package com.circaso.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.feature.auth.domain.RegisterDeviceUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design ID-03 "Signing you in…". Registers this phone's key; on success the session starts and the app moves on. */
class RegisteringViewModel(private val register: RegisterDeviceUseCase) : ViewModel() {
    private companion object {
        const val RaceGraceMillis = 5_000L
    }

    data class UiState(val busy: Boolean = true, val error: String? = null, val reference: String? = null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { run() }

    fun retry() {
        if (!_state.value.busy) run()
    }

    private fun run() {
        _state.value = UiState(busy = true)
        viewModelScope.launch {
            when (val result = register()) {
                is AppResult.Success -> Unit
                is AppResult.Failure -> {
                    // A second registration raced the first (409 request.in_progress): the first one's answer starts the
                    // session and moves the app on. Don't retry this one; only offer a retry if nothing happened meanwhile.
                    if ((result.error as? AppError.Api)?.code == "request.in_progress") delay(RaceGraceMillis)
                    // The kind of failure only (no tokens, keys or numbers): this is what support needs when the screen can only say "try again".
                    val e = result.error
                    println("CircasoRegister: device registration failed: " + if (e is AppError.Api) "code=${e.code} status=${e.httpStatus} requestId=${e.requestId}" else e::class.simpleName)
                    _state.update {
                        UiState(busy = false, error = ErrorMessages.forError(result.error), reference = ErrorMessages.reference(result.error))
                    }
                }
            }
        }
    }
}
