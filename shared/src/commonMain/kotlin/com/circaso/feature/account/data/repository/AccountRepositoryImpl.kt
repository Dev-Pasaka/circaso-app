package com.circaso.feature.account.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.core.sync.Outbox
import com.circaso.feature.account.data.local.AccountDao
import com.circaso.feature.account.data.local.SecurityEventEntity
import com.circaso.feature.account.data.local.SecurityOverviewEntity
import com.circaso.feature.account.data.remote.AccountRemote
import com.circaso.feature.account.data.remote.LinkProviderRequest
import com.circaso.feature.account.data.remote.RecoveryRequest
import com.circaso.feature.account.data.remote.RecoveryResponse
import com.circaso.feature.account.domain.AccountRepository
import com.circaso.feature.account.domain.ActiveDevice
import com.circaso.feature.account.domain.LinkedProvider
import com.circaso.feature.account.domain.RecoveryCase
import com.circaso.feature.account.domain.SecurityEvent
import com.circaso.feature.account.domain.SecurityOverview
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AccountRepositoryImpl(
    private val remote: AccountRemote,
    private val dao: AccountDao,
    private val outbox: Outbox,
    private val kickOutbox: () -> Unit,
) : AccountRepository {

    override val overview: Flow<SecurityOverview?> = dao.observeOverview().map { it?.toDomain() }
    override val securityLog: Flow<List<SecurityEvent>> = dao.observeEvents().map { rows -> rows.map { SecurityEvent(it.eventId, it.type, it.deviceModel, it.detail, it.at) } }

    override suspend fun refreshOverview(): AppResult<Unit> = when (val result = remote.overview()) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val o = result.value
            dao.upsertOverview(
                SecurityOverviewEntity(
                    maskedPhone = o.maskedPhone,
                    providers = o.providers.joinToString("\n") { "${it.provider}$FieldSeparator${it.maskedEmail.orEmpty()}$FieldSeparator${it.linkedAt}" },
                    deviceModel = o.device?.model,
                    deviceSince = o.device?.activeSince,
                    verification = o.verification,
                ),
            )
            AppResult.Success(Unit)
        }
    }

    override suspend fun refreshSecurityLog(): AppResult<Unit> = when (val result = remote.securityLog(LogPageSize)) {
        is AppResult.Failure -> result
        is AppResult.Success -> {
            val rows = result.value.events.map { SecurityEventEntity(it.eventId, it.type, it.deviceModel, it.detail, it.at) }
            if (rows.isEmpty()) dao.clearEvents() else {
                dao.upsertEvents(rows)
                dao.deleteEventsNotIn(rows.map { it.eventId })
            }
            AppResult.Success(Unit)
        }
    }

    override suspend fun linkProvider(provider: SocialProvider, credential: SocialCredential, nonce: String): AppResult<Unit> {
        val result = remote.linkProvider(LinkProviderRequest(provider.apiName, credential.idToken, nonce)).map { }
        if (result is AppResult.Success) refreshOverview()
        return result
    }

    override suspend fun unlinkProvider(provider: SocialProvider): AppResult<Unit> {
        val result = remote.unlinkProvider(provider.apiName).map { }
        if (result is AppResult.Success) refreshOverview()
        return result
    }

    override suspend fun deleteAccount(): AppResult<Unit> = remote.deleteAccount().map { }

    override suspend fun updatePushToken(token: String) {
        // Only the newest token matters, so a waiting one is replaced rather than sent first.
        outbox.enqueueOrMerge(PushTokenHandler.Type, token) { _, new -> new }
        kickOutbox()
    }

    override suspend fun requestRecovery(phone: String, contactEmail: String, note: String?): AppResult<RecoveryCase> =
        remote.requestRecovery(RecoveryRequest(phone, contactEmail, note)).map { it.toDomain() }

    override suspend fun recoveryStatus(requestId: String): AppResult<RecoveryCase> = remote.recoveryStatus(requestId).map { it.toDomain() }

    private companion object { const val LogPageSize = 50 }
}

/** Separates the parts of a linked login in one stored text field; cannot appear in an address or a date. */
private const val FieldSeparator = "\u001F"

private fun RecoveryResponse.toDomain() = RecoveryCase(requestId, status, noticeSentAt, relinkAfter)

private fun SecurityOverviewEntity.toDomain() = SecurityOverview(
    maskedPhone = maskedPhone,
    providers = providers.lines().filter { it.isNotBlank() }.map {
        val (name, email, at) = it.split(FieldSeparator, limit = 3)
        LinkedProvider(name, email.ifEmpty { null }, at)
    },
    device = if (deviceModel != null && deviceSince != null) ActiveDevice(deviceModel, deviceSince) else null,
    verification = verification,
)
