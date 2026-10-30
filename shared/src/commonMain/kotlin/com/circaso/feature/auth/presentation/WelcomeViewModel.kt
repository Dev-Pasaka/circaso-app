package com.circaso.feature.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.circaso.core.common.ErrorMessages
import com.circaso.core.session.AuthManager
import com.circaso.feature.auth.domain.SignInWithSocialUseCase
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignInState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Design ID-02. Moving on is the session's job: when sign-in succeeds the auth state changes and the app navigates itself. */
class WelcomeViewModel(
    private val signIn: SignInWithSocialUseCase,
    private val auth: AuthManager,
) : ViewModel() {
    data class UiState(
        /** The provider whose sign-in is running, or null. */
        val busy: SocialProvider? = null,
        val error: String? = null,
        /** "You were signed out..." when the server ended the session. */
        val signedOutNotice: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState(signedOutNotice = auth.endedByServer.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onContinue(provider: SocialProvider) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = provider, error = null) }
        viewModelScope.launch {
            val error: String? = when (val result = signIn(provider)) {
                is SocialSignInState.Next, SocialSignInState.Cancelled -> null
                SocialSignInState.Unavailable -> "Signing in with ${provider.label} isn't available in this version yet."
                is SocialSignInState.Failed -> result.error?.let { ErrorMessages.forError(it) } ?: "Couldn't reach ${provider.label}. Try again."
            }
            _state.update { it.copy(busy = null, error = error, signedOutNotice = if (error != null) false else it.signedOutNotice) }
        }
    }
}

val SocialProvider.label: String get() = when (this) { SocialProvider.Google -> "Google"; SocialProvider.Apple -> "Apple" }
