package com.circaso.feature.ramp.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.feature.ramp.data.remote.AmountDto
import com.circaso.feature.ramp.data.remote.OrderResponse
import com.circaso.feature.ramp.data.remote.OrderUpdatedFrame
import com.circaso.feature.ramp.data.remote.QuoteRequest
import com.circaso.feature.ramp.data.remote.RampRemote
import com.circaso.feature.ramp.data.remote.StartOffRampRequest
import com.circaso.feature.ramp.data.remote.StartOnRampRequest
import com.circaso.feature.ramp.data.remote.SavedSale
import com.circaso.feature.ramp.domain.MpesaSales
import com.circaso.feature.ramp.domain.RampAmount
import com.circaso.feature.ramp.domain.RampDirection
import com.circaso.feature.ramp.domain.SaleLabel
import com.circaso.feature.ramp.data.remote.SavedIdentity
import com.circaso.feature.ramp.data.remote.VerificationDto
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.core.sync.nowMillis
import com.circaso.feature.ramp.domain.IdentitySnapshot
import com.circaso.feature.ramp.domain.KycStart
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.KycUpdate
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.domain.RampCodes
import com.circaso.feature.ramp.domain.RampMoney
import com.circaso.feature.ramp.domain.RampOrder
import com.circaso.feature.ramp.domain.RampOrderState
import com.circaso.feature.ramp.domain.RampOrderUpdate
import com.circaso.feature.ramp.domain.RampPhoneStore
import com.circaso.feature.ramp.domain.RampQuote
import com.circaso.feature.ramp.domain.RampRepository
import com.circaso.feature.ramp.domain.RampStatus
import com.circaso.feature.wallet.domain.MinorUnits
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The ramp's data layer. Nothing here is queued or assumed: an order is whatever R3, R4, R6 or a frame last said.
 * The only things kept on the phone are the person's own number (secure storage, [phones]) and, per network, which
 * transaction hashes were M-Pesa top-ups ([savedTopUps], a small setting wiped at sign-out with the wallet's).
 */
class RampRepositoryImpl(
    private val remote: RampRemote,
    private val networks: WalletNetworks,
    private val phones: RampPhoneStore,
    private val json: Json,
    /** The saved hash → masked number map of one network (JSON), and its writer. */
    private val savedTopUps: (WalletNetwork) -> Flow<String?>,
    private val saveTopUps: suspend (WalletNetwork, String) -> Unit,
    /** The USDC arrived: the wallet's balance and history are read again (for the network of the coroutine). */
    private val onArrived: suspend () -> Unit = {},
    /** For the one thing done beside a call: reading the orders after R1, so old top-ups are labelled in the wallet. */
    private val scope: CoroutineScope? = null,
    /** One network's last known identity check (JSON), and its writer: the profile's copy for when there is no connection. */
    private val savedIdentity: (WalletNetwork) -> Flow<String?> = { flowOf(null) },
    private val saveIdentity: suspend (WalletNetwork, String) -> Unit = { _, _ -> },
    private val now: () -> Long = ::nowMillis,
    /** One network's sales paid from this phone (JSON), and its writer: the wallet history's "Cashed out to M-Pesa". */
    private val savedSales: (WalletNetwork) -> Flow<String?> = { flowOf(null) },
    private val saveSales: suspend (WalletNetwork, String) -> Unit = { _, _ -> },
) : RampRepository {
    private val _updates = MutableSharedFlow<RampOrderUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<RampOrderUpdate> = _updates

    private val _kycUpdates = MutableSharedFlow<KycUpdate>(extraBufferCapacity = 16)
    override val kycUpdates: Flow<KycUpdate> = _kycUpdates

    // Identity verification is the server's alone to decide: the state is whatever R0, the read, R1 or a frame last
    // said. That last word (the state and its reason code, nothing personal) is kept per network so the profile can
    // show it offline; the session's token and link live only as long as the launch they are for.
    override suspend fun startKyc(idempotencyKey: String): AppResult<KycStart> {
        val network = networks.effective()
        return remote.startKyc(idempotencyKey).map { it.toDomain() }.also { heard(network, it.map { start -> start.verification }) }
    }

    override suspend fun kyc(): AppResult<RampVerification> {
        val network = networks.effective()
        return remote.kyc().map { it.toDomain() }.also { heard(network, it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val identity: Flow<IdentitySnapshot?> = networks.followed.flatMapLatest { network -> savedIdentity(network).map { decodeIdentity(it) } }.distinctUntilChanged()

    private val identityLock = Mutex()

    private fun decodeIdentity(text: String?): IdentitySnapshot? {
        val saved = text?.let { runCatching { json.decodeFromString(SavedIdentity.serializer(), it) }.getOrNull() } ?: return null
        val verification = saved.status?.let { RampVerification(KycStatus.of(it), saved.required, saved.canStart, saved.reason, saved.retryAt, saved.submitted) }
        return IdentitySnapshot(verification, saved.unavailable, saved.checkedAt)
    }

    /**
     * What one of the verification's own routes just answered for [network]: a state replaces the copy; "not open here"
     * (`ramp.unavailable`) is remembered as that; anything else (no connection, a fault) leaves the copy as it was.
     */
    private suspend fun heard(network: WalletNetwork, result: AppResult<RampVerification>) {
        when (result) {
            is AppResult.Success -> remember(network, result.value)
            is AppResult.Failure -> if ((result.error as? AppError.Api)?.code == RampCodes.Unavailable) {
                save(network, SavedIdentity(unavailable = true, checkedAt = now()))
            }
        }
    }

    private suspend fun remember(network: WalletNetwork, v: RampVerification) =
        save(network, SavedIdentity(KycStatus.wire(v.status), v.required, v.canStart, v.reason, v.retryAt, v.submitted, unavailable = false, checkedAt = now()))

    /** Keeping the copy must never fail the call it rides on. */
    private suspend fun save(network: WalletNetwork, identity: SavedIdentity) {
        identityLock.withLock { runCatching { saveIdentity(network, json.encodeToString(SavedIdentity.serializer(), identity)) } }
    }

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val topUpsLock = Mutex()

    /** Orders whose arrival already refreshed the wallet, so a poll and a frame for the same order do it once. */
    private val arrived = mutableSetOf<String>()
    private val arrivedLock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val topUps: Flow<Map<String, String>> = networks.followed.flatMapLatest { network -> savedTopUps(network).map { decode(it) } }.distinctUntilChanged()

    private fun decode(text: String?): Map<String, String> = text?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() } ?: emptyMap()

    override suspend fun status(): AppResult<RampStatus> {
        val network = networks.effective()
        val result = remote.status().map { it.toDomain() }
        // Open to this person: the top-ups this phone hasn't seen (made on another phone) get their wallet label too.
        if (result is AppResult.Success) {
            // R1 carries the check in full: the profile's copy follows it. (R1 refusing says nothing about the check.)
            remember(result.value.network ?: network, result.value.verification)
            scope?.launch(PinnedNetwork(network)) { runCatching { refreshOrders() } }
        }
        return result
    }

    override suspend fun quote(blockchain: String, shillingsMinor: String, idempotencyKey: String): AppResult<RampQuote> {
        // Whole shillings on the wire, as R2 asks: the cents are always "00" by the time they get here.
        val whole = MinorUnits.toDisplay(shillingsMinor, RampMoney.FiatDecimals, minFraction = 0).replace(",", "")
        return remote.quote(QuoteRequest(direction = OnRamp, blockchain = blockchain, fiat = AmountDto(amount = whole)), idempotencyKey).map { it.toDomain() }
    }

    override suspend fun buy(quoteId: String, phone: String, idempotencyKey: String): AppResult<RampOrder> {
        val result = remote.buy(StartOnRampRequest(quoteId, phone), idempotencyKey)
        when {
            // The server took the number as the account's own: remembered, so the next top-up doesn't ask for it.
            result is AppResult.Success -> phones.put(phone)
            (result as? AppResult.Failure)?.error.let { (it as? AppError.Api)?.code } == RampCodes.PhoneNotYours -> phones.clear()
        }
        return result.map { it.seen() }
    }

    override suspend fun sellQuote(blockchain: String, usdcMinor: String?, shillingsMinor: String?, idempotencyKey: String): AppResult<RampQuote> {
        // One side only: the USDC to send (as a decimal, e.g. "10.5"), or the whole shillings to receive.
        val crypto = usdcMinor?.let { AmountDto(amount = MinorUnits.toDisplay(it, RampMoney.CoinDecimals, minFraction = 0).replace(",", "")) }
        val fiat = if (crypto == null) shillingsMinor?.let { AmountDto(amount = MinorUnits.toDisplay(it, RampMoney.FiatDecimals, minFraction = 0).replace(",", "")) } else null
        return remote.quote(QuoteRequest(direction = OffRamp, blockchain = blockchain, fiat = fiat, crypto = crypto), idempotencyKey).map { it.toDomain() }
    }

    override suspend fun sell(quoteId: String, phone: String, idempotencyKey: String): AppResult<RampOrder> {
        val result = remote.sell(StartOffRampRequest(quoteId, phone), idempotencyKey)
        when {
            // The server took the number as the account's own: remembered, so the next sale or top-up doesn't ask for it.
            result is AppResult.Success -> phones.put(phone)
            (result as? AppResult.Failure)?.error.let { (it as? AppError.Api)?.code } == RampCodes.PhoneNotYours -> phones.clear()
        }
        return result.map { it.seen() }
    }

    private val salesLock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val sales: Flow<MpesaSales> = networks.followed.flatMapLatest { network -> savedSales(network).map { MpesaSales.of(decodeSales(it).map { s -> s.label() }) } }.distinctUntilChanged()

    private fun decodeSales(text: String?): List<SavedSale> = text?.let { runCatching { json.decodeFromString(salesSerializer, it) }.getOrNull() } ?: emptyList()

    override suspend fun saleSent(order: RampOrder, transferId: String) {
        val network = order.network ?: networks.effective()
        editSales(network) { list ->
            val kept = list.firstOrNull { it.orderId == order.orderId }
            list.filterNot { it.orderId == order.orderId } + SavedSale.of(order, transferId.lowercase(), kept)
        }
    }

    override suspend fun saleAttempt(order: RampOrder, on: Boolean) {
        val network = order.network ?: networks.effective()
        editSales(network) { list ->
            val kept = list.firstOrNull { it.orderId == order.orderId }
            when {
                kept != null -> list.map { if (it.orderId == order.orderId) it.copy(attempted = on) else it }
                on -> list + SavedSale.of(order, null, null).copy(attempted = true)
                else -> list
            }
        }
    }

    override suspend fun saleAttempted(orderId: String): Boolean =
        decodeSales(savedSales(networks.effective()).first()).firstOrNull { it.orderId == orderId }?.attempted == true

    override suspend fun saleTransfer(orderId: String): String? =
        decodeSales(savedSales(networks.effective()).first()).firstOrNull { it.orderId == orderId }?.transferId

    /** A sale this phone paid moved on: its label follows (the receipt once paid out, the refund's hash once back). */
    private suspend fun noteSale(order: RampOrder) {
        val network = order.network ?: networks.effective()
        editSales(network) { list ->
            val kept = list.firstOrNull { it.orderId == order.orderId } ?: return@editSales list
            list.map { if (it.orderId == order.orderId) SavedSale.of(order, kept.transferId, kept) else it }
        }
    }

    /** Keeping a label must never fail the call it rides on. */
    private suspend fun editSales(network: WalletNetwork, change: (List<SavedSale>) -> List<SavedSale>) {
        salesLock.withLock {
            runCatching {
                val before = decodeSales(savedSales(network).first())
                val after = change(before).takeLast(MaxRemembered)
                if (after != before) saveSales(network, json.encodeToString(salesSerializer, after))
            }
        }
    }

    private val salesSerializer = kotlinx.serialization.builtins.ListSerializer(SavedSale.serializer())

    override suspend fun order(orderId: String): AppResult<RampOrder> = remote.order(orderId).map { it.seen() }

    override suspend fun cancel(orderId: String, idempotencyKey: String): AppResult<RampOrder> = remote.cancel(orderId, idempotencyKey).map { it.seen() }

    override suspend fun refreshOrders(): AppResult<Unit> {
        val network = networks.effective()
        return remote.orders().map { page -> remember(network, page.orders) }
    }

    override suspend fun ownPhone(): String? = phones.get()

    /**
     * An order as the server just gave it: a completed top-up is remembered and, the first time, refreshes the wallet;
     * a sale's label follows it, and a sale whose USDC came back refreshes the wallet once.
     */
    private suspend fun OrderResponse.seen(): RampOrder {
        val order = toDomain()
        if (order.sale) {
            noteSale(order)
            if (order.state == RampOrderState.Refunded) arrivedOnce(order.orderId)
            return order
        }
        if (order.state == RampOrderState.Completed) {
            remember(order.network ?: networks.effective(), listOf(this))
            arrivedOnce(order.orderId)
        }
        return order
    }

    private suspend fun arrivedOnce(orderId: String) {
        val first = arrivedLock.withLock { arrived.add(orderId) }
        if (first) runCatching { onArrived() }
    }

    private suspend fun remember(network: WalletNetwork, orders: List<OrderResponse>) {
        // Top-ups only: a sale's hash is the USDC leaving, or coming back, never money bought.
        val done = orders.filter { RampDirection.of(it.direction) == RampDirection.OnRamp && RampOrderState.of(it.state) == RampOrderState.Completed && !it.txHash.isNullOrBlank() }
        if (done.isEmpty()) return
        topUpsLock.withLock {
            val saved = decode(savedTopUps(network).first())
            val merged = saved + done.associate { it.txHash!!.lowercase() to it.maskedPhone }
            if (merged != saved) saveTopUps(network, json.encodeToString(mapSerializer, merged.entries.toList().takeLast(MaxRemembered).associate { it.key to it.value }))
        }
    }

    override suspend fun onFrame(type: String, data: JsonObject?) {
        if (data == null) return
        if (type == KycUpdated) {
            val moved = runCatching { json.decodeFromJsonElement(VerificationDto.serializer(), data) }.getOrNull() ?: return
            val network = WalletNetwork.of(moved.network)
            // The frame says which network it is for; one without a network can't be filed, so it is only passed on.
            if (network != null) remember(network, moved.toDomain())
            _kycUpdates.emit(KycUpdate(network, moved.toDomain()))
            return
        }
        if (type != OrderUpdated) return // a newer server's frame types are ignored
        val frame = runCatching { json.decodeFromJsonElement(OrderUpdatedFrame.serializer(), data) }.getOrNull() ?: return
        val state = RampOrderState.of(frame.state)
        val direction = RampDirection.of(frame.direction)
        _updates.emit(RampOrderUpdate(frame.orderId, state, frame.failureReason, direction))
        if (direction == RampDirection.OffRamp) {
            // The wallet's label follows every move, whether or not the sale's screen is open.
            val labelled = WalletNetwork.of(frame.network) ?: WalletNetwork.ofChainOrNull(frame.blockchain) ?: networks.effective()
            if (state != RampOrderState.Unknown) editSales(labelled) { list -> list.map { if (it.orderId == frame.orderId) it.copy(state = SavedSale.wire(state)) else it } }
            // A sale paid out or refunded: read once, so its wallet label gets the receipt or the refund (and a refund
            // refreshes the balance), whether or not its screen is open.
            if (state == RampOrderState.Completed || state == RampOrderState.Refunded) {
                val network = WalletNetwork.of(frame.network) ?: WalletNetwork.ofChainOrNull(frame.blockchain) ?: networks.effective()
                withContext(PinnedNetwork(network)) { runCatching { remote.order(frame.orderId).map { it.seen() } } }
            }
            return
        }
        if (state == RampOrderState.Completed) {
            // The frame names the chain, and a chain is on one network: the wallet read is for that network whatever
            // the Wallet tab shows.
            val network = WalletNetwork.ofChainOrNull(frame.blockchain) ?: networks.effective()
            withContext(PinnedNetwork(network)) {
                arrivedOnce(frame.orderId)
                // The hash isn't in the frame: the order is read once so its wallet row can say "Added from M-Pesa".
                runCatching { remote.order(frame.orderId).map { remember(network, listOf(it)) } }
            }
        }
    }

    override suspend fun reload() { _updates.emit(RampOrderUpdate(RampOrderUpdate.ReloadAll, RampOrderState.Unknown, null)) }

    companion object {
        const val OrderUpdated = "order.updated"
        const val KycUpdated = "kyc.updated"
        const val OnRamp = "ON_RAMP"
        const val OffRamp = "OFF_RAMP"
        /** The setting that holds one network's sales paid from this phone (under `text.wallet.`, wiped at sign-out). */
        fun salesKey(network: WalletNetwork): String = "wallet.ramp.sales." + network.wire
        /** The setting that holds one network's last known identity check (under `text.wallet.`, wiped at sign-out). */
        fun identityKey(network: WalletNetwork): String = "wallet.ramp.kyc." + network.wire
        /** The setting that holds one network's top-up labels (under `text.wallet.`, wiped at sign-out). */
        fun topUpsKey(network: WalletNetwork): String = "wallet.ramp.topups." + network.wire
        private const val MaxRemembered = 200
    }
}
