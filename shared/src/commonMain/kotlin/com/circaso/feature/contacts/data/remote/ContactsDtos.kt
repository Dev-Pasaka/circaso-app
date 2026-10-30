package com.circaso.feature.contacts.data.remote

import kotlinx.serialization.Serializable

// Named like the schemas in the integration guide (section 11). `PublicProfileDto2` there is the same shape as `PublicProfileDto`.

@Serializable
data class PersonDto(val userId: String, val displayName: String, val handle: String? = null, val avatarUrl: String? = null, val about: String? = null)

@Serializable data class SyncRequest(val syncId: String, val batch: Int? = null, val final: Boolean? = null, val phones: List<String>)
@Serializable data class SyncMatchDto(val phone: String, val user: PersonDto)
@Serializable data class SyncResponse(val matches: List<SyncMatchDto>)

@Serializable data class ContactDto(val user: PersonDto, val source: String, val addedAt: String)
@Serializable data class ContactsResponse(val contacts: List<ContactDto>, val nextCursor: String? = null)
@Serializable data class RemovedResponse(val removed: Boolean)

@Serializable data class ConnectRequest(val via: String, val value: String)
@Serializable data class ConnectResponse(val user: PersonDto, val created: Boolean)

@Serializable data class InviteResponse(val token: String, val url: String, val qrPayload: String, val expiresAt: String)

@Serializable data class BlocksResponse(val blocks: List<PersonDto>)
@Serializable data class ReachResponse(val reachable: Boolean)
