package com.circaso.feature.account

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NoBody
import com.circaso.core.sync.Outbox
import com.circaso.feature.account.data.local.AccountDao
import com.circaso.feature.account.data.local.SecurityEventEntity
import com.circaso.feature.account.data.local.SecurityOverviewEntity
import com.circaso.feature.account.data.remote.AccountRemote
import com.circaso.feature.account.data.remote.ActiveDeviceDto
import com.circaso.feature.account.data.remote.LinkProviderRequest
import com.circaso.feature.account.data.remote.ProviderDto
import com.circaso.feature.account.data.remote.PushTokenRequest
import com.circaso.feature.account.data.remote.RecoveryRequest
import com.circaso.feature.account.data.remote.RecoveryResponse
import com.circaso.feature.account.data.remote.SecurityEventDto
import com.circaso.feature.account.data.remote.SecurityLogResponse
import com.circaso.feature.account.data.remote.SecurityOverviewResponse
import com.circaso.feature.account.data.repository.AccountRepositoryImpl
import com.circaso.feature.account.data.repository.PushTokenHandler
import com.circaso.feature.account.domain.AccountRepository
import com.circaso.feature.account.domain.LinkProviderState
import com.circaso.feature.account.domain.LinkProviderUseCase
import com.circaso.feature.auth.domain.AuthRepository
import com.circaso.feature.auth.domain.PhoneVerification
import com.circaso.feature.auth.domain.SignInOutcome
import com.circaso.feature.auth.domain.SignInStep
import com.circaso.feature.auth.domain.SocialCredential
import com.circaso.feature.auth.domain.SocialProvider
import com.circaso.feature.auth.domain.SocialSignIn
import com.circaso.feature.auth.domain.SocialSignInResult
import com.circaso.testing.FakeOutboxDao
import com.circaso.testing.runSuspending
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class FakeAccountDao : AccountDao {
    val overview = MutableStateFlow<SecurityOverviewEntity?>(null)
    val events = MutableStateFlow<List<SecurityEventEntity>>(emptyList())
    override fun observeOverview(): Flow<SecurityOverviewEntity?> = overview
    override suspend fun upsertOverview(row: SecurityOverviewEntity) { overview.value = row }
    override suspend fun clearOverview() { overview.value = null }
    override fun observeEvents(): Flow<List<SecurityEventEntity>> = events
    override suspend fun upsertEvents(rows: List<SecurityEventEntity>) { events.value = events.value.filter { e -> rows.none { it.eventId == e.eventId } } + rows }
    override suspend fun deleteEventsNotIn(keep: List<String>) { events.value = events.value.filter { it.eventId in keep } }
    override suspend fun clearEvents() { events.value = emptyList() }
}

private class FakeAccountRemote : AccountRemote {
    var overview: AppResult<SecurityOverviewEntityLike> = AppResult.Success(SecurityOverviewEntityLike())
    var link: AppResult<NoBody> = AppResult.Success(NoBody())
    var unlink: AppResult<NoBody> = AppResult.Success(NoBody())
    var delete: AppResult<NoBody> = AppResult.Success(NoBody())
    var log: AppResult<SecurityLogResponse> = AppResult.Success(SecurityLogResponse(listOf(SecurityEventDto("e1", "sign_in", "Pixel 8", null, "2026-10-25T10:00:00Z"), SecurityEventDto("e2", "device_added", null, null, "2026-10-24T10:00:00Z"))))
    val linkRequests = mutableListOf<LinkProviderRequest>()
    val pushTokens = mutableListOf<Pair<String, String?>>()
    val unlinked = mutableListOf<String>()

    override suspend fun overview(): AppResult<SecurityOverviewResponse> = (overview as AppResult.Success).value.let { AppResult.Success(it.toResponse()) }
    override suspend fun linkProvider(request: LinkProviderRequest): AppResult<NoBody> { linkRequests += request; return link }
    override suspend fun unlinkProvider(provider: String): AppResult<NoBody> { unlinked += provider; return unlink }
    override suspend fun securityLog(limit: Int) = log
    override suspend fun deleteAccount() = delete
    override suspend fun pushToken(request: PushTokenRequest, idempotencyKey: String?): AppResult<NoBody> { pushTokens += request.pushToken to idempotencyKey; return AppResult.Success(NoBody()) }
    override suspend fun requestRecovery(request: RecoveryRequest) = AppResult.Success(RecoveryResponse("r1", "OPEN", "2026-10-25T10:00:00Z"))
    override suspend fun recoveryStatus(requestId: String) = AppResult.Success(RecoveryResponse(requestId, "OPEN"))
}

private data class SecurityOverviewEntityLike(val phone: String? = "+254 7•• ••• 678") {
    fun toResponse() = SecurityOverviewResponse(
        phone,
        listOf(ProviderDto("google", "a•••@gmail.com", "2026-10-01T00:00:00Z"), ProviderDto("apple", null, "2026-10-02T00:00:00Z")),
        ActiveDeviceDto("Pixel 8", "2026-10-12T00:00:00Z"),
        "VERIFIED",
    )
}

private class FakeAuth : AuthRepository {
    var nonce: AppResult<String> = AppResult.Success("n-link")
    override suspend fun requestNonce() = nonce
    override suspend fun signInWithSocial(provider: SocialProvider, credential: SocialCredential, nonce: String): AppResult<SignInOutcome> = error("unused")
    override suspend fun startPhoneVerification(phone: String): AppResult<PhoneVerification> = error("unused")
    override suspend fun verifyPhoneCode(verificationId: String, code: String): AppResult<SignInStep> = error("unused")
    override suspend fun verifyPhoneWithCarrier(carrierToken: String): AppResult<SignInStep> = error("unused")
    override suspend fun registerDevice(pushToken: String?): AppResult<Unit> = error("unused")
    override suspend fun signOut() = Unit
    override suspend fun forgetThisPhone(): AppResult<Unit> = error("unused")
}

private class Social(var result: SocialSignInResult) : SocialSignIn {
    var nonceSeen: String? = null
    override suspend fun signIn(provider: SocialProvider, nonce: String): SocialSignInResult { nonceSeen = nonce; return result }
}

class AccountRepositoryTest {
    private val dao = FakeAccountDao()
    private val outboxDao = FakeOutboxDao()
    private val outbox = Outbox(outboxDao) { 1_000L }
    private val remote = FakeAccountRemote()
    private var kicks = 0
    private val repo: AccountRepository = AccountRepositoryImpl(remote, dao, outbox) { kicks++ }

    @Test fun theOverviewIsSavedAndReadBackWithItsLinkedLogins() = runSuspending {
        repo.refreshOverview()
        val o = repo.overview.first()!!
        assertEquals("+254 7•• ••• 678", o.maskedPhone)
        assertEquals(listOf("google", "apple"), o.providers.map { it.provider })
        assertEquals("a•••@gmail.com", o.providers[0].maskedEmail)
        assertNull(o.providers[1].maskedEmail)
        assertEquals("Pixel 8", o.device?.model)
        assertEquals("VERIFIED", o.verification)
    }

    @Test fun theSecurityLogIsSavedNewestFirstAndAFailureKeepsTheOldCopy() = runSuspending {
        repo.refreshSecurityLog()
        assertEquals(listOf("e1", "e2"), repo.securityLog.first().map { it.eventId })
        remote.log = AppResult.Failure(AppError.Network)
        repo.refreshSecurityLog()
        assertEquals(2, repo.securityLog.first().size)
    }

    @Test fun linkingUsesTheNonceTheServerGave() = runSuspending {
        val social = Social(SocialSignInResult.Success(SocialCredential("id-token")))
        val state = LinkProviderUseCase(FakeAuth(), repo, social)(SocialProvider.Apple)
        assertEquals(LinkProviderState.Linked, state)
        assertEquals("n-link", social.nonceSeen)
        assertEquals(LinkProviderRequest("apple", "id-token", "n-link"), remote.linkRequests.single())
    }

    @Test fun linkingCanBeCancelledOrUnavailableWithoutCallingTheServer() = runSuspending {
        assertEquals(LinkProviderState.Cancelled, LinkProviderUseCase(FakeAuth(), repo, Social(SocialSignInResult.Cancelled))(SocialProvider.Google))
        assertEquals(LinkProviderState.Unavailable, LinkProviderUseCase(FakeAuth(), repo, Social(SocialSignInResult.Unavailable))(SocialProvider.Google))
        assertEquals(0, remote.linkRequests.size)
    }

    @Test fun theLastLoginCannotBeUnlinkedAndTheErrorCodeIsPassedOn() = runSuspending {
        remote.unlink = AppResult.Failure(AppError.Api("identity.provider_last", 409, null, null, emptyMap()))
        val result = repo.unlinkProvider(SocialProvider.Google)
        assertEquals("identity.provider_last", ((result as AppResult.Failure).error as AppError.Api).code)
        assertEquals(listOf("google"), remote.unlinked)
    }

    @Test fun deletingTheAccountPassesTheWalletGuardThrough() = runSuspending {
        remote.delete = AppResult.Failure(AppError.Api("identity.wallet_not_empty", 409, null, null, emptyMap()))
        assertEquals("identity.wallet_not_empty", ((repo.deleteAccount() as AppResult.Failure).error as AppError.Api).code)
    }

    @Test fun onlyTheNewestPushTokenIsSent() = runSuspending {
        repo.updatePushToken("old"); repo.updatePushToken("new")
        val item = outboxDao.rows.value.single()
        assertEquals("new", item.payload)
        assertIs<AppResult.Success<Unit>>(PushTokenHandler(remote).send(item))
        assertEquals("new" to item.idempotencyKey, remote.pushTokens.single())
        assertEquals(2, kicks)
    }

    @Test fun recoveryRequestsPassThrough() = runSuspending {
        assertEquals("r1", (repo.requestRecovery("+254712345678", "me@example.com", null) as AppResult.Success).value.requestId)
        assertEquals("OPEN", (repo.recoveryStatus("r1") as AppResult.Success).value.status)
    }
}
