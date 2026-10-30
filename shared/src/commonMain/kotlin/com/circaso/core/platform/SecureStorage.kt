package com.circaso.core.platform

/**
 * Small secrets (tokens, the install's device id) kept in the platform's secure store: Android Keystore-wrapped
 * encryption, iOS Keychain. Not backed up and not readable from another phone. DataStore is for settings, never for these.
 */
interface SecureStorage {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}
