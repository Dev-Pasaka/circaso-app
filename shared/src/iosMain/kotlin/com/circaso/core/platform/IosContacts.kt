@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.circaso.core.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Contacts.CNAuthorizationStatusAuthorized
import platform.Contacts.CNContact
import platform.Contacts.CNContactFetchRequest
import platform.Contacts.CNContactPhoneNumbersKey
import platform.Contacts.CNContactStore
import platform.Contacts.CNEntityType
import platform.Contacts.CNLabeledValue
import platform.Contacts.CNPhoneNumber
import kotlin.coroutines.resume

/** Reads only the phone-number field of each contact (the fetch asks for nothing else), so names are never loaded. */
class IosContactsReader : ContactsReader {
    private val store = CNContactStore()

    override suspend fun hasAccess() =
        CNContactStore.authorizationStatusForEntityType(CNEntityType.CNEntityTypeContacts) == CNAuthorizationStatusAuthorized

    override suspend fun requestAccess(): Boolean = hasAccess() || suspendCancellableCoroutine { continuation ->
        store.requestAccessForEntityType(CNEntityType.CNEntityTypeContacts) { granted, _ -> if (continuation.isActive) continuation.resume(granted) }
    }

    /** iOS posts this whenever the phone book changes, in Contacts or any other app. */
    override fun changes(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
        val center = platform.Foundation.NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(platform.Contacts.CNContactStoreDidChangeNotification, null, null) { _ -> trySend(Unit) }
        awaitClose { center.removeObserver(token) }
    }

    override suspend fun readNumbers(): List<String> {
        if (!hasAccess()) return emptyList()
        val numbers = mutableListOf<String>()
        val request = CNContactFetchRequest(keysToFetch = listOf(CNContactPhoneNumbersKey))
        store.enumerateContactsWithFetchRequest(request, error = null) { contact: CNContact?, _ ->
            contact?.phoneNumbers?.forEach { value ->
                ((value as? CNLabeledValue)?.value as? CNPhoneNumber)?.stringValue?.let(numbers::add)
            }
        }
        return numbers
    }
}
