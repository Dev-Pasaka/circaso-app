package com.circaso.feature.auth.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NoBody
import com.circaso.core.network.call
import com.circaso.core.network.withoutAuth
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/** The auth routes, one function each. Scopes follow the guide: nonce and social need no token; phone and device calls use the onboarding token; sign-out uses the app token. */
interface AuthRemote {
    suspend fun nonce(): AppResult<NonceResponse>
    suspend fun social(request: SocialSignInRequest): AppResult<SignInResponse>
    suspend fun startPhone(request: StartPhoneRequest): AppResult<StartPhoneResponse>
    suspend fun verifyCode(request: VerifyCodeRequest): AppResult<PhoneVerifiedResponse>
    suspend fun carrier(request: CarrierRequest): AppResult<PhoneVerifiedResponse>
    suspend fun registerDevice(request: RegisterDeviceRequest): AppResult<RegisterDeviceResponse>
    suspend fun signOut(): AppResult<NoBody>
    suspend fun forgetCurrentDevice(): AppResult<NoBody>
}

class KtorAuthRemote(private val api: CircasoApi) : AuthRemote {
    override suspend fun nonce() = api.call<NonceResponse> {
        method = HttpMethod.Post
        url("/v1/auth/nonce")
        withoutAuth()
    }

    override suspend fun social(request: SocialSignInRequest) = api.call<SignInResponse> {
        method = HttpMethod.Post
        url("/v1/auth/social")
        withoutAuth()
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun startPhone(request: StartPhoneRequest) = api.call<StartPhoneResponse> {
        method = HttpMethod.Post
        url("/v1/auth/phone/start")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun verifyCode(request: VerifyCodeRequest) = api.call<PhoneVerifiedResponse> {
        method = HttpMethod.Post
        url("/v1/auth/phone/verify")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun carrier(request: CarrierRequest) = api.call<PhoneVerifiedResponse> {
        method = HttpMethod.Post
        url("/v1/auth/phone/carrier")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun registerDevice(request: RegisterDeviceRequest) = api.call<RegisterDeviceResponse> {
        method = HttpMethod.Post
        url("/v1/devices")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun signOut() = api.call<NoBody> {
        method = HttpMethod.Post
        url("/v1/auth/signout")
    }

    override suspend fun forgetCurrentDevice() = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/devices/current")
    }
}
