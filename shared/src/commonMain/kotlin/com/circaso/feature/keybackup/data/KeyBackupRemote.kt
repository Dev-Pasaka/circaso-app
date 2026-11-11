package com.circaso.feature.keybackup.data

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NoBody
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import com.circaso.core.network.newIdempotencyKey
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable data class BackupRootDto(
    val exists: Boolean,
    val credentialId: String? = null,
    val salt: String? = null,
    val sealedRoot: String? = null,
    val algorithm: String? = null,
    val version: Int? = null,
    val updatedAt: String? = null,
)

@Serializable data class SaveRootRequest(val credentialId: String, val salt: String, val sealedRoot: String, val algorithm: String, val expectedVersion: Int? = null)

@Serializable data class GroupKeyRequest(val ciphertext: String)

@Serializable data class BackedUpGroupKey(val chatId: String, val metaVersion: Int, val ciphertext: String)

@Serializable data class GroupKeysPage(val items: List<BackedUpGroupKey> = emptyList(), val nextCursor: String? = null)

/** The group key backup on the server (2026-11-11): only sealed bytes; the root is sealed under the person's passkey. */
interface KeyBackupRemote {
    suspend fun root(): AppResult<BackupRootDto>
    suspend fun saveRoot(request: SaveRootRequest): AppResult<BackupRootDto>
    suspend fun putGroupKey(chatId: String, metaVersion: Int, ciphertext: String): AppResult<NoBody>
    suspend fun groupKeys(after: String?, limit: Int): AppResult<GroupKeysPage>
    suspend fun remove(): AppResult<NoBody>
}

class KtorKeyBackupRemote(private val api: CircasoApi) : KeyBackupRemote {
    override suspend fun root() = api.call<BackupRootDto> {
        method = HttpMethod.Get
        url("/v1/key-backup/root")
    }

    override suspend fun saveRoot(request: SaveRootRequest) = api.call<BackupRootDto> {
        method = HttpMethod.Put
        url("/v1/key-backup/root")
        idempotencyKey(newIdempotencyKey())
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun putGroupKey(chatId: String, metaVersion: Int, ciphertext: String) = api.call<NoBody> {
        method = HttpMethod.Put
        url("/v1/key-backup/group-keys/$chatId/$metaVersion")
        // The same blob for the same key: a retry is harmless, so the key is derived from what is sent.
        idempotencyKey("gk-$chatId-$metaVersion-${ciphertext.hashCode()}")
        contentType(ContentType.Application.Json)
        setBody(GroupKeyRequest(ciphertext))
    }

    override suspend fun groupKeys(after: String?, limit: Int) = api.call<GroupKeysPage> {
        method = HttpMethod.Get
        url("/v1/key-backup/group-keys")
        parameter("limit", limit)
        after?.let { parameter("cursor", it) }
    }

    override suspend fun remove() = api.call<NoBody> {
        method = HttpMethod.Delete
        url("/v1/key-backup")
        idempotencyKey(newIdempotencyKey())
    }
}
