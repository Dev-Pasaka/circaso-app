package com.circaso.feature.account.domain

import com.circaso.core.common.AppResult
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult

class ObserveSecurityOverviewUseCase(private val repository: AccountRepository) { operator fun invoke() = repository.overview }
class ObserveSecurityLogUseCase(private val repository: AccountRepository) { operator fun invoke() = repository.securityLog }
class RefreshSecurityOverviewUseCase(private val repository: AccountRepository) { suspend operator fun invoke() = repository.refreshOverview() }
class RefreshSecurityLogUseCase(private val repository: AccountRepository) { suspend operator fun invoke() = repository.refreshSecurityLog() }
class UnlinkProviderUseCase(private val repository: AccountRepository) { suspend operator fun invoke(provider: SocialProvider) = repository.unlinkProvider(provider) }
class DeleteAccountUseCase(private val repository: AccountRepository) { suspend operator fun invoke() = repository.deleteAccount() }
class UpdatePushTokenUseCase(private val repository: AccountRepository) { suspend operator fun invoke(token: String) = repository.updatePushToken(token) }
class RequestRecoveryUseCase(private val repository: AccountRepository) { suspend operator fun invoke(phone: String, contactEmail: String, note: String?) = repository.requestRecovery(phone, contactEmail, note) }
class RecoveryStatusUseCase(private val repository: AccountRepository) { suspend operator fun invoke(requestId: String) = repository.recoveryStatus(requestId) }

/** What linking a login came to, for the screen to act on. */
sealed interface LinkProviderState {
    data object Linked : LinkProviderState
    data object Cancelled : LinkProviderState
    data object Unavailable : LinkProviderState
    data class Failed(val error: com.circaso.core.common.AppError?) : LinkProviderState
}

/** Nonce, platform sign-in, server link: the same three steps as signing in, on an account that already exists. */
class LinkProviderUseCase(
    private val auth: AuthRepository,
    private val account: AccountRepository,
    private val social: SocialSignIn,
) {
    suspend operator fun invoke(provider: SocialProvider): LinkProviderState {
        val nonce = when (val n = auth.requestNonce()) {
            is AppResult.Success -> n.value
            is AppResult.Failure -> return LinkProviderState.Failed(n.error)
        }
        val credential = when (val r = social.signIn(provider, nonce)) {
            is SocialSignInResult.Success -> r.credential
            SocialSignInResult.Cancelled -> return LinkProviderState.Cancelled
            SocialSignInResult.Unavailable -> return LinkProviderState.Unavailable
            SocialSignInResult.Failed -> return LinkProviderState.Failed(null)
        }
        return when (val result = account.linkProvider(provider, credential, nonce)) {
            is AppResult.Success -> LinkProviderState.Linked
            is AppResult.Failure -> LinkProviderState.Failed(result.error)
        }
    }
}
