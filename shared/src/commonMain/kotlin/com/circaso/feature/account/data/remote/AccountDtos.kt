package com.circaso.feature.account.data.remote

import kotlinx.serialization.Serializable

@Serializable data class ProviderDto(val provider: String, val maskedEmail: String? = null, val linkedAt: String)
@Serializable data class ActiveDeviceDto(val model: String, val activeSince: String)

@Serializable
data class SecurityOverviewResponse(
    val maskedPhone: String? = null,
    val providers: List<ProviderDto>,
    val device: ActiveDeviceDto? = null,
    val verification: String,
)

@Serializable data class LinkProviderRequest(val provider: String, val idToken: String, val nonce: String)

@Serializable
data class SecurityEventDto(val eventId: String, val type: String, val deviceModel: String? = null, val detail: String? = null, val at: String)

@Serializable data class SecurityLogResponse(val events: List<SecurityEventDto>, val nextCursor: String? = null)

@Serializable data class PushTokenRequest(val pushToken: String)

@Serializable data class RecoveryRequest(val phone: String, val contactEmail: String, val note: String? = null)

@Serializable
data class RecoveryResponse(val requestId: String, val status: String, val noticeSentAt: String? = null, val relinkAfter: String? = null)
