package com.circaso.feature.account.domain

data class LinkedProvider(val provider: String, val maskedEmail: String?, val linkedAt: String)

data class ActiveDevice(val model: String, val activeSince: String)

data class SecurityOverview(
    val maskedPhone: String?,
    val providers: List<LinkedProvider>,
    val device: ActiveDevice?,
    /** Verification state in the API's words. */
    val verification: String,
)

data class SecurityEvent(val eventId: String, val type: String, val deviceModel: String?, val detail: String?, val at: String)

/** A support case for a lost Google or Apple login. */
data class RecoveryCase(val requestId: String, val status: String, val noticeSentAt: String?, val relinkAfter: String?)
