package com.circaso.feature.contacts.domain

/**
 * Someone on Circaso, as the people screens need them. [displayName] is the name shown: the one I saved for them, else
 * their own; [profileName] is always their own (shown under a saved name, and searched too).
 */
data class Person(val userId: String, val displayName: String, val handle: String?, val avatarUrl: String?, val about: String?, val profileName: String = displayName) {
    /** I saved a name for them. */
    val renamed: Boolean get() = profileName != displayName
}

data class Contact(val person: Person, val source: String, val addedAt: String)

enum class ConnectVia(val apiName: String) { Handle("handle"), Qr("qr"), Invite("invite") }

data class Connection(val person: Person, val created: Boolean)

data class Invite(val token: String, val url: String, val qrPayload: String, val expiresAt: String)

/** What a contact sync found: the phone numbers that belong to people on Circaso. */
data class SyncMatch(val phone: String, val person: Person)

data class SyncSummary(val numbersSent: Int, val matches: List<SyncMatch>)
