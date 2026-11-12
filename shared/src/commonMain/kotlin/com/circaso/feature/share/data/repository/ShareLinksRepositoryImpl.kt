package com.circaso.feature.share.data.repository

import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.feature.share.data.remote.CreateShareLinkRequest
import com.circaso.feature.share.data.remote.OpenTargetDto
import com.circaso.feature.share.data.remote.ShareLinkDto
import com.circaso.feature.share.data.remote.ShareLinksRemote
import com.circaso.feature.share.data.remote.SharedLinkDto
import com.circaso.feature.share.domain.OpenTarget
import com.circaso.feature.share.domain.ShareKind
import com.circaso.feature.share.domain.ShareLink
import com.circaso.feature.share.domain.ShareLinkPage
import com.circaso.feature.share.domain.ShareLinksRepository
import com.circaso.feature.share.domain.ShareRequest
import com.circaso.feature.share.domain.SharedAddress
import com.circaso.feature.share.domain.SharedFiat
import com.circaso.feature.share.domain.SharedLink
import com.circaso.feature.share.domain.SharedPerson
import com.circaso.feature.share.domain.SharedReceipt
import com.circaso.feature.share.domain.SharedStatement
import com.circaso.feature.share.domain.SharedStatementLine

class ShareLinksRepositoryImpl(
    private val remote: ShareLinksRemote,
    /** Names I saved for people: a pay link from someone I named reads with my name for them. */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
) : ShareLinksRepository {
    override suspend fun create(request: ShareRequest): AppResult<ShareLink> = remote.create(request.toDto()).map { it.toModel() }

    override suspend fun list(cursor: String?): AppResult<ShareLinkPage> =
        remote.list(cursor, PageSize).map { r -> ShareLinkPage(r.links.map { it.toModel() }, r.nextCursor) }

    override suspend fun revoke(code: String): AppResult<Unit> = remote.revoke(code).map { }

    override suspend fun open(code: String): AppResult<SharedLink> = remote.open(code).map { it.toModel().named(names.saved.value) }

    private companion object {
        const val PageSize = 50
    }
}

/** A pay link names its person by id: the name I saved for them, if any, on the card and the button. */
internal fun SharedLink.named(saved: Map<String, String>): SharedLink {
    val pay = open as? OpenTarget.Pay ?: return this
    val name = pay.userId?.let { saved[it] } ?: return this
    return copy(person = person?.copy(displayName = name), open = pay.copy(displayName = name))
}

internal fun ShareRequest.toDto() = CreateShareLinkRequest(
    kind = kind.apiName,
    transferId = transferId,
    chatId = chatId,
    blockchain = blockchain,
    showNames = showNames,
    currency = currency,
    expiresInDays = expiresInDays,
)

internal fun ShareLinkDto.toModel() = ShareLink(code, url, ShareKind.from(kind), title, summary, createdAt, expiresAt)

internal fun SharedLinkDto.toModel() = SharedLink(
    code = code,
    url = url,
    kind = ShareKind.from(kind),
    title = title,
    summary = summary,
    createdAt = createdAt,
    expiresAt = expiresAt,
    mine = mine,
    receipt = receipt?.let { r ->
        SharedReceipt(
            sent = r.direction == "sent", amount = r.amount, asset = r.asset, fiat = r.fiat?.let { SharedFiat(it.currency, it.amount) },
            status = r.status, blockchain = r.blockchain, txHash = r.txHash, explorerUrl = r.explorerUrl, at = r.at,
            fromName = r.fromName, toName = r.toName,
        )
    },
    statement = statement?.let { s ->
        SharedStatement(
            goalName = s.goalName, asset = s.asset, target = s.target, raised = s.raised, paidOut = s.paidOut, held = s.held,
            fiatCurrency = s.fiat?.currency, fiatRate = s.fiat?.rate, memberCount = s.memberCount,
            lines = s.lines.map { SharedStatementLine(it.at, it.direction == "in", it.amount, it.name, it.note) },
        )
    },
    person = person?.let { SharedPerson(it.displayName, it.handle) },
    address = address?.let { SharedAddress(it.blockchain, it.address, it.asset, it.name) },
    open = open?.toModel(),
)

/** An `open` this app doesn't know, or one missing what it needs, is dropped: the screen then offers nothing to open. */
internal fun OpenTargetDto.toModel(): OpenTarget? = when (screen) {
    "transfer" -> transferId?.let { OpenTarget.Transfer(it) }
    "chama" -> chatId?.let { OpenTarget.Chama(it) }
    "invite" -> inviteToken?.let { OpenTarget.Invite(it, displayName) }
    "pay" -> if (userId != null || handle != null) OpenTarget.Pay(userId, handle, displayName) else null
    "pay_address" -> address?.let { OpenTarget.PayAddress(it, blockchain, displayName) }
    else -> null
}
