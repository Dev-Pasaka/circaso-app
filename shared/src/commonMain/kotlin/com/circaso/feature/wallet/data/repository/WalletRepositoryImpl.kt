package com.circaso.feature.wallet.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.Diagnostics
import com.circaso.core.common.map
import com.circaso.core.network.newIdempotencyKey
import com.circaso.core.sync.nowMillis
import com.circaso.feature.wallet.data.local.TransferEntity
import com.circaso.feature.wallet.data.local.WalletDao
import com.circaso.feature.wallet.data.local.WalletSnapshotEntity
import com.circaso.feature.wallet.data.remote.PayeeRequest
import com.circaso.feature.wallet.data.remote.PrepareTransferRequest
import com.circaso.feature.wallet.data.remote.AmountRequest
import com.circaso.feature.wallet.data.remote.SdkDto
import com.circaso.feature.wallet.data.remote.TransferResponse
import com.circaso.feature.wallet.data.remote.ValidateAddressRequest
import com.circaso.feature.wallet.data.remote.WalletRemote
import com.circaso.feature.wallet.data.remote.WalletResponse
import com.circaso.feature.wallet.domain.Amount
import com.circaso.feature.wallet.domain.Asset
import com.circaso.feature.wallet.domain.AssetTotal
import com.circaso.feature.wallet.domain.ChainOption
import com.circaso.feature.wallet.domain.ChainStatus
import com.circaso.feature.wallet.domain.Chains
import com.circaso.feature.wallet.domain.FiatValue
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.Recipient
import com.circaso.feature.wallet.domain.RecipientStatus
import com.circaso.feature.wallet.domain.RemindOutcome
import com.circaso.feature.wallet.domain.SdkSession
import com.circaso.feature.wallet.domain.SignOutcome
import com.circaso.feature.wallet.domain.Transfer
import com.circaso.feature.wallet.domain.TransferDirection
import com.circaso.feature.wallet.domain.TransferState
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletChain
import com.circaso.feature.wallet.domain.WalletRepository
import com.circaso.feature.wallet.domain.WalletSigner
import com.circaso.feature.wallet.domain.WalletStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.circaso.feature.wallet.data.remote.TransactionDto
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WalletRepositoryImpl(
    private val remote: WalletRemote,
    private val dao: WalletDao,
    private val signer: () -> WalletSigner,
    /** The person's chosen currency, else their phone's region's (read at each refresh, so a change applies at once). */
    private val currency: suspend () -> String?,
    private val json: Json,
    scope: kotlinx.coroutines.CoroutineScope? = null,
    onBiometricsLost: suspend () -> Unit = {},
    /** Money arrived while the app is open (the frame, not a push): the coins sound, if the person has it on. */
    private val onMoneyReceived: suspend () -> Unit = {},
) : WalletRepository {
    init { scope?.launch { com.circaso.feature.wallet.domain.WalletSignerBridge.biometricsLost.collect { onBiometricsLost() } } }

    override val wallet: Flow<Wallet?> = dao.observeSnapshot().map { row -> row?.let { decode(it) } }
    override val transfers: Flow<List<Transfer>> = dao.observeTransfers().map { rows -> rows.map { it.toDomain() } }
    override fun transfer(transferId: String): Flow<Transfer?> = dao.observeTransfer(transferId).map { it?.toDomain() }

    /** Who asked this person to set up a wallet (`setup.requested`), for the tab's nudge; cleared once a wallet exists. */
    private val _setupRequestedBy = MutableStateFlow<String?>(null)
    val setupRequestedBy: StateFlow<String?> = _setupRequestedBy.asStateFlow()

    /** Flipped by `wallet.ready`, so a create or add-chain stops polling at once. */
    private val ready = MutableStateFlow(0)

    private var createKey: String? = null
    private val addChainKeys = mutableMapOf<String, String>()

    override suspend fun refresh(): AppResult<Wallet> = remote.wallet(currency()).map { save(it) }.also { if (it is AppResult.Success) syncHistory() }

    /**
     * W6: the latest page of money in and out, so a new phone (or a fresh install) has its Recent list and a payment
     * whose frame was missed shows up. Rows merge with what frames already stored: an outgoing one by its transfer, an
     * incoming one by its transaction id (the id `payment.received` carries). A failure leaves the list as it was.
     */
    private suspend fun syncHistory() {
        val page = (remote.transactions(limit = HistoryPage) as? AppResult.Success)?.value ?: return
        page.transactions.forEach { row ->
            val key = row.transferId ?: row.id
            dao.upsert(row.toEntity(key, dao.transfer(key)))
        }
    }

    private suspend fun save(response: WalletResponse): Wallet {
        val fetchedAt = nowMillis()
        dao.saveSnapshot(WalletSnapshotEntity(1, json.encodeToString(WalletResponse.serializer(), response), fetchedAt))
        if (response.status == "ACTIVE") _setupRequestedBy.value = null
        return response.toDomain(fetchedAt)
    }

    override suspend fun create(): AppResult<WalletStatus> {
        val key = createKey ?: newIdempotencyKey().also { createKey = it }
        val created = when (val r = remote.create(key)) {
            is AppResult.Failure -> { Diagnostics.note("wallet: create failed (${(r.error as? AppError.Api)?.code ?: r.error::class.simpleName})"); return r }
            is AppResult.Success -> r.value
        }
        Diagnostics.note("wallet: create ${created.status}, challenge=${created.challengeId != null}, signer=${signer().available}")
        created.challengeId?.let { challenge ->
            when (val outcome = signer().execute(created.sdk.toDomain(), challenge)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: sign ${outcome::class.simpleName}"); return AppResult.Failure(outcome.toError()) }
            }
        }
        val status = awaitStatus { it.status == WalletStatus.Active }
        if (status == WalletStatus.Active) createKey = null
        return AppResult.Success(status)
    }

    /** Polls W1 (and listens for `wallet.ready`) until [done] or a minute passes; the status seen last. */
    private suspend fun awaitStatus(done: (Wallet) -> Boolean): WalletStatus {
        var last = WalletStatus.SettingUp
        withTimeoutOrNull(AwaitMillis) {
            while (true) {
                val w = refresh()
                if (w is AppResult.Success) { last = w.value.status; if (done(w.value)) return@withTimeoutOrNull }
                val seen = ready.value
                withTimeoutOrNull(PollMillis) { ready.collect { if (it != seen) throw ReadyArrived } }
            }
        }
        return last
    }

    private object ReadyArrived : RuntimeException()

    override suspend fun chains(): AppResult<Chains> = remote.chains().map { r ->
        Chains(r.defaultChain, r.chains.map { ChainOption(it.blockchain, it.assets, when (it.status) { "ENABLED" -> ChainStatus.Enabled; "ADDING" -> ChainStatus.Adding; else -> ChainStatus.Available }, it.receiveEnabled) })
    }

    override suspend fun enableBiometricApproval(): AppResult<Unit> {
        val sdk = when (val r = remote.session()) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        return when (val outcome = signer().enableBiometrics(sdk.toDomain())) {
            SignOutcome.Done -> AppResult.Success(Unit)
            else -> AppResult.Failure(outcome.toError())
        }
    }

    private var addMissingKey: String? = null

    override suspend fun addMissingChains(): AppResult<Unit> {
        val key = addMissingKey ?: newIdempotencyKey().also { addMissingKey = it }
        val added = when (val r = remote.addMissingChains(key)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value.chains }
        // Networks that share a challenge carry the same id: each is run once, so the PIN is asked once per group.
        for (entry in added.filter { it.challengeId != null && it.sdk != null }.distinctBy { it.challengeId }) {
            when (val outcome = signer().execute(entry.sdk!!.toDomain(), entry.challengeId!!)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: add networks sign ${outcome.describe()}"); return AppResult.Failure(outcome.toError()) }
            }
        }
        addMissingKey = null
        val wanted = added.map { it.blockchain }.toSet()
        awaitStatus { w -> wanted.all { b -> w.chains.any { it.blockchain == b } } }
        return AppResult.Success(Unit)
    }

    override suspend fun addChain(blockchain: String): AppResult<ChainStatus> {
        val key = addChainKeys.getOrPut(blockchain) { newIdempotencyKey() }
        val added = when (val r = remote.addChain(blockchain, key)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        if (added.status == "ENABLED") { addChainKeys.remove(blockchain); refresh(); return AppResult.Success(ChainStatus.Enabled) }
        val challenge = added.challengeId
        val sdk = added.sdk
        if (challenge != null && sdk != null) {
            when (val outcome = signer().execute(sdk.toDomain(), challenge)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: add $blockchain sign ${outcome.describe()}"); return AppResult.Failure(outcome.toError()) }
            }
        }
        awaitStatus { w -> w.chains.any { it.blockchain == blockchain } }
        addChainKeys.remove(blockchain)
        return AppResult.Success(ChainStatus.Enabled)
    }

    override suspend fun recipient(userId: String, blockchain: String?, chatId: String?): AppResult<Recipient> =
        remote.recipient(userId, blockchain, chatId).map { Recipient(it.userId, when (it.status) { "CAN_RECEIVE" -> RecipientStatus.CanReceive; "NO_WALLET" -> RecipientStatus.NoWallet; else -> RecipientStatus.NoWalletOnChain }, it.blockchains) }

    override suspend fun remind(userId: String): AppResult<RemindOutcome> =
        remote.remind(userId, newIdempotencyKey()).map { when (it.status) { "SENT" -> RemindOutcome.Sent; "ALREADY_SENT" -> RemindOutcome.AlreadySent; else -> RemindOutcome.HasWallet } }

    override suspend fun validateAddress(blockchain: String, address: String): AppResult<String?> =
        remote.validateAddress(ValidateAddressRequest(blockchain, address.trim())).map { if (it.valid) it.address else null }

    override suspend fun pay(request: PaymentRequest, idempotencyKey: String): AppResult<Transfer> {
        val body = PrepareTransferRequest(
            blockchain = request.blockchain,
            to = PayeeRequest(userId = request.toUserId, address = request.toAddress),
            amount = AmountRequest(request.asset, request.amountMinor),
            chatId = request.chatId,
        )
        val prepared = when (val r = remote.prepare(body, idempotencyKey)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        val now = nowMillis()
        val row = TransferEntity(
            transferId = prepared.transferId, direction = TransferDirection.Out.name, state = prepared.state, blockchain = prepared.blockchain,
            fromAddress = "", toUserId = request.toUserId, toAddress = request.toAddress.orEmpty(), asset = prepared.amount.asset,
            amountMinor = prepared.amount.amountMinor, amount = prepared.amount.amount, feeMinor = prepared.fee?.amountMinor,
            chatId = request.chatId, txHash = null, failureReason = null, counterpartName = request.counterpartName, note = request.note,
            createdAt = now, updatedAt = now,
        )
        dao.upsert(row)
        val challenge = prepared.challengeId
        val sdk = prepared.sdk
        if (challenge != null && sdk != null) {
            when (val outcome = signer().execute(sdk.toDomain(), challenge)) {
                SignOutcome.Done -> dao.upsert(row.copy(state = "SUBMITTED", updatedAt = nowMillis()))
                SignOutcome.Cancelled -> { dao.upsert(row.copy(state = "EXPIRED", failureReason = "cancelled", updatedAt = nowMillis())); return AppResult.Failure(outcome.toError()) }
                else -> return AppResult.Failure(outcome.toError())
            }
        }
        // The frames carry the rest; one read a moment later covers a socket that is not connected.
        val fresh = loadTransfer(prepared.transferId)
        return if (fresh is AppResult.Success) fresh else AppResult.Success(dao.transfer(prepared.transferId)!!.toDomain())
    }

    override suspend fun loadTransfer(transferId: String): AppResult<Transfer> = remote.transfer(transferId).map { dto ->
        val existing = dao.transfer(transferId)
        val row = dto.toEntity(existing)
        dao.upsert(row)
        row.toDomain()
    }

    override suspend fun onFrame(type: String, data: JsonObject?) {
        fun str(k: String) = data?.get(k)?.jsonPrimitive?.contentOrNull
        when (type) {
            "transfer.updated" -> {
                val id = str("transferId") ?: return
                val existing = dao.transfer(id)
                if (existing == null) { loadTransfer(id); return }
                dao.upsert(existing.copy(state = str("state") ?: existing.state, txHash = str("txHash") ?: existing.txHash, failureReason = str("failureReason") ?: existing.failureReason, updatedAt = nowMillis()))
                if (str("state") == "CONFIRMED" || str("state") == "FAILED") refresh()
            }
            "payment.received" -> {
                val id = str("transactionId") ?: return
                // Heard once per payment, even if the frame is replayed after a reconnect.
                if (dao.transfer(id) == null) onMoneyReceived()
                val amount = data?.get("amount")?.jsonObject
                val now = nowMillis()
                dao.upsert(
                    TransferEntity(
                        transferId = id, direction = TransferDirection.In.name, state = "CONFIRMED", blockchain = str("blockchain").orEmpty(),
                        fromAddress = "", toUserId = null, toAddress = "", asset = amount?.get("asset")?.jsonPrimitive?.contentOrNull ?: Wallet.Usdc,
                        amountMinor = amount?.get("amountMinor")?.jsonPrimitive?.contentOrNull ?: "0", amount = amount?.get("amount")?.jsonPrimitive?.contentOrNull ?: "0",
                        feeMinor = null, chatId = null, txHash = null, failureReason = null, counterpartName = str("fromName"), note = null,
                        createdAt = dao.transfer(id)?.createdAt ?: now, updatedAt = now,
                    ),
                )
                refresh()
            }
            "wallet.ready" -> { ready.value++; refresh() }
            "setup.requested" -> _setupRequestedBy.value = str("fromName") ?: "Someone"
            else -> Diagnostics.note("wallet: frame $type ignored")
        }
    }

    private fun decode(row: WalletSnapshotEntity): Wallet? = try {
        json.decodeFromString(WalletResponse.serializer(), row.json).toDomain(row.fetchedAt)
    } catch (e: Exception) { null }

    private companion object {
        const val AwaitMillis = 60_000L
        const val PollMillis = 3_000L
    }
}

internal fun WalletResponse.toDomain(fetchedAt: Long) = Wallet(
    status = when (status) { "ACTIVE" -> WalletStatus.Active; "SETTING_UP" -> WalletStatus.SettingUp; else -> WalletStatus.None },
    chains = chains.map { c -> WalletChain(c.blockchain, c.address, c.state, c.assets.map { Asset(it.symbol, it.tokenAddress, it.decimals, it.amountMinor, it.amount, it.fiatAmount) }, c.receiveEnabled) },
    totals = totals.map { AssetTotal(it.symbol, it.decimals, it.amountMinor, it.amount) },
    fiat = fiat?.let { FiatValue(it.currency, it.amount, it.rate, it.asOf, it.partial) },
    holdUntil = hold?.until,
    fetchedAt = fetchedAt,
)

internal fun SdkDto.toDomain() = SdkSession(appId, userToken, encryptionKey, expiresAt)

/** One page of history on each refresh: the Recent list shows five, the Activity screen more. */
private const val HistoryPage = 50

/** For the diagnostics log: which way the SDK ended, with Circle's code (never a PIN or token). */
internal fun SignOutcome.describe(): String = when (this) {
    is SignOutcome.Failed -> "failed $code"
    else -> this::class.simpleName.orEmpty()
}

internal fun SignOutcome.toError(): AppError = when (this) {
    SignOutcome.Cancelled -> AppError.Api("wallet.cancelled", 400, null, null, emptyMap())
    SignOutcome.Unavailable -> AppError.Api("wallet.sdk_unavailable", 400, null, null, emptyMap())
    is SignOutcome.Failed -> AppError.Api(if (code.contains("pin", ignoreCase = true) && code.contains("lock", ignoreCase = true)) "wallet.pin_locked" else "wallet.sdk_failed", 400, null, null, emptyMap())
    SignOutcome.Done -> AppError.Unexpected(null)
}

internal fun TransferResponse.toEntity(existing: TransferEntity?): TransferEntity {
    val now = nowMillis()
    return TransferEntity(
        transferId = transferId, direction = if (direction == "IN") TransferDirection.In.name else TransferDirection.Out.name, state = state,
        blockchain = blockchain, fromAddress = from, toUserId = to.userId, toAddress = to.address, asset = amount.asset, amountMinor = amount.amountMinor,
        amount = amount.amount, feeMinor = fee?.amountMinor, chatId = chatId ?: existing?.chatId, txHash = txHash, failureReason = failureReason,
        counterpartName = existing?.counterpartName, note = existing?.note,
        createdAt = existing?.createdAt ?: createdAt?.let { isoMillis(it) } ?: now, updatedAt = updatedAt?.let { isoMillis(it) } ?: now,
    )
}

internal fun TransactionDto.toEntity(key: String, existing: TransferEntity?): TransferEntity {
    val at = isoMillis(occurredAt) ?: nowMillis()
    val incoming = direction == "IN"
    val party = counterparty
    return TransferEntity(
        transferId = key, direction = if (incoming) TransferDirection.In.name else TransferDirection.Out.name, state = state, blockchain = blockchain,
        fromAddress = if (incoming) party?.address.orEmpty() else existing?.fromAddress.orEmpty(),
        toUserId = if (incoming) existing?.toUserId else party?.userId ?: existing?.toUserId,
        toAddress = if (incoming) existing?.toAddress.orEmpty() else party?.address ?: existing?.toAddress.orEmpty(),
        asset = amount.asset, amountMinor = amount.amountMinor, amount = amount.amount, feeMinor = existing?.feeMinor,
        chatId = chatId ?: existing?.chatId, txHash = txHash ?: existing?.txHash, failureReason = existing?.failureReason,
        counterpartName = party?.displayName ?: existing?.counterpartName ?: party?.handle?.let { "@$it" }, note = existing?.note,
        createdAt = existing?.createdAt ?: at, updatedAt = maxOf(existing?.updatedAt ?: 0L, at),
    )
}

private fun isoMillis(iso: String): Long? = try { Instant.parse(iso).toEpochMilliseconds() } catch (e: Exception) { null }

internal fun TransferEntity.toDomain() = Transfer(
    transferId = transferId,
    direction = if (direction == TransferDirection.In.name) TransferDirection.In else TransferDirection.Out,
    state = when (state) { "AWAITING_SIGNATURE" -> TransferState.AwaitingSignature; "SUBMITTED" -> TransferState.Submitted; "CONFIRMED" -> TransferState.Confirmed; "FAILED" -> TransferState.Failed; else -> TransferState.Expired },
    blockchain = blockchain, from = fromAddress, toUserId = toUserId, toAddress = toAddress,
    amount = Amount(asset, amountMinor, amount), fee = feeMinor?.let { Amount(asset, it, "") },
    chatId = chatId, txHash = txHash, failureReason = failureReason, counterpartName = counterpartName, note = note,
    createdAt = createdAt, updatedAt = updatedAt,
)

