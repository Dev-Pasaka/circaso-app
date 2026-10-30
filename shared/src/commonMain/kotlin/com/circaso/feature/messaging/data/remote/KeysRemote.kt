package com.circaso.feature.messaging.data.remote

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.call
import com.circaso.feature.messaging.data.keys.SignedKeyUpload
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * The key directory (M1–M3). Uploads take the **exact bytes** that were hashed and signed, plus the proof headers,
 * and send them unchanged and uncompressed (integration guide, section 5, key uploads).
 */
interface KeysRemote {
    suspend fun publish(body: ByteArray, proof: SignedKeyUpload.Proof): AppResult<OneTimePreKeysLeftResponse>
    suspend fun addOneTime(body: ByteArray, proof: SignedKeyUpload.Proof): AppResult<OneTimePreKeysLeftResponse>
    suspend fun status(): AppResult<KeyStatusResponse>
    suspend fun bundle(userId: String): AppResult<KeyBundleResponse>
}

class KtorKeysRemote(private val api: CircasoApi) : KeysRemote {
    override suspend fun publish(body: ByteArray, proof: SignedKeyUpload.Proof) = api.call<OneTimePreKeysLeftResponse> {
        method = HttpMethod.Put
        url("/v1/keys")
        signed(body, proof)
    }

    override suspend fun addOneTime(body: ByteArray, proof: SignedKeyUpload.Proof) = api.call<OneTimePreKeysLeftResponse> {
        method = HttpMethod.Post
        url("/v1/keys/one-time")
        signed(body, proof)
    }

    override suspend fun status() = api.call<KeyStatusResponse> {
        method = HttpMethod.Get
        url("/v1/keys/me")
    }

    override suspend fun bundle(userId: String) = api.call<KeyBundleResponse> {
        method = HttpMethod.Get
        url("/v1/keys/$userId")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.signed(body: ByteArray, proof: SignedKeyUpload.Proof) {
        proof.headers.forEach { (name, value) -> header(name, value) }
        contentType(ContentType.Application.Json)
        setBody(body) // the serialised bytes as hashed; never re-encoded here
    }
}
