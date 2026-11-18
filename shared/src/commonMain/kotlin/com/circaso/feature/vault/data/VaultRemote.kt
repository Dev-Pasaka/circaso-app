package com.circaso.feature.vault.data

import com.circaso.core.common.AppResult
import com.circaso.core.network.CircasoApi
import com.circaso.core.network.NoBody
import com.circaso.core.network.WalletNetworkGate
import com.circaso.core.network.walletCall
import com.circaso.core.network.idempotencyKey
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable data class VaultStatusDto(val exists: Boolean, val credentialId: String? = null, val version: Int? = null, val updatedAt: String? = null)

/** The sealed vault as the server keeps it: base64url credential id, base64 salt and ciphertext. */
@Serializable data class VaultDto(
    val credentialId: String,
    val salt: String,
    val ciphertext: String,
    val algorithm: String,
    val version: Int = 0,
    val updatedAt: String? = null,
)

@Serializable data class SaveVaultRequest(
    val credentialId: String,
    val salt: String,
    val ciphertext: String,
    val algorithm: String,
    val expectedVersion: Int? = null,
)

/**
 * The PIN Vault on the server (2026-11-11). `GET /v1/vault` counts as opening it: the server logs it, tells the person,
 * and pauses sending for 24 hours, so it is only called to open the vault; [status] is the cheap check. PUT answers
 * with the status; DELETE with an empty envelope. Errors: `vault.not_found`, `vault.version_conflict`, `vault.*_invalid`,
 * `request.rate_limited` (more than 5 openings an hour). Backend PR #93.
 */
interface VaultRemote {
    suspend fun status(): AppResult<VaultStatusDto>
    suspend fun open(): AppResult<VaultDto>
    suspend fun save(request: SaveVaultRequest, idempotencyKey: String): AppResult<VaultStatusDto>
    suspend fun remove(): AppResult<NoBody>
}

/** The vault is per wallet network (each network's wallet has its own PIN): every call says which. */
class KtorVaultRemote(private val api: CircasoApi, private val network: WalletNetworkGate = WalletNetworkGate.None) : VaultRemote {
    override suspend fun status() = api.walletCall<VaultStatusDto>(network) {
        method = HttpMethod.Get
        url("/v1/vault/status")
    }

    override suspend fun open() = api.walletCall<VaultDto>(network) {
        method = HttpMethod.Get
        url("/v1/vault")
    }

    override suspend fun save(request: SaveVaultRequest, idempotencyKey: String) = api.walletCall<VaultStatusDto>(network) {
        method = HttpMethod.Put
        url("/v1/vault")
        idempotencyKey(idempotencyKey)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    override suspend fun remove() = api.walletCall<NoBody>(network) {
        method = HttpMethod.Delete
        url("/v1/vault")
        idempotencyKey(com.circaso.core.network.newIdempotencyKey())
    }
}
