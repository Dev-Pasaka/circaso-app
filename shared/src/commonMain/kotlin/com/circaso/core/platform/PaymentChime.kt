package com.circaso.core.platform

import com.circaso.core.sync.nowMillis
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The two-note chime Add Money plays when a top-up's USDC lands (`files/payment_chime.wav`, made by
 * `scripts/make_payment_chime.py`). Each platform plays it its own way and **never when the phone is silenced**:
 * iOS through System Sound Services, which the Ring/Silent switch mutes and which leaves the app's audio session
 * (voice notes, calls, Asha's voice) alone; Android on the notification stream, only in the normal ringer mode.
 * Loaded ahead, so it starts on the frame the card settles.
 */
interface PaymentChime {
    /** Gets the sound ready to play at once (it is read and decoded off the main thread). */
    fun prepare() {}

    /** Plays it now, unless the phone is silenced. Never throws. */
    fun play()
}

/** Where there is no player (tests, previews, a platform without one). */
object NoPaymentChime : PaymentChime {
    override fun play() = Unit
}

/**
 * The money sounds the app plays itself, and when: the coins for money received, the chime for a top-up landing.
 * Both follow the one setting, **You → Money sound** ([soundOn], on unless turned off), and the phone's own silent
 * mode (the players' part).
 *
 * - **Once per order.** The chime rings the first time a top-up's success is shown on this phone, watched as it
 *   lands or opened later (a push, the open order); the order ids it has rung for are kept ([rung]/[saveRung], the
 *   newest [Remembered]), so opening it again stays quiet. With the setting off the order still counts as heard.
 * - **One sound per arrival.** The same USDC also arrives as an incoming payment, which would play the coins: while
 *   a paid top-up is on its way on screen ([follow]) and for a short while after it rings, the coins keep quiet.
 * - **In time with the card.** Whether a landing rings is decided ahead ([armLanding]: heard before? sound on? the
 *   sound loaded), so on the frame the card settles [ringLanding] plays at once, with nothing read from storage first;
 *   the order is written down as heard just after ([markHeard]).
 */
class MoneySounds(
    private val chime: PaymentChime,
    private val coins: () -> Unit,
    private val soundOn: suspend () -> Boolean,
    private val rung: suspend () -> String?,
    private val saveRung: suspend (String) -> Unit,
    private val now: () -> Long = ::nowMillis,
) {
    private val lock = Mutex()
    @kotlin.concurrent.Volatile private var followingOrder: String? = null
    @kotlin.concurrent.Volatile private var quietUntil = 0L

    /** Loads the chime ahead (Add Money opened), so the first landing never waits on decoding. */
    fun prepare() = chime.prepare()

    /** A paid top-up is on its way on screen until it lands: its USDC gets the chime, not the coins. */
    fun follow(orderId: String) {
        followingOrder = orderId
        chime.prepare()
    }

    /** [orderId] is no longer on screen (or has rung): incoming money plays the coins again (another screen's order stays). */
    fun unfollow(orderId: String) {
        if (followingOrder == orderId) followingOrder = null
    }

    /** Money arrived in the wallet: the coins, unless a top-up's chime has it. */
    suspend fun moneyReceived() {
        if (followingOrder != null || now() < quietUntil) return
        if (soundOn()) coins()
    }

    /** Whether [orderId]'s landing has already been heard on this phone. */
    suspend fun landedBefore(orderId: String): Boolean = orderId in heard()

    /**
     * [orderId] has landed: whether its landing is still to be heard on this phone (false: heard before). When it is,
     * the setting is read now and the sound made ready, so [ringLanding] needs nothing more.
     */
    suspend fun armLanding(orderId: String): Boolean {
        if (landedBefore(orderId)) return false
        armed[orderId] = soundOn()
        chime.prepare()
        return true
    }

    /** The card has settled on [orderId]'s USDC: the chime now, if it was armed with the sound on. Returns whether it rang. */
    fun ringLanding(orderId: String): Boolean {
        val on = armed.remove(orderId) ?: return false
        quietUntil = now() + QuietMillis
        if (on) chime.play()
        return on
    }

    /** [orderId]'s landing has been heard (or felt, with the sound off): it never rings again on this phone. */
    suspend fun markHeard(orderId: String) {
        lock.withLock {
            val before = heard()
            if (orderId !in before) saveRung((before + orderId).takeLast(Remembered).joinToString(","))
        }
    }

    /** All three at once (arm, ring, mark): the chime for [orderId] if its landing is new. Returns whether it rang. */
    suspend fun topUpLanded(orderId: String): Boolean {
        if (!armLanding(orderId)) return false
        val rang = ringLanding(orderId)
        markHeard(orderId)
        return rang
    }

    private val armed = mutableMapOf<String, Boolean>()

    private suspend fun heard(): List<String> = rung()?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    companion object {
        /** How many orders' chimes are remembered: far more than anyone opens again. */
        const val Remembered = 50
        /** After the chime, an incoming payment within this long is the same USDC: no coins over it. */
        const val QuietMillis = 30_000L
    }
}
