package com.circaso.feature.names.data

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

/** The sealed saved-names blob on the server; [version] 0 and no [ciphertext] while there is none. */
@Serializable data class SavedNamesDto(val exists: Boolean = false, val version: Long = 0, val ciphertext: String? = null, val updatedAt: String? = null)

@Serializable data class PutSavedNamesRequest(val ciphertext: String, val baseVersion: Long)

@Serializable data class PutSavedNamesResponse(val version: Long)

/** `GET`/`PUT /v1/key-backup/saved-names`: the server only ever holds the sealed bytes. */
interface SavedNamesRemote {
    suspend fun get(): AppResult<SavedNamesDto>
    suspend fun put(ciphertext: String, baseVersion: Long): AppResult<PutSavedNamesResponse>

    companion object {
        /** Someone else's phone wrote since [put]'s base version: read, merge and write again. */
        const val Conflict = "identity.saved_names_conflict"
        /** Backup was turned off: the names stay on the phone. */
        const val BackupOff = "identity.key_backup_off"
    }
}

class KtorSavedNamesRemote(private val api: CircasoApi) : SavedNamesRemote {
    override suspend fun get() = api.call<SavedNamesDto> {
        method = HttpMethod.Get
        url("/v1/key-backup/saved-names")
    }

    override suspend fun put(ciphertext: String, baseVersion: Long) = api.call<PutSavedNamesResponse> {
        method = HttpMethod.Put
        url("/v1/key-backup/saved-names")
        // The same blob on the same base is the same write: a retry carries the same key.
        idempotencyKey("sn-$baseVersion-${ciphertext.hashCode()}")
        contentType(ContentType.Application.Json)
        setBody(PutSavedNamesRequest(ciphertext, baseVersion))
    }
}
