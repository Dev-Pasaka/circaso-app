package com.circaso.feature.wallet

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.feature.wallet.data.repository.WalletRepositoryImpl
import com.circaso.feature.wallet.domain.BiometricOfferRule
import com.circaso.feature.wallet.domain.BiometricSignals
import com.circaso.feature.wallet.domain.PaymentRequest
import com.circaso.feature.wallet.domain.SignOutcome
import com.circaso.feature.wallet.domain.SignPurpose
import com.circaso.feature.wallet.domain.SignSubject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The offer-once rule: after a PIN approval, only where it can work, never twice. */
class BiometricOfferRuleTest {
    @Test fun offeredWhenEnrolledOffAndNotYetAsked() = assertTrue(BiometricOfferRule.shouldOffer(supported = true, enabled = false, alreadyOffered = false, lost = false))
    @Test fun neverTwice() = assertFalse(BiometricOfferRule.shouldOffer(supported = true, enabled = false, alreadyOffered = true, lost = false))
    @Test fun notWhenAlreadyOn() = assertFalse(BiometricOfferRule.shouldOffer(supported = true, enabled = true, alreadyOffered = false, lost = false))
    @Test fun notOnAPhoneWithNothingEnrolled() = assertFalse(BiometricOfferRule.shouldOffer(supported = false, enabled = false, alreadyOffered = false, lost = false))
    @Test fun notRightAfterCircleSaidItStopped() = assertFalse(BiometricOfferRule.shouldOffer(supported = true, enabled = false, alreadyOffered = false, lost = true))
}

/** Which Circle codes mean a wallet's biometric approval is gone (it falls back to the PIN, and the app stops showing it as on). */
class BiometricSignalsTest {
    @Test fun newFingerprintsOrTurnedOffMeanGone() {
        assertTrue(BiometricSignals.lost("biometricsKeyPermanentlyInvalidated", usedBiometrics = true))
        assertTrue(BiometricSignals.lost("biometricsUserDisableForPin", usedBiometrics = true))
        assertTrue(BiometricSignals.lost("biometricsUserNotAllowPermission", usedBiometrics = false))
    }

    @Test fun lockoutsPassOnTheirOwn() {
        assertFalse(BiometricSignals.lost("biometricsUserLockout", usedBiometrics = true))
        assertFalse(BiometricSignals.lost("biometricsUserLockoutPermanent", usedBiometrics = true))
        assertFalse(BiometricSignals.lost("incorrectUserPin", usedBiometrics = true))
        assertFalse(BiometricSignals.lost(null, usedBiometrics = true))
    }

    @Test fun skippingTheReEnrolPageCountsOnlyWhenBiometricsWereOn() {
        assertTrue(BiometricSignals.lost("biometricsUserSkip", usedBiometrics = true), "declined Circle's 'update biometrics' after new fingerprints")
        assertFalse(BiometricSignals.lost("biometricsUserSkip", usedBiometrics = false))
    }
}

/** Biometrics first, the PIN as fallback: what the repository hands Circle, and when it reports a PIN approval. */
class BiometricSigningTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val dao = FakeWalletDao()
    private val remote = FakeWalletRemote()
    private val signer = FakeSigner(onExecute = { remote.status = "ACTIVE" })
    private val on = mutableSetOf<String?>()
    private val pinApprovals = mutableListOf<SignSubject>()
    private val repo = WalletRepositoryImpl(
        remote, dao, { signer }, { "KES" }, json,
        biometricsOn = { it in on },
        onApprovedWithPin = { pinApprovals += it },
    )

    private fun payment(subject: SignSubject = SignSubject.Personal, from: String? = null) =
        PaymentRequest("BASE-SEPOLIA", "u-2", null, "5000000", chatId = "chat-1", fromWalletId = from, subject = subject)

    @Test fun aWalletWithBiometricsOnSignsWithThem() = runTest {
        on += null
        assertIs<AppResult.Success<*>>(repo.pay(payment(), "key-1"))
        assertTrue(signer.sessions.single().biometrics, "Circle's PIN screen opens with the system prompt")
        assertTrue(pinApprovals.isEmpty(), "approved with biometrics: nothing to offer")
    }

    @Test fun offTheChallengeIsPinOnlyAndThePinApprovalIsReported() = runTest {
        assertIs<AppResult.Success<*>>(repo.pay(payment(), "key-1"))
        assertFalse(signer.sessions.single().biometrics)
        assertEquals(listOf(SignSubject.Personal), pinApprovals, "the moment for the one-time offer")
    }

    @Test fun eachWalletKeepsItsOwnSwitch() = runTest {
        on += "gw-1"
        val group = SignSubject.group("gw-1", "Kilimani Chama")
        repo.pay(payment(group, from = "gw-1"), "key-1")
        repo.pay(payment(), "key-2")
        assertEquals(listOf(true, false), signer.sessions.map { it.biometrics }, "the group's on, the personal one's off")
        assertEquals(listOf(SignSubject.Personal), pinApprovals)
    }

    @Test fun aCancelledOrFailedApprovalOffersNothing() = runTest {
        signer.outcome = SignOutcome.Cancelled
        assertIs<AppResult.Failure>(repo.pay(payment(), "key-1"))
        signer.outcome = SignOutcome.Failed("incorrectUserPin", "Wrong PIN")
        assertIs<AppResult.Failure>(repo.pay(payment(), "key-2"))
        assertTrue(pinApprovals.isEmpty())
    }

    @Test fun aNewPinNeverRunsWithBiometrics() = runTest {
        val always = WalletRepositoryImpl(remote, dao, { signer }, { "KES" }, json, biometricsOn = { true })
        always.create()
        val created = signer.sessions.single()
        assertEquals(SignPurpose.SetPin, created.subject.purpose)
        assertFalse(created.biometrics, "a new wallet's PIN: no Circle biometrics page over it")
        signer.sessions.clear()
        always.startGroupWallet("g1", "Kilimani Chama")
        assertFalse(signer.sessions.single().biometrics, "Set a PIN for Kilimani Chama: the same")
    }

    @Test fun biometricSetUpFailuresSayWhatToDo() = runTest {
        signer.outcome = SignOutcome.Failed("deviceNotSupportBiometrics", "no sensor")
        val r = assertIs<AppResult.Failure>(repo.enableBiometricApproval())
        assertEquals("wallet.biometrics_unavailable", (r.error as AppError.Api).code)
        signer.outcome = SignOutcome.Failed("biometricsUserLockoutPermanent", "locked")
        val locked = assertIs<AppResult.Failure>(repo.enableBiometricApproval())
        assertEquals("wallet.biometrics_locked", (locked.error as AppError.Api).code)
    }
}
