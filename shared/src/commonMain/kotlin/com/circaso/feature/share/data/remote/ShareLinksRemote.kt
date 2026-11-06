package com.circaso.feature.share.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/**
 * SH1–SH4. The client adds a fresh `Idempotency-Key` to the POST and the DELETE and keeps it across its own retries
 * (CircasoApi), so a link is made or turned off once however the network behaves.
 */
interface ShareLinksRemote {
    suspend fun create(request: CreateShareLinkRequest): AppResult<ShareLinkDto>
    suspend fun list(cursor: String?, limit: Int): AppResult<ShareLinksResponse>
    suspend fun revoke(code: String): AppResult<RevokedResponse>
    suspend fun open(code: String): AppResult<SharedLinkDto>
}

class KtorShareLinksRemote(private val api: CircasoApi) : ShareLinksRemote {
    override suspend fun create(request: CreateShareLinkRequest) = api.call<ShareLinkDto> {
        method = HttpMethod.Post
        url("/v1/share/links")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun list(cursor: String?, limit: Int) = api.call<ShareLinksResponse> {
        method = HttpMethod.Get
        url("/v1/share/links")
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) }
    }

    override suspend fun revoke(code: String) = api.call<RevokedResponse> {
        method = HttpMethod.Delete
        url("/v1/share/links/${code.encodeURLPathPart()}")
    }

    override suspend fun open(code: String) = api.call<SharedLinkDto> {
        method = HttpMethod.Get
        url("/v1/share/links/${code.encodeURLPathPart()}")
    }
}
