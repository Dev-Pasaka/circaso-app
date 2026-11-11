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
import com.circaso.feature.wallet.domain.PaymentQuote
import com.circaso.feature.wallet.domain.QuoteProblem
import com.circaso.feature.wallet.data.remote.QuoteTransferResponse
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.circaso.feature.wallet.data.local.WalletActivityEntity
import com.circaso.feature.wallet.data.local.WalletCacheEntity
import com.circaso.feature.wallet.data.local.WalletListEntity
import com.circaso.feature.wallet.data.remote.GroupWalletResponse
import com.circaso.feature.wallet.data.remote.WalletListingDto
import com.circaso.feature.wallet.data.remote.WalletOwnerDto
import com.circaso.feature.wallet.data.remote.WalletsResponse
import com.circaso.feature.wallet.domain.GroupWallet
import com.circaso.feature.wallet.domain.SignPurpose
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.WalletListing
import com.circaso.feature.wallet.domain.WalletOwner
import com.circaso.feature.wallet.domain.WalletOwnerType
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
    /** Circle said one wallet's biometrics are gone ([walletId] null: the personal wallet); only that one falls back to its PIN. */
    onBiometricsLost: suspend (walletId: String?) -> Unit = {},
    /** Money arrived while the app is open (the frame, not a push): the coins sound, if the person has it on. */
    private val onMoneyReceived: suspend () -> Unit = {},
    /**
     * A confirmed payment made from a chat, from the server's own record: the chat shows its card even when the
     * end-to-end card can't be read on this phone (or this phone is new). Messaging dedupes it by transfer.
     */
    private val onChatPayment: suspend (com.circaso.feature.wallet.domain.ChatPaymentSeen) -> Unit = {},
) : WalletRepository {
    // Before init: a frame handled on Main.immediate could reach [save] while the class is still being built.
    private val settledOut = kotlinx.coroutines.flow.MutableSharedFlow<Transfer>(extraBufferCapacity = 16)
    override val settled: Flow<Transfer> = settledOut

    /** Money-in frames already heard (the coins sound plays once per payment). */
    private val heardMoney = mutableSetOf<String>()

    init { scope?.launch { com.circaso.feature.wallet.domain.WalletSignerBridge.biometricsLost.collect { onBiometricsLost(it.walletId) } } }

    /**
     * One challenge at a time: the SDK is one instance for every wallet (Circle user) on the phone, so a group wallet's
     * PIN screen must never open over the personal one's, or a second tap start another before the first is done.
     */
    private val signing = Mutex()

    private suspend fun sign(session: SdkSession, challengeId: String): SignOutcome = signing.withLock { signer().execute(session, challengeId) }

    override val wallet: Flow<Wallet?> = dao.observeSnapshot().map { row -> row?.let { decode(it) } }
    override val transfers: Flow<List<Transfer>> = dao.observeTransfers().map { rows -> rows.map { it.toDomain() } }
    override fun transfer(transferId: String): Flow<Transfer?> = dao.observeTransfer(transferId).map { it?.toDomain() }

    /** Who asked this person to set up a wallet (`setup.requested`), for the tab's nudge; cleared once a wallet exists. */
    private val _setupRequestedBy = MutableStateFlow<String?>(null)
    private val _walletNamesHeard = MutableStateFlow<Map<String, String>>(emptyMap())
    override val walletNamesHeard: kotlinx.coroutines.flow.Flow<Map<String, String>> = _walletNamesHeard
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
        val recentSince = nowMillis() - ChatCardsWithinMillis
        page.transactions.forEach { row ->
            val entity = saveHistoryRow(row)
            // A chat's payment that this phone has no card for (a new phone, an unreadable card): the card, from here.
            val chatId = row.chatId
            if (row.transferId != null && chatId != null && row.state == "CONFIRMED" && entity.createdAt >= recentSince) {
                onChatPayment(
                    com.circaso.feature.wallet.domain.ChatPaymentSeen(
                        chatId = chatId, transferId = row.transferId, outgoing = row.direction != "IN", counterpartUserId = row.counterparty?.userId,
                        amountMinor = row.amount.amountMinor, asset = row.amount.asset, blockchain = row.blockchain, at = isoMillis(row.occurredAt) ?: entity.createdAt,
                    ),
                )
            }
        }
    }

    /** One W6 row into the person's own history, merged with what frames already stored. */
    private suspend fun saveHistoryRow(row: com.circaso.feature.wallet.data.remote.TransactionDto): TransferEntity {
        val key = row.transferId ?: row.id
        // Money in that a Circaso payment brought now names its transfer (2026-11-05): one row, not two.
        if (row.direction == "IN" && row.transferId != null) dao.deleteIncoming(row.id)
        val entity = row.toEntity(key, dao.transfer(key))
        dao.upsert(entity)
        return entity
    }

    override suspend fun loadActivityPage(walletId: String?, cursor: String?): AppResult<String?> =
        remote.transactions(cursor = cursor, limit = HistoryPage, walletId = walletId).map { page ->
            if (walletId != null) dao.saveActivity(page.transactions.map { it.toActivity(walletId) })
            else page.transactions.forEach { saveHistoryRow(it) }
            page.nextCursor
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
            when (val outcome = sign(created.sdk.toDomain(), challenge)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: sign ${outcome::class.simpleName}"); return AppResult.Failure(outcome.toError()) }
            }
        }
        val status = awaitStatus { it.status == WalletStatus.Active }
        if (status == WalletStatus.Active) createKey = null
        return AppResult.Success(status)
    }

    /** Polls W1 (and listens for `wallet.ready`) until [done] or a minute passes; the status seen last. */
    private suspend fun awaitStatus(walletId: String? = null, done: (Wallet) -> Boolean): WalletStatus {
        var last = WalletStatus.SettingUp
        withTimeoutOrNull(AwaitMillis) {
            while (true) {
                val w = refreshWallet(walletId)
                if (w is AppResult.Success) { last = w.value.status; if (done(w.value)) return@withTimeoutOrNull }
                val seen = ready.value
                try { withTimeoutOrNull(PollMillis) { ready.collect { if (it != seen) throw ReadyArrived } } } catch (e: ReadyArrived) { }
            }
        }
        return last
    }

    private object ReadyArrived : RuntimeException()

    override suspend fun chains(): AppResult<Chains> = remote.chains().map { r ->
        Chains(r.defaultChain, r.chains.map { ChainOption(it.blockchain, it.assets, when (it.status) { "ENABLED" -> ChainStatus.Enabled; "ADDING" -> ChainStatus.Adding; else -> ChainStatus.Available }, it.receiveEnabled) })
    }

    override suspend fun enableBiometricApproval(): AppResult<Unit> = enableBiometricApproval(SignSubject.Personal)

    override suspend fun enableBiometricApproval(subject: SignSubject): AppResult<Unit> {
        val sdk = when (val r = remote.session(subject.walletId)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        val session = sdk.toDomain(subject.copy(purpose = SignPurpose.EnableBiometrics))
        return when (val outcome = signing.withLock { signer().enableBiometrics(session) }) {
            SignOutcome.Done -> AppResult.Success(Unit)
            else -> AppResult.Failure(outcome.toError())
        }
    }

    private val addMissingKeys = mutableMapOf<String?, String>()

    override suspend fun addMissingChains(): AppResult<Unit> = addMissingChains(SignSubject.Personal)

    override suspend fun addMissingChains(subject: SignSubject): AppResult<Unit> {
        val walletId = subject.walletId
        val subjectForPin = subject.copy(purpose = SignPurpose.AddNetworks)
        val key = addMissingKeys.getOrPut(walletId) { newIdempotencyKey() }
        val added = when (val r = remote.addMissingChains(key, walletId)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value.chains }
        // Networks that share a challenge carry the same id: each is run once, so the PIN is asked once per group.
        for (entry in added.filter { it.challengeId != null && it.sdk != null }.distinctBy { it.challengeId }) {
            when (val outcome = sign(entry.sdk!!.toDomain(subjectForPin), entry.challengeId!!)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: add networks sign ${outcome.describe()}"); return AppResult.Failure(outcome.toError()) }
            }
        }
        addMissingKeys.remove(walletId)
        val wanted = added.map { it.blockchain }.toSet()
        awaitStatus(walletId) { w -> wanted.all { b -> w.chains.any { it.blockchain == b } } }
        return AppResult.Success(Unit)
    }

    override suspend fun addChain(blockchain: String): AppResult<ChainStatus> {
        val key = addChainKeys.getOrPut(blockchain) { newIdempotencyKey() }
        val added = when (val r = remote.addChain(blockchain, key)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        if (added.status == "ENABLED") { addChainKeys.remove(blockchain); refresh(); return AppResult.Success(ChainStatus.Enabled) }
        val challenge = added.challengeId
        val sdk = added.sdk
        if (challenge != null && sdk != null) {
            when (val outcome = sign(sdk.toDomain(), challenge)) {
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
            to = PayeeRequest(userId = request.toUserId, address = request.toAddress, groupId = request.toGroupId, spaceId = request.toSpaceId),
            amount = AmountRequest(request.asset, request.amountMinor),
            chatId = request.chatId,
            walletId = request.fromWalletId,
            payoutRequestId = request.payoutRequestId,
            quoteId = request.quoteId,
            spacePaymentId = request.spacePaymentId,
        )
        val prepared = when (val r = remote.prepare(body, idempotencyKey)) { is AppResult.Failure -> return r; is AppResult.Success -> r.value }
        val now = nowMillis()
        val row = TransferEntity(
            transferId = prepared.transferId, direction = TransferDirection.Out.name, state = prepared.state, blockchain = prepared.blockchain,
            fromAddress = "", toUserId = request.toUserId, toAddress = request.toAddress.orEmpty(), asset = prepared.amount.asset,
            amountMinor = prepared.amount.amountMinor, amount = prepared.amount.amount, feeMinor = prepared.fee?.amountMinor,
            chatId = request.chatId, txHash = null, failureReason = null, counterpartName = request.counterpartName, note = request.note,
            createdAt = now, updatedAt = now,
            // A payout lives with the group wallet it leaves, not in the person's own list; a contribution leaves theirs.
            walletId = request.fromWalletId, toGroupId = request.toGroupId, payoutRequestId = request.payoutRequestId,
            fromGroupId = request.fromWalletId?.let { request.chatId },
        )
        dao.upsert(row)
        val challenge = prepared.challengeId
        val sdk = prepared.sdk
        if (challenge != null && sdk != null) {
            when (val outcome = sign(sdk.toDomain(request.subject.copy(purpose = SignPurpose.Approve)), challenge)) {
                SignOutcome.Done -> dao.upsert(row.copy(state = "SUBMITTED", updatedAt = nowMillis()))
                SignOutcome.Cancelled -> { dao.upsert(row.copy(state = "EXPIRED", failureReason = "cancelled", updatedAt = nowMillis())); return AppResult.Failure(outcome.toError()) }
                else -> return AppResult.Failure(outcome.toError())
            }
        }
        // The frames carry the rest; one read a moment later covers a socket that is not connected.
        val fresh = loadTransfer(prepared.transferId)
        return if (fresh is AppResult.Success) fresh else AppResult.Success(dao.transfer(prepared.transferId)!!.toDomain())
    }

    override suspend fun quote(request: PaymentRequest): AppResult<PaymentQuote?> {
        val body = PrepareTransferRequest(
            blockchain = request.blockchain,
            to = PayeeRequest(userId = request.toUserId, address = request.toAddress, groupId = request.toGroupId, spaceId = request.toSpaceId),
            amount = AmountRequest(request.asset, request.amountMinor),
            chatId = request.chatId,
            walletId = request.fromWalletId,
            spacePaymentId = request.spacePaymentId,
        )
        // Nothing is created, so every quote takes a fresh key (a repeated one would be answered from the replay cache).
        return remote.quote(body, newIdempotencyKey()).map { it?.toDomain() }
    }

    // ---- Group wallets (phase 2)

    override val wallets: Flow<List<WalletListing>> = dao.observeList().map { row ->
        row?.let { runCatching { json.decodeFromString(WalletsResponse.serializer(), it.json) }.getOrNull() }?.wallets.orEmpty().map { it.toDomain() }
    }

    override suspend fun refreshWallets(): AppResult<List<WalletListing>> = remote.wallets().map { r ->
        dao.saveList(WalletListEntity(1, json.encodeToString(WalletsResponse.serializer(), r), nowMillis()))
        r.wallets.map { it.toDomain() }
    }

    override fun walletOf(walletId: String?): Flow<Wallet?> =
        if (walletId == null) wallet else dao.observeCached(walletId).map { row -> row?.let { decodeCached(it) } }

    override suspend fun refreshWallet(walletId: String?): AppResult<Wallet> {
        if (walletId == null) return refresh()
        return remote.wallet(currency(), walletId).map { response ->
            val at = nowMillis()
            dao.saveCached(WalletCacheEntity(walletId, json.encodeToString(WalletResponse.serializer(), response), at))
            response.toDomain(at)
        }
    }

    override fun activity(walletId: String): Flow<List<Transfer>> =
        combine(dao.observeActivity(walletId), dao.observeTransfersOf(walletId)) { history, signedHere ->
            val known = history.mapNotNull { it.transferId }.toSet()
            (history.map { it.toDomain() } + signedHere.filter { it.transferId !in known }.map { it.toDomain() }).sortedByDescending { it.createdAt }
        }

    override suspend fun refreshActivity(walletId: String): AppResult<Unit> =
        remote.transactions(limit = HistoryPage, walletId = walletId).map { page -> dao.saveActivity(page.transactions.map { it.toActivity(walletId) }) }

    override suspend fun groupWallet(chatId: String): AppResult<GroupWallet> = remote.groupWallet(chatId).map { it.toDomain() }

    private val setupKeys = mutableMapOf<String, String>()

    override suspend fun startGroupWallet(chatId: String, name: String): AppResult<GroupWallet> {
        val key = setupKeys.getOrPut(chatId) { newIdempotencyKey() }
        val started = when (val r = remote.setUpGroupWallet(chatId, key)) {
            is AppResult.Failure -> {
                Diagnostics.note("wallet: group set-up failed (${(r.error as? AppError.Api)?.code ?: r.error::class.simpleName})")
                // Already active (a second phone finished it, or a retry after the answer was lost): nothing to sign.
                if ((r.error as? AppError.Api)?.code == "wallet.already_exists") { setupKeys.remove(chatId); return groupWallet(chatId) }
                return r
            }
            is AppResult.Success -> r.value
        }
        started.challengeId?.let { challenge ->
            val subject = SignSubject.group(started.walletId, name, SignPurpose.SetPin)
            when (val outcome = sign(started.sdk.toDomain(subject), challenge)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: group PIN ${outcome.describe()}"); setupKeys.remove(chatId); return AppResult.Failure(outcome.toError()) }
            }
        }
        // A new key next time: W14 again while it is setting up gives a fresh challenge (GW6b); it must not replay this one.
        setupKeys.remove(chatId)
        val listing = WalletListing(started.walletId, WalletOwner(WalletOwnerType.Group, null, chatId, ""), walletStatus(started.status), true, emptyList())
        return AppResult.Success(GroupWallet(chatId, walletStatus(started.status), listing))
    }

    override suspend fun awaitGroupWallet(chatId: String): AppResult<GroupWallet> {
        var last: AppResult<GroupWallet> = AppResult.Failure(AppError.Network)
        withTimeoutOrNull(AwaitMillis) {
            while (true) {
                val seen = groupWallet(chatId)
                last = seen
                if (seen is AppResult.Success && seen.value.status == WalletStatus.Active) return@withTimeoutOrNull
                // An answer that says it can't be (not in the group, not found) ends the wait at once.
                if (seen is AppResult.Failure && seen.error is AppError.Api) return@withTimeoutOrNull
                val tick = ready.value
                try { withTimeoutOrNull(PollMillis) { ready.collect { if (it != tick) throw ReadyArrived } } } catch (e: ReadyArrived) { }
            }
        }
        val done = last
        if (done is AppResult.Success) {
            done.value.wallet?.walletId?.let { refreshWallet(it) }
            refreshWallets()
        }
        return done
    }

    override suspend fun loadTransfer(transferId: String): AppResult<Transfer> = remote.transfer(transferId).map { dto ->
        val existing = dao.transfer(transferId)
        val row = dto.toEntity(existing)
        save(row, existing)
        row.toDomain()
    }

    override fun transfers(ids: Collection<String>): Flow<List<Transfer>> =
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else dao.observeTransfersIn(ids.distinct()).map { rows -> rows.map { it.toDomain() } }

    /** Saves the row; an outgoing payment that has just been confirmed or failed is announced on [settled]. */
    private suspend fun save(row: TransferEntity, existing: TransferEntity?) {
        dao.upsert(row)
        val ended = row.state == "CONFIRMED" || row.state == "FAILED"
        if (ended && existing?.state != row.state && row.direction == TransferDirection.Out.name) settledOut.tryEmit(row.toDomain())
    }

    override suspend fun onFrame(type: String, data: JsonObject?) {
        fun str(k: String) = data?.get(k)?.jsonPrimitive?.contentOrNull
        when (type) {
            "transfer.updated" -> {
                val id = str("transferId") ?: return
                // A group wallet's frames go to its signer with `walletId`; null is the person's own wallet.
                val walletId = str("walletId")
                val existing = dao.transfer(id)
                if (existing == null) {
                    loadTransfer(id)
                    if (walletId != null) dao.transfer(id)?.let { dao.upsert(it.copy(walletId = walletId)) }
                    return
                }
                save(existing.copy(state = str("state") ?: existing.state, txHash = str("txHash") ?: existing.txHash, failureReason = str("failureReason") ?: existing.failureReason, updatedAt = nowMillis()), existing)
                if (str("state") == "CONFIRMED" || str("state") == "FAILED") { refreshWallet(walletId); if (walletId != null) refreshActivity(walletId) }
            }
            "payment.received" -> {
                val id = str("transactionId") ?: return
                // Money into a group wallet: its history and balance, not the person's own list.
                val groupWallet = str("walletId")
                // A space wallet's frame names its space (phase 3): kept so the money is labelled before Room has the space.
                if (groupWallet != null && str("ownerType") == "SPACE") str("spaceName")?.let { name -> _walletNamesHeard.value = _walletNamesHeard.value + (groupWallet to name) }
                if (groupWallet != null) { refreshWallet(groupWallet); refreshActivity(groupWallet); return }
                // A Circaso payment names its transfer (2026-11-05): kept under it, so the chat's card and the
                // history are one row. Money from outside Circaso stays under the history row's id.
                val transferId = str("transferId")
                val key = transferId ?: id
                // Heard once per payment, even if the frame is replayed after a reconnect (the chat's card may have
                // read the transfer first, so the row alone can't say).
                if (heardMoney.add(key) && dao.transfer(id) == null && dao.transfer(key)?.state != "CONFIRMED") onMoneyReceived()
                if (transferId != null) dao.deleteIncoming(id)
                val amount = data?.get("amount")?.jsonObject
                val now = nowMillis()
                val existing = dao.transfer(key)
                val asset = amount?.get("asset")?.jsonPrimitive?.contentOrNull ?: Wallet.Usdc
                val amountMinor = amount?.get("amountMinor")?.jsonPrimitive?.contentOrNull ?: "0"
                dao.upsert(
                    existing?.copy(state = "CONFIRMED", chatId = existing.chatId ?: str("chatId"), counterpartName = existing.counterpartName ?: str("fromName"), updatedAt = now)
                        ?: TransferEntity(
                            transferId = key, direction = TransferDirection.In.name, state = "CONFIRMED", blockchain = str("blockchain").orEmpty(),
                            fromAddress = "", toUserId = null, toAddress = "", asset = asset,
                            amountMinor = amountMinor, amount = amount?.get("amount")?.jsonPrimitive?.contentOrNull ?: "0",
                            feeMinor = null, chatId = str("chatId"), txHash = null, failureReason = null, counterpartName = str("fromName"), note = null,
                            createdAt = now, updatedAt = now,
                        ),
                )
                // Paid from a chat: that chat shows the card, whatever happened to the end-to-end one.
                val chatId = str("chatId")
                if (transferId != null && chatId != null) {
                    onChatPayment(
                        com.circaso.feature.wallet.domain.ChatPaymentSeen(
                            chatId = chatId, transferId = transferId, outgoing = false, counterpartUserId = str("fromUserId"),
                            amountMinor = amountMinor, asset = asset, blockchain = str("blockchain").orEmpty(), at = existing?.createdAt ?: now,
                        ),
                    )
                }
                refresh()
            }
            "wallet.ready" -> {
                ready.value++
                val walletId = str("walletId")
                if (walletId == null) refresh() else { refreshWallet(walletId); refreshWallets() }
            }
            "setup.requested" -> _setupRequestedBy.value = str("fromName") ?: "Someone"
            else -> Diagnostics.note("wallet: frame $type ignored")
        }
    }

    private fun decodeCached(row: WalletCacheEntity): Wallet? = try {
        json.decodeFromString(WalletResponse.serializer(), row.json).toDomain(row.fetchedAt)
    } catch (e: Exception) { null }

    private fun decode(row: WalletSnapshotEntity): Wallet? = try {
        json.decodeFromString(WalletResponse.serializer(), row.json).toDomain(row.fetchedAt)
    } catch (e: Exception) { null }

    private companion object {
        /**
         * How long a wallet set-up (personal, group or space) waits for Circle to finish creating it. Circle's smart
         * contract wallets took 1 min 43 s on testnet (2026-11-05), past the old 60 s, so a space was left "failed" though
         * its wallet was made; the answer still lands at once (frame or the 3 s check) when it's quicker.
         */
        // The server now syncs on Circle's notification and on these polls (backend PR #36), so a wallet is ready
        // seconds after the PIN; 2 minutes is the margin for a slow testnet, not the usual wait.
        const val AwaitMillis = 120_000L
        const val PollMillis = 3_000L
        /** History rows older than this don't add cards to chats (a new phone isn't flooded with old money). */
        const val ChatCardsWithinMillis = 30L * 24 * 60 * 60 * 1000
    }
}

internal fun WalletResponse.toDomain(fetchedAt: Long) = Wallet(
    status = when (status) { "ACTIVE" -> WalletStatus.Active; "SETTING_UP" -> WalletStatus.SettingUp; else -> WalletStatus.None },
    chains = chains.map { c -> WalletChain(c.blockchain, c.address, c.state, c.assets.map { Asset(it.symbol, it.tokenAddress, it.decimals, it.amountMinor, it.amount, it.fiatAmount, it.usdPrice) }, c.receiveEnabled) },
    totals = totals.map { AssetTotal(it.symbol, it.decimals, it.amountMinor, it.amount, it.usdPrice) },
    fiat = fiat?.let { FiatValue(it.currency, it.amount, it.rate, it.asOf, it.partial) },
    holdUntil = hold?.until,
    holdReason = hold?.reason,
    fetchedAt = fetchedAt,
    walletId = walletId,
    owner = owner?.toDomain(),
    canSign = canSign,
)

internal fun SdkDto.toDomain(subject: SignSubject = SignSubject.Personal) = SdkSession(appId, userToken, encryptionKey, expiresAt, subject)

internal fun walletStatus(status: String) = when (status) { "ACTIVE" -> WalletStatus.Active; "SETTING_UP" -> WalletStatus.SettingUp; else -> WalletStatus.None }

internal fun WalletOwnerDto.toDomain() = WalletOwner(
    type = when (type) { "GROUP" -> WalletOwnerType.Group; "SPACE" -> WalletOwnerType.Space; else -> WalletOwnerType.Person },
    userId = userId, chatId = chatId, signerUserId = signerUserId, spaceId = spaceId,
)

internal fun WalletListingDto.toDomain() = WalletListing(walletId, owner.toDomain(), walletStatus(status), canSign, blockchains)

internal fun GroupWalletResponse.toDomain() = GroupWallet(chatId, walletStatus(status), wallet?.toDomain())

/** How a space counterparty is kept in `wallet_activity.counterpartGroupId` (no schema change); see [Transfer.counterpartSpaceId]. */
internal const val SpacePrefix = "space:"

internal fun TransactionDto.toActivity(walletId: String): WalletActivityEntity = WalletActivityEntity(
    walletId = walletId, rowId = id, transferId = transferId, direction = direction, state = state, blockchain = blockchain,
    asset = amount.asset, amountMinor = amount.amountMinor, amount = amount.amount,
    counterpartName = counterparty?.displayName ?: counterparty?.handle?.let { "@$it" }, counterpartHandle = counterparty?.handle,
    counterpartAvatar = counterparty?.avatarUrl, counterpartUserId = counterparty?.userId, counterpartAddress = counterparty?.address,
    // A space on the other side (phase 3) has no name on the wire: kept as "space:{id}" and named from the phone's spaces.
    counterpartGroupId = counterparty?.groupId ?: (counterparty?.spaceId ?: if (direction == "OUT") toSpaceId else fromSpaceId)?.let { SpacePrefix + it },
    chatId = chatId, txHash = txHash, occurredAt = isoMillis(occurredAt) ?: nowMillis(),
)

internal fun WalletActivityEntity.toDomain(): Transfer {
    val incoming = direction == "IN"
    return Transfer(
        transferId = transferId ?: rowId, direction = if (incoming) TransferDirection.In else TransferDirection.Out,
        state = when (state) { "AWAITING_SIGNATURE" -> TransferState.AwaitingSignature; "SUBMITTED" -> TransferState.Submitted; "CONFIRMED" -> TransferState.Confirmed; "FAILED" -> TransferState.Failed; else -> TransferState.Expired },
        blockchain = blockchain, from = if (incoming) counterpartAddress.orEmpty() else "", toUserId = if (incoming) null else counterpartUserId,
        toAddress = if (incoming) "" else counterpartAddress.orEmpty(), amount = Amount(asset, amountMinor, amount), fee = null, chatId = chatId,
        txHash = txHash, failureReason = null, counterpartName = counterpartName, note = null, createdAt = occurredAt, updatedAt = occurredAt,
        counterpartHandle = counterpartHandle, counterpartAvatar = counterpartAvatar, walletId = walletId, counterpartGroupId = counterpartGroupId,
    )
}


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
        counterpartHandle = existing?.counterpartHandle, counterpartAvatar = existing?.counterpartAvatar,
        walletId = existing?.walletId, fromGroupId = fromGroupId ?: existing?.fromGroupId, toGroupId = toGroupId ?: existing?.toGroupId,
        payoutRequestId = payoutRequestId ?: existing?.payoutRequestId,
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
        counterpartHandle = party?.handle ?: existing?.counterpartHandle, counterpartAvatar = party?.avatarUrl ?: existing?.counterpartAvatar,
        walletId = existing?.walletId, fromGroupId = existing?.fromGroupId, toGroupId = party?.groupId ?: existing?.toGroupId, payoutRequestId = existing?.payoutRequestId,
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
    createdAt = createdAt, updatedAt = updatedAt, counterpartHandle = counterpartHandle, counterpartAvatar = counterpartAvatar,
    walletId = walletId, fromGroupId = fromGroupId, toGroupId = toGroupId, payoutRequestId = payoutRequestId,
)

private fun QuoteTransferResponse.toDomain() = PaymentQuote(
    feeMinor = fee.amountMinor, rateBps = fee.rateBps, minimum = fee.minimum == true, totalMinor = total.amountMinor,
    balanceMinor = balance?.amountMinor, maxAmountMinor = maxAmount?.amountMinor,
    problem = when (problem) {
        "INSUFFICIENT_FUNDS" -> QuoteProblem.InsufficientFunds
        "PER_PAYMENT_LIMIT" -> QuoteProblem.PerPaymentLimit
        "DAILY_LIMIT" -> QuoteProblem.DailyLimit
        else -> null
    },
    fitsAt = fitsAt, leftMinor = limit?.left?.amountMinor, dailyMaxMinor = limit?.dailyMax?.amountMinor, moreAt = limit?.moreAt,
    quoteId = quoteId, expiresAt = expiresAt,
)

