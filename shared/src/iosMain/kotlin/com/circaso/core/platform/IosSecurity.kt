@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSProcessInfo
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyRef
import platform.Security.SecKeyVerifySignature
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrService
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecReturnData
import platform.Security.kSecReturnRef
import platform.Security.kSecValueData
import platform.posix.memcpy

// ---- small Core Foundation helpers. Every Create/Copy result is released by its owner (the "create rule").

private fun ByteArray.toCFData(): CFDataRef? = usePinned {
    CFDataCreate(null, if (isEmpty()) null else it.addressOf(0).reinterpret(), size.convert())
}

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val out = ByteArray(length)
    if (length > 0) out.usePinned { memcpy(it.addressOf(0), CFDataGetBytePtr(this), length.convert()) }
    return out
}

private fun String.toCFString(): CFTypeRef? = CFBridgingRetain(this)

private fun dictionaryOf(vararg entries: Pair<CFTypeRef?, CFTypeRef?>): CFDictionaryRef? {
    val dict = CFDictionaryCreateMutable(null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
    entries.forEach { (key, value) -> CFDictionaryAddValue(dict, key, value) }
    return dict
}

private fun release(vararg items: CFTypeRef?) = items.forEach { if (it != null) CFRelease(it) }

private class KeychainFailure(message: String) : Exception(message)

/** Tokens and the device id, in the Keychain. Readable after the first unlock (background refresh), never backed up, never moved to another phone. */
class IosSecureStorage : SecureStorage {
    private fun baseQuery(key: String, extra: List<Pair<CFTypeRef?, CFTypeRef?>> = emptyList()): Triple<CFDictionaryRef?, CFTypeRef?, CFTypeRef?> {
        val service = Service.toCFString()
        val account = key.toCFString()
        val entries = listOf<Pair<CFTypeRef?, CFTypeRef?>>(kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to account) + extra
        return Triple(dictionaryOf(*entries.toTypedArray()), service, account)
    }

    override suspend fun get(key: String): String? = withContext(Dispatchers.Default) {
        memScoped {
            val (query, service, account) = baseQuery(key, listOf(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne))
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            release(query, service, account)
            if (status != errSecSuccess) return@memScoped null
            val data = result.value as CFDataRef
            try { data.toByteArray().decodeToString() } finally { CFRelease(data) }
        }
    }

    override suspend fun put(key: String, value: String): Unit = withContext(Dispatchers.Default) {
        remove(key)
        val data = value.encodeToByteArray().toCFData()
        val (item, service, account) = baseQuery(
            key,
            listOf(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly),
        )
        val status = SecItemAdd(item, null)
        release(item, data, service, account)
        if (status != errSecSuccess) throw KeychainFailure("could not write to the Keychain (status $status)")
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.Default) {
        val (query, service, account) = baseQuery(key)
        SecItemDelete(query)
        release(query, service, account)
    }

    private companion object {
        const val Service = "com.circaso.secure"
    }
}

/**
 * ECDSA P-256 in the Secure Enclave. The simulator has no Secure Enclave, so only there a software key
 * is used; on a real phone a failure to create the hardware key is an error, never a silent downgrade.
 */
class IosDeviceKeyStore : DeviceKeyStore {
    private val tag = "com.circaso.device.key".encodeToByteArray()
    private val onSimulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] != null

    private fun tagQuery(extra: List<Pair<CFTypeRef?, CFTypeRef?>>): Pair<CFDictionaryRef?, CFDataRef?> {
        val tagData = tag.toCFData()
        val entries = listOf<Pair<CFTypeRef?, CFTypeRef?>>(
            kSecClass to kSecClassKey,
            kSecAttrApplicationTag to tagData,
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
        ) + extra
        return dictionaryOf(*entries.toTypedArray()) to tagData
    }

    /** The private key, or null. The caller releases it. */
    private fun findPrivateKey(): SecKeyRef? = memScoped {
        val (query, tagData) = tagQuery(listOf(kSecReturnRef to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne))
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        release(query, tagData)
        if (status == errSecSuccess) result.value as SecKeyRef else null
    }

    private fun createPrivateKey(): SecKeyRef = memScoped {
        val tagData = tag.toCFData()
        val access = if (onSimulator) null else SecAccessControlCreateWithFlags(
            null, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, kSecAccessControlPrivateKeyUsage, null,
        )
        val privateAttrs = if (access != null) {
            dictionaryOf(kSecAttrIsPermanent to kCFBooleanTrue, kSecAttrApplicationTag to tagData, kSecAttrAccessControl to access)
        } else {
            dictionaryOf(kSecAttrIsPermanent to kCFBooleanTrue, kSecAttrApplicationTag to tagData, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        }
        val bitsValue = alloc<kotlinx.cinterop.IntVar>().also { it.value = 256 }
        val bits = CFNumberCreate(null, kCFNumberIntType, bitsValue.ptr)
        val entries = mutableListOf<Pair<CFTypeRef?, CFTypeRef?>>(
            kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits to bits,
            kSecPrivateKeyAttrs to privateAttrs,
        )
        if (!onSimulator) entries += kSecAttrTokenID to kSecAttrTokenIDSecureEnclave
        val attributes = dictionaryOf(*entries.toTypedArray())
        val error = alloc<CFErrorRefVar>()
        val key = SecKeyCreateRandomKey(attributes, error.ptr)
        release(attributes, privateAttrs, access, bits, tagData)
        key ?: throw DeviceKeyException("could not create the device key" + if (onSimulator) " (simulator)" else " in the Secure Enclave")
    }

    override suspend fun hasKey(): Boolean = withContext(Dispatchers.Default) {
        findPrivateKey()?.also { CFRelease(it) } != null
    }

    override suspend fun getOrCreatePublicKey(): String = withContext(Dispatchers.Default) {
        val priv = findPrivateKey() ?: createPrivateKey()
        val pub = SecKeyCopyPublicKey(priv)
        try {
            val raw = memScoped {
                val error = alloc<CFErrorRefVar>()
                SecKeyCopyExternalRepresentation(pub, error.ptr) ?: throw DeviceKeyException("could not read the public key")
            }
            try {
                // iOS returns the bare 65-byte point; the server wants the 91-byte SubjectPublicKeyInfo.
                DeviceKeyFormats.base64(DeviceKeyFormats.spkiFromRawPoint(raw.toByteArray()))
            } finally { CFRelease(raw) }
        } finally {
            release(pub, priv)
        }
    }

    override suspend fun sign(payload: String): String = withContext(Dispatchers.Default) {
        val priv = findPrivateKey() ?: throw DeviceKeyException("no device key on this phone")
        val data = payload.encodeToByteArray().toCFData()
        try {
            memScoped {
                val error = alloc<CFErrorRefVar>()
                // X962 output is DER, which is what the server wants.
                val signature = SecKeyCreateSignature(priv, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, data, error.ptr)
                    ?: throw DeviceKeyException("could not sign")
                try { DeviceKeyFormats.base64(signature.toByteArray()) } finally { CFRelease(signature) }
            }
        } finally {
            release(data, priv)
        }
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.Default) {
        val (query, tagData) = tagQuery(emptyList())
        SecItemDelete(query)
        release(query, tagData)
    }
}

actual fun verifyEs256(publicKeySpkiBase64: String, payload: String, signatureBase64: String): Boolean = memScoped {
    val rawPoint = DeviceKeyFormats.rawPointFromSpki(DeviceKeyFormats.fromBase64(publicKeySpkiBase64)).toCFData()
    val attributes = dictionaryOf(kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeyClass to kSecAttrKeyClassPublic)
    val error = alloc<CFErrorRefVar>()
    val publicKey = SecKeyCreateWithData(rawPoint, attributes, error.ptr)
    release(rawPoint, attributes)
    if (publicKey == null) return@memScoped false
    val data = payload.encodeToByteArray().toCFData()
    val signature = DeviceKeyFormats.fromBase64(signatureBase64).toCFData()
    val ok = SecKeyVerifySignature(publicKey, kSecKeyAlgorithmECDSASignatureMessageX962SHA256, data, signature, error.ptr)
    release(data, signature, publicKey)
    ok
}
