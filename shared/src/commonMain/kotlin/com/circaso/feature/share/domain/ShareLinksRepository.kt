package com.circaso.feature.share.domain

import com.circaso.core.common.AppResult

/**
 * Share links live on the server only (SH1–SH4): making, listing, opening and turning one off all need a connection,
 * so nothing here is queued or cached. A link is never made optimistically: the share sheet opens with the server's URL.
 */
interface ShareLinksRepository {
    suspend fun create(request: ShareRequest): AppResult<ShareLink>
    suspend fun list(cursor: String? = null): AppResult<ShareLinkPage>
    suspend fun revoke(code: String): AppResult<Unit>
    suspend fun open(code: String): AppResult<SharedLink>
}

data class ShareLinkPage(val links: List<ShareLink>, val nextCursor: String?)

class CreateShareLinkUseCase(private val repository: ShareLinksRepository) {
    suspend operator fun invoke(request: ShareRequest) = repository.create(request)
}

class ListShareLinksUseCase(private val repository: ShareLinksRepository) {
    suspend operator fun invoke(cursor: String? = null) = repository.list(cursor)
}

class RevokeShareLinkUseCase(private val repository: ShareLinksRepository) {
    suspend operator fun invoke(code: String) = repository.revoke(code)
}

class OpenShareLinkUseCase(private val repository: ShareLinksRepository) {
    suspend operator fun invoke(code: String) = repository.open(code)
}
