package com.circaso.feature.share.data.remote

import kotlinx.serialization.Serializable

// SH1–SH4, tag "Share links" in the API reference. Nulls are left out of what is sent (explicitNulls = false).

@Serializable
data class CreateShareLinkRequest(
    val kind: String,
    val transferId: String? = null,
    val chatId: String? = null,
    val blockchain: String? = null,
    val showNames: Boolean? = null,
    val currency: String? = null,
    val expiresInDays: Int? = null,
)

@Serializable
data class ShareLinkDto(
    val code: String,
    val url: String,
    val kind: String,
    val title: String,
    val summary: String = "",
    val createdAt: String,
    val expiresAt: String? = null,
)

@Serializable data class ShareLinksResponse(val links: List<ShareLinkDto>, val nextCursor: String? = null)

@Serializable data class RevokedResponse(val revoked: Boolean)

@Serializable data class SharedFiatDto(val currency: String, val amount: String)

@Serializable
data class SharedReceiptDto(
    val direction: String,
    val amount: String,
    val asset: String,
    val fiat: SharedFiatDto? = null,
    val status: String,
    val blockchain: String,
    val txHash: String? = null,
    val explorerUrl: String? = null,
    val at: String,
    val fromName: String? = null,
    val toName: String? = null,
)

@Serializable data class StatementFiatDto(val currency: String, val rate: String)

@Serializable
data class StatementLineDto(val at: String, val direction: String, val amount: String, val name: String? = null, val note: String? = null)

@Serializable
data class SharedStatementDto(
    val goalName: String,
    val asset: String = "USDC",
    val target: String,
    val raised: String,
    val paidOut: String,
    val held: String,
    val fiat: StatementFiatDto? = null,
    val memberCount: Int = 0,
    val lines: List<StatementLineDto> = emptyList(),
)

@Serializable data class SharedPersonDto(val displayName: String, val handle: String? = null)

@Serializable data class SharedAddressDto(val blockchain: String, val address: String, val asset: String = "USDC", val name: String? = null)

@Serializable
data class OpenTargetDto(
    val screen: String,
    val transferId: String? = null,
    val chatId: String? = null,
    val userId: String? = null,
    val handle: String? = null,
    val inviteToken: String? = null,
    val displayName: String? = null,
    val address: String? = null,
    val blockchain: String? = null,
)

@Serializable
data class SharedLinkDto(
    val code: String,
    val url: String,
    val kind: String,
    val title: String,
    val summary: String = "",
    val createdAt: String,
    val expiresAt: String? = null,
    val mine: Boolean = false,
    val receipt: SharedReceiptDto? = null,
    val statement: SharedStatementDto? = null,
    val person: SharedPersonDto? = null,
    val address: SharedAddressDto? = null,
    val open: OpenTargetDto? = null,
)
