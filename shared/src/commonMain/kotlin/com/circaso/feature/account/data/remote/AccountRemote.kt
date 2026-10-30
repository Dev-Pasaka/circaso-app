package com.circaso.feature.account.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NoBody
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

interface AccountRemote {
    suspend fun overview(): AppResult<SecurityOverviewResponse>
    suspend fun linkProvider(request: LinkProviderRequest): AppResult<NoBody>
    suspend fun unlinkProvider(provider: String): AppResult<NoBody>
    suspend fun securityLog(limit: Int): AppResult<SecurityLogResponse>
    suspend fun deleteAccount(): AppResult<NoBody>
    suspend fun pushToken(request: PushTokenRequest, idempotencyKey: String? = null): AppResult<NoBody>
    suspend fun requestRecovery(request: RecoveryRequest): AppResult<RecoveryResponse>
    suspend fun recoveryStatus(requestId: String): AppResult<RecoveryResponse>
}

class KtorAccountRemote(private val api: CircasoApi) : AccountRemote {
    override suspend fun overview() = api.call<SecurityOverviewResponse> {
        method = HttpMethod.Get
        url("/v1/account/security")
    }

    override suspend fun linkProvider(request: LinkProviderRequest) = api.call<NoBody> {
        method = HttpMethod.Post
        url("/v1/account/providers")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun unlinkProvider(provider: String) = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/account/providers/$provider")
    }

    override suspend fun securityLog(limit: Int) = api.call<SecurityLogResponse> {
        method = HttpMethod.Get
        url("/v1/account/security-log")
        parameter("limit", limit)
    }

    override suspend fun deleteAccount() = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/account")
    }

    override suspend fun pushToken(request: PushTokenRequest, idempotencyKey: String?) = api.call<NoBody> {
        method = HttpMethod.Put
        url("/v1/devices/current/push-token")
        idempotencyKey?.let { idempotencyKey(it) }
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    // Recovery uses the onboarding token (the person has signed in with a provider but has no full session).
    override suspend fun requestRecovery(request: RecoveryRequest) = api.call<RecoveryResponse> {
        method = HttpMethod.Post
        url("/v1/recovery/requests")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun recoveryStatus(requestId: String) = api.call<RecoveryResponse> {
        method = HttpMethod.Get
        url("/v1/recovery/requests/$requestId")
    }
}
