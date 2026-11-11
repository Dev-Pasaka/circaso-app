package com.circaso.core.platform

import android.os.Build
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * PRF passkeys through Credential Manager (2026-11-11), for the PIN Vault. The passkey lives in the person's password
 * manager (Google Password Manager by default), which syncs it to their other Android phones. The WebAuthn JSON asks for
 * the `prf` extension with `eval.first` = the vault's salt; the secret comes back in `clientExtensionResults.prf.results`.
 * Nothing here is checked by our server: the secret itself is the proof (the vault opens only with it), so the challenge
 * is random and local.
 */
@OptIn(ExperimentalEncodingApi::class)
class AndroidPasskeyPrf(private val activities: CurrentActivity) : PasskeyPrf {
    private val random = SecureRandom()

    override suspend fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray): PrfResult {
        val activity = activities.get() ?: return PrfResult.Failed("no-screen")
        val json = buildJsonObject {
            put("challenge", b64(bytes(32)))
            putJsonObject("rp") { put("id", PasskeyPrf.RelyingParty); put("name", PasskeyPrf.RelyingPartyName) }
            putJsonObject("user") { put("id", b64(userId.encodeToByteArray())); put("name", userName); put("displayName", displayName) }
            putJsonArray("pubKeyCredParams") {
                add(buildJsonObject { put("type", "public-key"); put("alg", -7) })
                add(buildJsonObject { put("type", "public-key"); put("alg", -257) })
            }
            put("timeout", 120_000)
            put("attestation", "none")
            putJsonObject("authenticatorSelection") {
                put("residentKey", "required"); put("requireResidentKey", true); put("userVerification", "required")
            }
            putJsonObject("extensions") { putJsonObject("prf") { putJsonObject("eval") { put("first", b64(prfSalt)) } } }
        }.toString()
        return try {
            val response = CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(json))
            val body = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson ?: return PrfResult.Failed("no-response")
            val parsed = Json.parseToJsonElement(body).jsonObject
            val id = parsed["rawId"]?.jsonPrimitive?.contentOrNull ?: parsed["id"]?.jsonPrimitive?.contentOrNull ?: return PrfResult.Failed("no-id")
            val prf = parsed.prf()
            if (prf?.get("enabled")?.jsonPrimitive?.contentOrNull == "false") return PrfResult.Unsupported
            PrfResult.Success(unb64(id), prf.first())
        } catch (e: CreateCredentialCancellationException) {
            PrfResult.Cancelled
        } catch (e: CreateCredentialException) {
            Log.w(Tag, "passkey create failed: ${e.type}")
            PrfResult.Failed(e.type)
        }
    }

    override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray): PrfResult {
        val activity = activities.get() ?: return PrfResult.Failed("no-screen")
        val json = buildJsonObject {
            put("challenge", b64(bytes(32)))
            put("rpId", PasskeyPrf.RelyingParty)
            put("timeout", 120_000)
            put("userVerification", "required")
            putJsonArray("allowCredentials") { add(buildJsonObject { put("type", "public-key"); put("id", b64(credentialId)) }) }
            putJsonObject("extensions") { putJsonObject("prf") { putJsonObject("eval") { put("first", b64(prfSalt)) } } }
        }.toString()
        return try {
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(json))))
            val body = (result.credential as? PublicKeyCredential)?.authenticationResponseJson ?: return PrfResult.Failed("no-response")
            val secret = Json.parseToJsonElement(body).jsonObject.prf().first() ?: return PrfResult.Unsupported
            PrfResult.Success(credentialId, secret)
        } catch (e: GetCredentialCancellationException) {
            PrfResult.Cancelled
        } catch (e: NoCredentialException) {
            PrfResult.Failed("no-passkey")
        } catch (e: GetCredentialException) {
            Log.w(Tag, "passkey use failed: ${e.type}")
            PrfResult.Failed(e.type)
        }
    }

    private fun JsonObject.prf(): JsonObject? =
        (this["clientExtensionResults"] as? JsonObject)?.get("prf") as? JsonObject

    private fun JsonObject?.first(): ByteArray? =
        ((this?.get("results") as? JsonObject)?.get("first"))?.jsonPrimitive?.contentOrNull?.let(::unb64)

    private fun bytes(n: Int) = ByteArray(n).also(random::nextBytes)
    private fun b64(bytes: ByteArray) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    private fun unb64(value: String) = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(value)

    private companion object { const val Tag = "CircasoPasskey" }
}
