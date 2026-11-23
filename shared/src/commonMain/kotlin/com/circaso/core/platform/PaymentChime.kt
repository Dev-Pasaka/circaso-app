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
 *   a top-up is being followed on screen ([follow]) and for a short while after it rings, the coins keep quiet.
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

    /** A top-up is being followed on screen until it lands: its USDC gets the chime, not the coins. */
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
     * [orderId]'s USDC is in the wallet and its card has just settled in front of the person: the chime, once.
     * Returns whether it rang (false: heard before, or the setting is off).
     */
    suspend fun topUpLanded(orderId: String): Boolean {
        val first = lock.withLock {
            val before = heard()
            if (orderId in before) return@withLock false
            saveRung((before + orderId).takeLast(Remembered).joinToString(","))
            true
        }
        if (!first) return false
        quietUntil = now() + QuietMillis
        if (!soundOn()) return false
        chime.play()
        return true
    }

    private suspend fun heard(): List<String> = rung()?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    companion object {
        /** How many orders' chimes are remembered: far more than anyone opens again. */
        const val Remembered = 50
        /** After the chime, an incoming payment within this long is the same USDC: no coins over it. */
        const val QuietMillis = 30_000L
    }
}
