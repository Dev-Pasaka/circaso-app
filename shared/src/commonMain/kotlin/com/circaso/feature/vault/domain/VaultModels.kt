package com.circaso.feature.vault.domain

import kotlinx.serialization.Serializable

/** What the PIN Vault keeps: the Circle wallet PIN, and the person's security-question answers if they chose to add them. */
@Serializable
data class VaultContents(val pin: String, val answers: List<SecurityAnswer> = emptyList())

@Serializable
data class SecurityAnswer(val question: String, val answer: String)

/** Whether this account has a vault, from `GET /v1/vault/status`. */
data class VaultStatus(val exists: Boolean, val version: Int?, val updatedAt: String?)
