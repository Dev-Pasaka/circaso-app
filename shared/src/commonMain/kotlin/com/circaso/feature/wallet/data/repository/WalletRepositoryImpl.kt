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
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import com.circaso.feature.wallet.domain.PinnedNetwork
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
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
    /** Names I saved for people: a payment row shows them in place of the server's name. */
    private val names: com.circaso.feature.names.domain.PeopleNames = com.circaso.feature.names.domain.PeopleNames.None,
    /**
     * Which wallet network is selected (wallet networks, 2026-11-18). Every copy on the phone is keyed on it, every
     * list follows it, and a call is made and saved for one network whatever is selected by the time it answers.
     */
    private val networks: WalletNetworks = WalletNetworks.Single,
    /** Circle said a wallet's biometrics are gone, with the network that wallet is on (each network has its own flags). */
    onBiometricsLostOn: (suspend (walletId: String?, network: WalletNetwork) -> Unit)? = null,
) : WalletRepository {
    /** The network of the challenge on Circle's screen right now: what a "biometrics lost" report belongs to. */
    private var signingOn: WalletNetwork? = null

    /** Runs [block] for one network: the remote sends it and every copy made inside is saved under it. */
    private suspend fun <T> on(network: WalletNetwork, block: suspend () -> T): T = withContext(PinnedNetwork(network)) { block() }

    /** The network this call is for: pinned on the coroutine, else the selected one. */
    private suspend fun net(): WalletNetwork = networks.effective()

    /** Where a server answer is kept: the network it names, else the one that was asked for. */
    private fun keyOf(answered: String?, asked: WalletNetwork): String = (WalletNetwork.of(answered) ?: asked).wire

    private fun Transfer.named(saved: Map<String, String>): Transfer =
        counterpartUserId?.let { saved[it] }?.takeIf { it != counterpartName }?.let { copy(counterpartName = it, counterpartOwnName = counterpartName) } ?: this

    // Before init: a frame handled on Main.immediate could reach [save] while the class is still being built.
    private val settledOut = kotlinx.coroutines.flow.MutableSharedFlow<Transfer>(extraBufferCapacity = 16)
    override val settled: Flow<Transfer> = settledOut

    /** Money-in frames already heard (the coins sound plays once per payment). */
    private val heardMoney = mutableSetOf<String>()

    init {
        scope?.launch {
            com.circaso.feature.wallet.domain.WalletSignerBridge.biometricsLost.collect {
                val on = signingOn ?: networks.current()
                if (onBiometricsLostOn != null) onBiometricsLostOn(it.walletId, on) else onBiometricsLost(it.walletId)
            }
        }
    }

    /**
     * One challenge at a time: the SDK is one instance for every wallet (Circle user) on the phone, so a group wallet's
     * PIN screen must never open over the personal one's, or a second tap start another before the first is done.
     */
    private val signing = Mutex()

    private suspend fun sign(session: SdkSession, challengeId: String): SignOutcome {
        val on = net()
        return signing.withLock {
            signingOn = on
            signer().execute(session, challengeId)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val wallet: Flow<Wallet?> = networks.followed.flatMapLatest { dao.observeSnapshot(it.wire) }.map { row -> row?.let { decode(it) } }
    override fun walletOn(network: WalletNetwork): Flow<Wallet?> = dao.observeSnapshot(network.wire).map { row -> row?.let { decode(it) } }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val transfers: Flow<List<Transfer>> =
        combine(networks.followed.flatMapLatest { dao.observeTransfers(it.wire) }, names.saved) { rows, saved -> rows.map { it.toDomain().named(saved) } }
    override fun transfer(transferId: String): Flow<Transfer?> = combine(dao.observeTransfer(transferId), names.saved) { row, saved -> row?.toDomain()?.named(saved) }

    /** Who asked this person to set up a wallet (`setup.requested`), for the tab's nudge; cleared once a wallet exists. */
    private val _setupRequestedBy = MutableStateFlow<Map<WalletNetwork, String>>(emptyMap())
    private val _walletNamesHeard = MutableStateFlow<Map<String, String>>(emptyMap())
    override val walletNamesHeard: kotlinx.coroutines.flow.Flow<Map<String, String>> = _walletNamesHeard
    /** For the selected network only: someone waiting on a Testnet wallet says nothing on the Mainnet page. */
    val setupRequestedBy: Flow<String?> = combine(_setupRequestedBy, networks.selected) { asked, on -> asked[on] }

    /** Flipped by `wallet.ready`, so a create or add-chain stops polling at once. */
    private val ready = MutableStateFlow(0)

    private var createKey: String? = null
    private val addChainKeys = mutableMapOf<String, String>()

    override suspend fun refresh(): AppResult<Wallet> {
        val network = net()
        return on(network) { remote.wallet(currency()).map { save(it, network) }.also { if (it is AppResult.Success) syncHistory() } }
    }

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
        val entity = row.toEntity(key, dao.transfer(key), net())
        dao.upsert(entity)
        return entity
    }

    override suspend fun loadActivityPage(walletId: String?, cursor: String?): AppResult<String?> {
        val network = net()
        return on(network) {
            remote.transactions(cursor = cursor, limit = HistoryPage, walletId = walletId).map { page ->
                if (walletId != null) dao.saveActivity(page.transactions.map { it.toActivity(walletId, network) })
                else page.transactions.forEach { saveHistoryRow(it) }
                page.nextCursor
            }
        }
    }

    private suspend fun save(response: WalletResponse, asked: WalletNetwork): Wallet {
        val fetchedAt = nowMillis()
        val key = keyOf(response.network, asked)
        dao.saveSnapshot(WalletSnapshotEntity(key, json.encodeToString(WalletResponse.serializer(), response), fetchedAt))
        if (response.status == "ACTIVE") WalletNetwork.of(key)?.let { on -> _setupRequestedBy.update { it - on } }
        return response.toDomain(fetchedAt).copy(network = WalletNetwork.of(key))
    }

    /** The create key belongs to one network's wallet: Mainnet's W2 must never replay Testnet's. */
    private var createKeyOn: WalletNetwork? = null

    override suspend fun create(): AppResult<WalletStatus> {
        val network = net()
        return on(network) { createOn(network) }
    }

    private suspend fun createOn(network: WalletNetwork): AppResult<WalletStatus> {
        if (createKeyOn != network) { createKey = null; createKeyOn = network }
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
        val network = net()
        return on(network) { enableBiometricsOn(subject) }
    }

    private suspend fun enableBiometricsOn(subject: SignSubject): AppResult<Unit> {
        signingOn = net()
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
        val network = net()
        return on(network) { addMissingOn(subject) }
    }

    private suspend fun addMissingOn(subject: SignSubject): AppResult<Unit> {
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
        // A chain belongs to one network, so the chain says which wallet it joins.
        val network = pinnedOr(WalletNetwork.ofChain(blockchain))
        return on(network) { addChainOn(blockchain) }
    }

    /** The pinned network when there is one; else [fallback], when the person may use it; else the selected one. */
    private suspend fun pinnedOr(fallback: WalletNetwork?): WalletNetwork {
        com.circaso.feature.wallet.domain.pinnedNetwork()?.let { return it }
        val selected = net()
        val s = networks.state.value
        return fallback?.takeIf { f -> f == selected || (s.known && !s.legacy && s.options.any { it.network == f }) } ?: selected
    }

    private suspend fun addChainOn(blockchain: String): AppResult<ChainStatus> {
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

    /**
     * A payment is prepared and signed for one network: the one its review was shown for ([PaymentRequest.network]).
     * If the selection has moved since, nothing is sent; if it moves between W4 and the PIN, nothing is signed.
     * A group's money is the exception ([PaymentRequest.followsSelection]): it moves on the network the group's wallet
     * is on, whatever the Wallet tab shows, so the selection never stops it.
     */
    override suspend fun pay(request: PaymentRequest, idempotencyKey: String): AppResult<Transfer> {
        val network = request.network ?: net()
        if (request.followsSelection && moved(network)) return AppResult.Failure(networkChanged())
        return on(network) { payOn(request, idempotencyKey, network) }
    }

    /** The person has moved to another network than the payment's (only once the saved choice is read). */
    private fun moved(network: WalletNetwork): Boolean = networks.state.value.let { it.loaded && it.selected != network }

    private fun networkChanged() = AppError.Api(com.circaso.feature.wallet.domain.NetworkChangedCode, 409, null, null, emptyMap())

    private suspend fun payOn(request: PaymentRequest, idempotencyKey: String, network: WalletNetwork): AppResult<Transfer> {
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
            counterpartUserId = request.toUserId,
            fromGroupId = request.fromWalletId?.let { request.chatId },
            network = keyOf(prepared.network, network),
        )
        dao.upsert(row)
        val challenge = prepared.challengeId
        val sdk = prepared.sdk
        if (challenge != null && sdk != null) {
            // The person moved to the other network while W4 was answering: its PIN screen never opens for this one.
            if (request.followsSelection && moved(network)) {
                dao.upsert(row.copy(state = "EXPIRED", failureReason = "network_changed", updatedAt = nowMillis()))
                return AppResult.Failure(networkChanged())
            }
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
        val network = request.network ?: net()
        return on(network) { quoteOn(request) }
    }

    private suspend fun quoteOn(request: PaymentRequest): AppResult<PaymentQuote?> {
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

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override val wallets: Flow<List<WalletListing>> = networks.followed.flatMapLatest { dao.observeList(it.wire) }.map { row ->
        row?.let { runCatching { json.decodeFromString(WalletsResponse.serializer(), it.json) }.getOrNull() }?.wallets.orEmpty().map { it.toDomain() }
    }

    override fun walletsOn(network: WalletNetwork): Flow<List<WalletListing>?> = dao.observeList(network.wire).map { row ->
        row?.let { runCatching { json.decodeFromString(WalletsResponse.serializer(), it.json) }.getOrNull() }?.wallets?.map { it.toDomain() }
    }

    override suspend fun refreshWallets(): AppResult<List<WalletListing>> {
        val network = net()
        return on(network) {
            remote.wallets().map { r ->
                dao.saveList(WalletListEntity(network.wire, json.encodeToString(WalletsResponse.serializer(), r), nowMillis()))
                r.wallets.map { it.toDomain() }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun walletOf(walletId: String?): Flow<Wallet?> =
        if (walletId == null) wallet else networks.followed.flatMapLatest { dao.observeCached(walletId, it.wire) }.map { row -> row?.let { decodeCached(it) } }

    override fun walletOfOn(walletId: String?, network: WalletNetwork): Flow<Wallet?> =
        if (walletId == null) walletOn(network) else dao.observeCached(walletId, network.wire).map { row -> row?.let { decodeCached(it) } }

    override suspend fun refreshWallet(walletId: String?): AppResult<Wallet> {
        if (walletId == null) return refresh()
        val network = net()
        return on(network) {
            remote.wallet(currency(), walletId).map { response ->
                val at = nowMillis()
                val key = keyOf(response.network, network)
                dao.saveCached(WalletCacheEntity(walletId, json.encodeToString(WalletResponse.serializer(), response), at, key))
                response.toDomain(at).copy(network = WalletNetwork.of(key))
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun activity(walletId: String): Flow<List<Transfer>> = networks.followed.flatMapLatest { on ->
        combine(dao.observeActivity(walletId, on.wire), dao.observeTransfersOf(walletId, on.wire), names.saved) { history, signedHere, saved ->
            val known = history.mapNotNull { it.transferId }.toSet()
            (history.map { it.toDomain() } + signedHere.filter { it.transferId !in known }.map { it.toDomain() }).sortedByDescending { it.createdAt }
                .map { it.named(saved) }
        }
    }

    override suspend fun refreshActivity(walletId: String): AppResult<Unit> {
        val network = net()
        return on(network) {
            remote.transactions(limit = HistoryPage, walletId = walletId).map { page -> dao.saveActivity(page.transactions.map { it.toActivity(walletId, network) }) }
        }
    }

    override suspend fun groupWallet(chatId: String): AppResult<GroupWallet> = remote.groupWallet(chatId).map { it.toDomain() }

    private val setupKeys = mutableMapOf<String, String>()

    override suspend fun startGroupWallet(chatId: String, name: String): AppResult<GroupWallet> {
        val network = net()
        return on(network) { startGroupWalletOn(chatId, name, network) }
    }

    private suspend fun startGroupWalletOn(chatId: String, name: String, network: WalletNetwork): AppResult<GroupWallet> {
        // One group can have a wallet on each network: each set-up has its own key.
        val key = setupKeys.getOrPut("${network.wire}:$chatId") { newIdempotencyKey() }
        val started = when (val r = remote.setUpGroupWallet(chatId, key)) {
            is AppResult.Failure -> {
                Diagnostics.note("wallet: group set-up failed (${(r.error as? AppError.Api)?.code ?: r.error::class.simpleName})")
                // Already active (a second phone finished it, or a retry after the answer was lost): nothing to sign.
                if ((r.error as? AppError.Api)?.code == "wallet.already_exists") { setupKeys.remove("${network.wire}:$chatId"); return groupWallet(chatId) }
                return r
            }
            is AppResult.Success -> r.value
        }
        started.challengeId?.let { challenge ->
            val subject = SignSubject.group(started.walletId, name, SignPurpose.SetPin)
            when (val outcome = sign(started.sdk.toDomain(subject), challenge)) {
                SignOutcome.Done -> Unit
                else -> { Diagnostics.note("wallet: group PIN ${outcome.describe()}"); setupKeys.remove("${network.wire}:$chatId"); return AppResult.Failure(outcome.toError()) }
            }
        }
        // A new key next time: W14 again while it is setting up gives a fresh challenge (GW6b); it must not replay this one.
        setupKeys.remove("${network.wire}:$chatId")
        val listing = WalletListing(started.walletId, WalletOwner(WalletOwnerType.Group, null, chatId, ""), walletStatus(started.status), true, emptyList())
        return AppResult.Success(GroupWallet(chatId, walletStatus(started.status), listing))
    }

    override suspend fun awaitGroupWallet(chatId: String): AppResult<GroupWallet> {
        val network = net()
        return on(network) { awaitGroupWalletOn(chatId) }
    }

    private suspend fun awaitGroupWalletOn(chatId: String): AppResult<GroupWallet> {
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

    /**
     * W5 for the network the payment is on: the phone's row says which, a pinned call says which, else the selected
     * one is asked first and then, for someone with both, the other (a Testnet card opened while Mainnet is selected).
     */
    override suspend fun loadTransfer(transferId: String): AppResult<Transfer> {
        val existing = dao.transfer(transferId)
        val known = com.circaso.feature.wallet.domain.pinnedNetwork() ?: existing?.let { WalletNetwork.of(it.network) }?.takeIf { usable(it) }
        val first = known ?: net()
        val read = on(first) { readTransfer(transferId, first) }
        val missing = ((read as? AppResult.Failure)?.error as? AppError.Api)?.let { it.httpStatus == 404 || it.httpStatus == 403 } == true
        val other = WalletNetwork.entries.firstOrNull { it != first }
        if (!missing || known != null || other == null || !networks.state.value.let { s -> s.known && !s.legacy && s.options.any { it.network == other } }) return read
        val second = on(other) { readTransfer(transferId, other) }
        return if (second is AppResult.Success) second else read
    }

    /** The person may use [network] (or there is only one, as before networks). */
    private fun usable(network: WalletNetwork): Boolean = networks.state.value.let { s -> !s.known || s.legacy || s.options.any { it.network == network } }

    private suspend fun readTransfer(transferId: String, asked: WalletNetwork): AppResult<Transfer> = remote.transfer(transferId).map { dto ->
        val existing = dao.transfer(transferId)
        val row = dto.toEntity(existing, asked)
        save(row, existing)
        row.toDomain()
    }

    override fun transfers(ids: Collection<String>): Flow<List<Transfer>> =
        if (ids.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList()) else combine(dao.observeTransfersIn(ids.distinct()), names.saved) { rows, saved -> rows.map { it.toDomain().named(saved) } }

    /** Saves the row; an outgoing payment that has just been confirmed or failed is announced on [settled]. */
    private suspend fun save(row: TransferEntity, existing: TransferEntity?) {
        dao.upsert(row)
        val ended = row.state == "CONFIRMED" || row.state == "FAILED"
        if (ended && existing?.state != row.state && row.direction == TransferDirection.Out.name) settledOut.tryEmit(row.toDomain())
    }

    /**
     * A frame is handled for the network it names (`network`, wallet networks), so money on the network that isn't on
     * screen updates that network's copy and leaves the visible one alone. An older server names none: the chain says
     * (chains never overlap), else the phone's row, else the selected network.
     */
    override suspend fun onFrame(type: String, data: JsonObject?) {
        fun str(k: String) = data?.get(k)?.jsonPrimitive?.contentOrNull
        val network = WalletNetwork.of(str("network"))
            ?: WalletNetwork.ofChainOrNull(str("blockchain"))?.takeIf { usable(it) }
            ?: (str("transferId") ?: str("transactionId"))?.let { dao.transfer(it) }?.let { WalletNetwork.of(it.network) }?.takeIf { usable(it) }
            ?: net()
        on(network) { handleFrame(type, data, network) }
    }

    private suspend fun handleFrame(type: String, data: JsonObject?, network: WalletNetwork) {
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
                    existing?.copy(state = "CONFIRMED", chatId = existing.chatId ?: str("chatId"), counterpartName = existing.counterpartName ?: str("fromName"), counterpartUserId = existing.counterpartUserId ?: str("fromUserId"), updatedAt = now)
                        ?: TransferEntity(
                            transferId = key, direction = TransferDirection.In.name, state = "CONFIRMED", blockchain = str("blockchain").orEmpty(),
                            fromAddress = "", toUserId = null, toAddress = "", asset = asset,
                            amountMinor = amountMinor, amount = amount?.get("amount")?.jsonPrimitive?.contentOrNull ?: "0",
                            feeMinor = null, chatId = str("chatId"), txHash = null, failureReason = null, counterpartName = str("fromName"), note = null,
                            createdAt = now, updatedAt = now, counterpartUserId = str("fromUserId"), network = network.wire,
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
            "setup.requested" -> _setupRequestedBy.update { it + (network to (str("fromName") ?: "Someone")) }
            else -> Diagnostics.note("wallet: frame $type ignored")
        }
    }

    // A copy read from the phone says which network's row it came from, so no screen can pair it with the other's name.
    private fun decodeCached(row: WalletCacheEntity): Wallet? = try {
        json.decodeFromString(WalletResponse.serializer(), row.json).toDomain(row.fetchedAt).copy(network = WalletNetwork.of(row.network))
    } catch (e: Exception) { null }

    private fun decode(row: WalletSnapshotEntity): Wallet? = try {
        json.decodeFromString(WalletResponse.serializer(), row.json).toDomain(row.fetchedAt).copy(network = WalletNetwork.of(row.network))
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

internal fun TransactionDto.toActivity(walletId: String, asked: WalletNetwork = WalletNetwork.TESTNET): WalletActivityEntity = WalletActivityEntity(
    walletId = walletId, rowId = id, transferId = transferId, direction = direction, state = state, blockchain = blockchain,
    asset = amount.asset, amountMinor = amount.amountMinor, amount = amount.amount,
    counterpartName = counterparty?.displayName ?: counterparty?.handle?.let { "@$it" }, counterpartHandle = counterparty?.handle,
    counterpartAvatar = counterparty?.avatarUrl, counterpartUserId = counterparty?.userId, counterpartAddress = counterparty?.address,
    // A space on the other side (phase 3) has no name on the wire: kept as "space:{id}" and named from the phone's spaces.
    counterpartGroupId = counterparty?.groupId ?: (counterparty?.spaceId ?: if (direction == "OUT") toSpaceId else fromSpaceId)?.let { SpacePrefix + it },
    chatId = chatId, txHash = txHash, occurredAt = isoMillis(occurredAt) ?: nowMillis(),
    network = networkOf(network, blockchain, asked),
)

/** A row's network: what the server said, else what its chain says (chains never overlap), else what was asked for. */
internal fun networkOf(answered: String?, blockchain: String?, asked: WalletNetwork): String =
    (WalletNetwork.of(answered) ?: WalletNetwork.ofChainOrNull(blockchain) ?: asked).wire

internal fun WalletActivityEntity.toDomain(): Transfer {
    val incoming = direction == "IN"
    return Transfer(
        transferId = transferId ?: rowId, direction = if (incoming) TransferDirection.In else TransferDirection.Out,
        state = when (state) { "AWAITING_SIGNATURE" -> TransferState.AwaitingSignature; "SUBMITTED" -> TransferState.Submitted; "CONFIRMED" -> TransferState.Confirmed; "FAILED" -> TransferState.Failed; else -> TransferState.Expired },
        blockchain = blockchain, from = if (incoming) counterpartAddress.orEmpty() else "", toUserId = if (incoming) null else counterpartUserId,
        toAddress = if (incoming) "" else counterpartAddress.orEmpty(), amount = Amount(asset, amountMinor, amount), fee = null, chatId = chatId,
        txHash = txHash, failureReason = null, counterpartName = counterpartName, note = null, createdAt = occurredAt, updatedAt = occurredAt,
        counterpartHandle = counterpartHandle, counterpartAvatar = counterpartAvatar, walletId = walletId, counterpartGroupId = counterpartGroupId,
        counterpartUserId = counterpartUserId, network = WalletNetwork.of(network) ?: WalletNetwork.TESTNET,
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

internal fun TransferResponse.toEntity(existing: TransferEntity?, asked: WalletNetwork = WalletNetwork.TESTNET): TransferEntity {
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
        counterpartUserId = existing?.counterpartUserId ?: to.userId.takeIf { direction != "IN" },
        network = networkOf(network, blockchain, asked),
    )
}

internal fun TransactionDto.toEntity(key: String, existing: TransferEntity?, asked: WalletNetwork = WalletNetwork.TESTNET): TransferEntity {
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
        counterpartUserId = party?.userId ?: existing?.counterpartUserId,
        network = networkOf(network, blockchain, asked),
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
    counterpartUserId = counterpartUserId ?: toUserId,
    network = WalletNetwork.of(network) ?: WalletNetwork.TESTNET,
)

private fun QuoteTransferResponse.toDomain() = PaymentQuote(
    feeMinor = fee.amountMinor, rateBps = fee.rateBps, minimum = fee.minimum == true, totalMinor = total.amountMinor,
    balanceMinor = balance?.amountMinor, maxAmountMinor = maxAmount?.amountMinor,
    problem = when (problem) {
        "INSUFFICIENT_FUNDS" -> QuoteProblem.InsufficientFunds
        "PER_PAYMENT_LIMIT" -> QuoteProblem.PerPaymentLimit
        "DAILY_LIMIT" -> QuoteProblem.DailyLimit
        "INSUFFICIENT_GAS" -> QuoteProblem.InsufficientGas
        else -> null
    },
    fitsAt = fitsAt, leftMinor = limit?.left?.amountMinor, dailyMaxMinor = limit?.dailyMax?.amountMinor, moreAt = limit?.moreAt,
    quoteId = quoteId, expiresAt = expiresAt,
)

