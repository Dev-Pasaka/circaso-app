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
    @Volatile private var lastTransports: List<String>? = null
    @Volatile private var justCreatedAt: Long? = null

    override suspend fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray): PrfResult {
        val activity = visibleActivity() ?: return PrfResult.Failed("no-screen").also { Log.e(Tag, "passkey: no visible screen") }
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
            // Ask for the PRF secret while creating: one fingerprint, no second sheet. A provider that can't evaluate
            // at creation returns no result, and the vault then asks with a second sheet.
            putJsonObject("extensions") { putJsonObject("prf") { putJsonObject("eval") { put("first", b64(prfSalt)) } } }
        }.toString()
        return try {
            Log.i(Tag, "passkey create: start (rp=${PasskeyPrf.RelyingParty})")
            val response = CredentialManager.create(activity).createCredential(activity, CreatePublicKeyCredentialRequest(json))
            val body = (response as? CreatePublicKeyCredentialResponse)?.registrationResponseJson
                ?: return PrfResult.Failed("no-response").also { Log.e(Tag, "passkey create: unexpected response ${response::class.simpleName}") }
            val parsed = Json.parseToJsonElement(body).jsonObject
            val id = parsed["rawId"]?.jsonPrimitive?.contentOrNull ?: parsed["id"]?.jsonPrimitive?.contentOrNull
                ?: return PrfResult.Failed("no-id").also { Log.e(Tag, "passkey create: no credential id") }
            val prf = parsed.prf()
            val attestation = parsed["response"] as? JsonObject
            lastTransports = (attestation?.get("transports") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
            Log.i(Tag, "passkey create: done, prf=${prf?.get("enabled")} secret=${prf.first() != null} id=${id.take(10)}… attachment=${parsed["authenticatorAttachment"]} transports=$lastTransports")
            justCreatedAt = System.currentTimeMillis()
            if (prf?.get("enabled")?.jsonPrimitive?.contentOrNull == "false") return PrfResult.Unsupported
            PrfResult.Success(unb64(id), prf.first())
        } catch (e: CreateCredentialCancellationException) {
            Log.i(Tag, "passkey create: cancelled")
            PrfResult.Cancelled
        } catch (e: CreateCredentialException) {
            Log.e(Tag, "passkey create failed: ${e.type}: ${e.errorMessage}", e)
            PrfResult.Failed(e.describe())
        } catch (e: Exception) {
            Log.e(Tag, "passkey create crashed", e)
            PrfResult.Failed(e::class.simpleName ?: "error")
        }
    }

    override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray): PrfResult {
        val activity = visibleActivity() ?: return PrfResult.Failed("no-screen").also { Log.e(Tag, "passkey: no visible screen") }
        val json = buildJsonObject {
            put("challenge", b64(bytes(32)))
            put("rpId", PasskeyPrf.RelyingParty)
            put("timeout", 120_000)
            put("userVerification", "required")
            putJsonArray("allowCredentials") {
                add(buildJsonObject {
                    put("type", "public-key"); put("id", b64(credentialId))
                    // Kept on this phone (and synced by the password manager): look here first, not on another device.
                    putJsonArray("transports") { (lastTransports?.takeIf { it.isNotEmpty() } ?: listOf("internal", "hybrid")).forEach { add(it) } }
                })
            }
            putJsonObject("extensions") { putJsonObject("prf") { putJsonObject("eval") { put("first", b64(prfSalt)) } } }
        }.toString()
        // A passkey made a moment ago can take a beat to be usable in Google Password Manager.
        justCreatedAt?.let { val wait = 3_000 - (System.currentTimeMillis() - it); if (wait > 0) kotlinx.coroutines.delay(wait) }
        justCreatedAt = null
        return try {
            Log.i(Tag, "passkey use: start id=${b64(credentialId).take(10)}… (transports=${lastTransports ?: "default"})")
            val result = CredentialManager.create(activity).getCredential(activity, GetCredentialRequest(listOf(GetPublicKeyCredentialOption(json))))
            val body = (result.credential as? PublicKeyCredential)?.authenticationResponseJson
                ?: return PrfResult.Failed("no-response").also { Log.e(Tag, "passkey use: unexpected credential ${result.credential.type}") }
            val prf = Json.parseToJsonElement(body).jsonObject.prf()
            val secret = prf.first() ?: return PrfResult.Unsupported.also { Log.e(Tag, "passkey use: no PRF result (${prf?.keys})") }
            Log.i(Tag, "passkey use: done")
            PrfResult.Success(credentialId, secret)
        } catch (e: GetCredentialCancellationException) {
            Log.i(Tag, "passkey use: cancelled")
            PrfResult.Cancelled
        } catch (e: NoCredentialException) {
            Log.e(Tag, "passkey use: no passkey", e)
            PrfResult.Failed("no-passkey")
        } catch (e: GetCredentialException) {
            Log.e(Tag, "passkey use failed: ${e.type}: ${e.errorMessage}", e)
            PrfResult.Failed(e.describe())
        } catch (e: Exception) {
            Log.e(Tag, "passkey use crashed", e)
            PrfResult.Failed(e::class.simpleName ?: "error")
        }
    }

    /** The provider's own words after its type, e.g. "…DATA_ERROR: The incoming request cannot be validated". */
    private fun androidx.credentials.exceptions.CreateCredentialException.describe() =
        type.substringAfterLast('.') + (errorMessage?.let { ": $it" } ?: "")
    private fun androidx.credentials.exceptions.GetCredentialException.describe() =
        type.substringAfterLast('.') + (errorMessage?.let { ": $it" } ?: "")

    /**
     * Circaso's screen, waiting a moment for it: straight after one passkey sheet closes (create, then use), Circaso's
     * activity isn't resumed yet, and the next sheet needs it.
     */
    private suspend fun visibleActivity(): android.app.Activity? =
        activities.get() ?: kotlinx.coroutines.withTimeoutOrNull(3_000) {
            var found = activities.get()
            while (found == null) { kotlinx.coroutines.delay(50); found = activities.get() }
            found
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
