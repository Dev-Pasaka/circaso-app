package com.circaso.feature.account.domain

import com.circaso.core.common.AppResult
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import kotlinx.coroutines.flow.Flow

/**
 * Account and security. Overview and the security log are read from the phone and refreshed in the background. The
 * push token is a queued write. Linking logins, deleting the account and the recovery request need the server.
 */
interface AccountRepository {
    val overview: Flow<SecurityOverview?>
    val securityLog: Flow<List<SecurityEvent>>

    suspend fun refreshOverview(): AppResult<Unit>

    /** Loads the newest page of the security log and replaces the saved copy. */
    suspend fun refreshSecurityLog(): AppResult<Unit>

    suspend fun linkProvider(provider: SocialProvider, credential: SocialCredential, nonce: String): AppResult<Unit>
    suspend fun unlinkProvider(provider: SocialProvider): AppResult<Unit>

    /** Schedules deletion. What finishes it and how long it stays recoverable is not decided on the backend yet. */
    suspend fun deleteAccount(): AppResult<Unit>

    /** Saves the newest push token to send when online; only the latest matters. */
    suspend fun updatePushToken(token: String)

    suspend fun requestRecovery(phone: String, contactEmail: String, note: String?): AppResult<RecoveryCase>
    suspend fun recoveryStatus(requestId: String): AppResult<RecoveryCase>
}
