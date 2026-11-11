package com.circaso.core.platform

import com.circaso.core.crypto.toByteArray
import com.circaso.core.crypto.toNSData
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import kotlin.coroutines.resume

/**
 * PRF passkeys on iOS (2026-11-11) live on the Swift side (`PasskeyImpl.swift`: AuthenticationServices, iOS 18+), which
 * installs this at launch. Bytes cross as `NSData`. [PasskeyPrfProvider.create] and [PasskeyPrfProvider.secret] call
 * back once with the credential id and secret (the secret may be null after a create), or with null and why:
 * `"cancelled"`, `"unsupported"` or another reason.
 */
interface PasskeyPrfProvider {
    fun isSupported(): Boolean
    fun create(userId: String, userName: String, displayName: String, relyingParty: String, prfSalt: NSData, completion: (NSData?, NSData?, String?) -> Unit)
    fun secret(credentialId: NSData, relyingParty: String, prfSalt: NSData, completion: (NSData?, NSData?, String?) -> Unit)
}

object PasskeyBridge {
    var provider: PasskeyPrfProvider? = null
}

class IosPasskeyPrf : PasskeyPrf {
    override suspend fun isSupported() = PasskeyBridge.provider?.isSupported() == true

    override suspend fun create(userId: String, userName: String, displayName: String, prfSalt: ByteArray): PrfResult {
        val provider = PasskeyBridge.provider ?: return PrfResult.Unsupported
        return suspendCancellableCoroutine { cont ->
            provider.create(userId, userName, displayName, PasskeyPrf.RelyingParty, prfSalt.toNSData()) { id, secret, reason ->
                if (cont.isActive) cont.resume(result(id, secret, reason))
            }
        }
    }

    override suspend fun secret(credentialId: ByteArray, prfSalt: ByteArray): PrfResult {
        val provider = PasskeyBridge.provider ?: return PrfResult.Unsupported
        return suspendCancellableCoroutine { cont ->
            provider.secret(credentialId.toNSData(), PasskeyPrf.RelyingParty, prfSalt.toNSData()) { id, secret, reason ->
                if (cont.isActive) cont.resume(result(id ?: credentialId.toNSData(), secret, reason))
            }
        }
    }

    private fun result(id: NSData?, secret: NSData?, reason: String?): PrfResult = when {
        id != null && reason == null -> PrfResult.Success(id.toByteArray(), secret?.toByteArray())
        reason == "cancelled" -> PrfResult.Cancelled
        reason == "unsupported" -> PrfResult.Unsupported
        else -> PrfResult.Failed(reason ?: "failed")
    }
}
