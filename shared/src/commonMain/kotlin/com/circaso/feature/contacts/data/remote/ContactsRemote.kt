package com.circaso.feature.contacts.data.remote

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

interface ContactsRemote {
    suspend fun sync(request: SyncRequest): AppResult<SyncResponse>
    suspend fun contacts(cursor: String?, limit: Int): AppResult<ContactsResponse>
    suspend fun removeContact(userId: String, idempotencyKey: String? = null): AppResult<RemovedResponse>
    suspend fun connect(request: ConnectRequest): AppResult<ConnectResponse>
    suspend fun createInvite(): AppResult<InviteResponse>
    suspend fun revokeInvite(token: String): AppResult<NoBody>
    suspend fun blocks(): AppResult<BlocksResponse>
    suspend fun block(userId: String, idempotencyKey: String? = null): AppResult<NoBody>
    suspend fun unblock(userId: String, idempotencyKey: String? = null): AppResult<NoBody>
    suspend fun reach(userId: String): AppResult<ReachResponse>
}

class KtorContactsRemote(private val api: CircasoApi) : ContactsRemote {
    override suspend fun sync(request: SyncRequest) = api.call<SyncResponse> {
        method = HttpMethod.Post
        url("/v1/contacts/sync")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun contacts(cursor: String?, limit: Int) = api.call<ContactsResponse> {
        method = HttpMethod.Get
        url("/v1/contacts")
        parameter("limit", limit)
        cursor?.let { parameter("cursor", it) } // the cursor is opaque: never built, only passed back
    }

    override suspend fun removeContact(userId: String, idempotencyKey: String?) = api.call<RemovedResponse> {
        method = HttpMethod.Delete
        url("/v1/contacts/$userId")
        idempotencyKey?.let { idempotencyKey(it) }
    }

    override suspend fun connect(request: ConnectRequest) = api.call<ConnectResponse> {
        method = HttpMethod.Post
        url("/v1/connections")
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun createInvite() = api.call<InviteResponse> {
        method = HttpMethod.Post
        url("/v1/invites")
    }

    override suspend fun revokeInvite(token: String) = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/invites/$token")
    }

    override suspend fun blocks() = api.call<BlocksResponse> {
        method = HttpMethod.Get
        url("/v1/blocks")
    }

    override suspend fun block(userId: String, idempotencyKey: String?) = api.call<NoBody> {
        method = HttpMethod.Put
        url("/v1/blocks/$userId")
        idempotencyKey?.let { idempotencyKey(it) }
    }

    override suspend fun unblock(userId: String, idempotencyKey: String?) = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/blocks/$userId")
        idempotencyKey?.let { idempotencyKey(it) }
    }

    override suspend fun reach(userId: String) = api.call<ReachResponse> {
        method = HttpMethod.Get
        url("/v1/me/reach/$userId")
    }
}
