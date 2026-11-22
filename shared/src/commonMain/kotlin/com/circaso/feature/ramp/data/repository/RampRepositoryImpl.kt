package com.circaso.feature.ramp.data.repository

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.common.map
import com.circaso.feature.ramp.data.remote.AmountDto
import com.circaso.feature.ramp.data.remote.OrderResponse
import com.circaso.feature.ramp.data.remote.OrderUpdatedFrame
import com.circaso.feature.ramp.data.remote.QuoteRequest
import com.circaso.feature.ramp.data.remote.RampRemote
import com.circaso.feature.ramp.data.remote.StartOnRampRequest
import com.circaso.feature.ramp.data.remote.toDomain
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
) : RampRepository {
    private val _updates = MutableSharedFlow<RampOrderUpdate>(extraBufferCapacity = 16)
    override val updates: Flow<RampOrderUpdate> = _updates

    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val topUpsLock = Mutex()

    /** Orders whose arrival already refreshed the wallet, so a poll and a frame for the same order do it once. */
    private val arrived = mutableSetOf<String>()
    private val arrivedLock = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val topUps: Flow<Map<String, String>> = networks.followed.flatMapLatest { network -> savedTopUps(network).map { decode(it) } }.distinctUntilChanged()

    private fun decode(text: String?): Map<String, String> = text?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() } ?: emptyMap()

    override suspend fun status(): AppResult<RampStatus> {
        val result = remote.status().map { it.toDomain() }
        // Open to this person: the top-ups this phone hasn't seen (made on another phone) get their wallet label too.
        if (result is AppResult.Success) {
            val network = networks.effective()
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

    override suspend fun order(orderId: String): AppResult<RampOrder> = remote.order(orderId).map { it.seen() }

    override suspend fun cancel(orderId: String, idempotencyKey: String): AppResult<RampOrder> = remote.cancel(orderId, idempotencyKey).map { it.seen() }

    override suspend fun refreshOrders(): AppResult<Unit> {
        val network = networks.effective()
        return remote.orders().map { page -> remember(network, page.orders) }
    }

    override suspend fun ownPhone(): String? = phones.get()

    /** An order as the server just gave it: a completed one is remembered and, the first time, refreshes the wallet. */
    private suspend fun OrderResponse.seen(): RampOrder {
        val order = toDomain()
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
        val done = orders.filter { RampOrderState.of(it.state) == RampOrderState.Completed && !it.txHash.isNullOrBlank() }
        if (done.isEmpty()) return
        topUpsLock.withLock {
            val saved = decode(savedTopUps(network).first())
            val merged = saved + done.associate { it.txHash!!.lowercase() to it.maskedPhone }
            if (merged != saved) saveTopUps(network, json.encodeToString(mapSerializer, merged.entries.toList().takeLast(MaxRemembered).associate { it.key to it.value }))
        }
    }

    override suspend fun onFrame(type: String, data: JsonObject?) {
        if (type != OrderUpdated || data == null) return // a newer server's frame types are ignored
        val frame = runCatching { json.decodeFromJsonElement(OrderUpdatedFrame.serializer(), data) }.getOrNull() ?: return
        val state = RampOrderState.of(frame.state)
        _updates.emit(RampOrderUpdate(frame.orderId, state, frame.failureReason))
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
        const val OnRamp = "ON_RAMP"
        /** The setting that holds one network's top-up labels (under `text.wallet.`, wiped at sign-out). */
        fun topUpsKey(network: WalletNetwork): String = "wallet.ramp.topups." + network.wire
        private const val MaxRemembered = 200
    }
}
