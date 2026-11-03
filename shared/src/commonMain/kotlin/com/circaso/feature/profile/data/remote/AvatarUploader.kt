package com.circaso.feature.profile.data.remote

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import io.ktor.client.HttpClient
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/** Sends the picture bytes straight to the pre-signed storage URL (step 2 of the avatar flow). */
interface AvatarUploader {
    suspend fun put(url: String, bytes: ByteArray, contentType: String): AppResult<Unit>
}

class KtorAvatarUploader(
    private val plainClient: HttpClient,
    /** The platform's background uploader where there is one (iOS), so the photo outlives the app. */
    private val uploads: com.circaso.core.platform.BackgroundUploads = com.circaso.core.platform.NoBackgroundUploads,
) : AvatarUploader {
    override suspend fun put(url: String, bytes: ByteArray, contentType: String): AppResult<Unit> = try {
        uploads.put(url, mapOf("Content-Type" to contentType), bytes)?.let { ok -> return if (ok) AppResult.Success(Unit) else AppResult.Failure(AppError.Network) }
        val response = plainClient.put(url) {
            contentType(ContentType.parse(contentType))
            setBody(bytes)
        }
        if (response.status.isSuccess()) AppResult.Success(Unit) else AppResult.Failure(AppError.Unexpected(response.status.value))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Failure(AppError.Network)
    }
}
