package com.circaso.feature.ramp

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.circasoJson
import com.circaso.feature.ramp.data.remote.KycResponse
import com.circaso.feature.ramp.data.remote.KycSessionDto
import com.circaso.feature.ramp.data.remote.RampStatusResponse
import com.circaso.feature.ramp.data.remote.SavedIdentity
import com.circaso.feature.ramp.data.remote.VerificationDto
import com.circaso.feature.ramp.data.remote.toDomain
import com.circaso.feature.ramp.data.repository.IdentityPush
import com.circaso.feature.ramp.domain.IdentitySnapshot
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.KycUpdate
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.presentation.IdentityBadgeTone
import com.circaso.feature.ramp.presentation.IdentityRowKind
import com.circaso.feature.ramp.presentation.IdentityRowUi
import com.circaso.feature.ramp.presentation.identityRow
import com.circaso.feature.wallet.domain.PinnedNetwork
import com.circaso.feature.wallet.domain.WalletNetwork
import com.circaso.feature.wallet.domain.WalletNetworks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `submitted` on the wire (backend guide, "Changed on 2026-11-23": R0's answer, the read, R1's `verification` and the
 * `kyc.updated` frame), decoded from the guide's own JSON.
 */
class SubmittedWireTest {
    private val json = circasoJson()

    @Test fun r0WithACheckOpenSaysNotSubmittedAndCarriesTheSession() {
        val start = json.decodeFromString(
            KycResponse.serializer(),
            """{ "status": "PENDING", "required": true, "canStart": true, "reason": null, "retryAt": null, "submitted": false,
                 "session": { "sessionToken": "abc123", "url": "https://verify.didit.me/session/abc123", "expiresAt": "2026-11-23T12:00:00Z" } }""",
        ).toDomain()
        assertFalse(start.verification.submitted)
        assertTrue(start.verification.startable)
        assertEquals("abc123", start.session?.sessionToken)
    }

    @Test fun r0ForASubmittedCheckHasNoSessionAndNothingToStart() {
        val start = json.decodeFromString(
            KycResponse.serializer(),
            """{ "status": "PENDING", "required": true, "canStart": false, "reason": null, "retryAt": null, "submitted": true,
                 "session": null }""",
        ).toDomain()
        assertEquals(KycStatus.Pending, start.verification.status)
        assertTrue(start.verification.submitted)
        assertTrue(start.verification.awaitingDecision)
        assertFalse(start.verification.startable)
        assertNull(start.session)
    }

    @Test fun r0WhenVerifiedSaysNotSubmitted() {
        val start = json.decodeFromString(
            KycResponse.serializer(),
            """{ "status": "VERIFIED", "required": true, "canStart": false, "reason": null, "retryAt": null, "submitted": false,
                 "session": null }""",
        ).toDomain()
        assertFalse(start.verification.submitted)
        assertFalse(start.verification.awaitingDecision)
        assertTrue(start.verification.cleared)
    }

    @Test fun aSessionSentWithASubmittedCheckIsNeverKept() {
        // The guide says `session` is null while submitted; if one came anyway there is still nothing to open.
        val start = KycResponse("PENDING", required = true, canStart = true, submitted = true, session = KycSessionDto("tok", "https://verify.didit.me/session/tok", null)).toDomain()
        assertNull(start.session)
        assertFalse(start.verification.startable, "submitted is read before canStart")
    }

    @Test fun theReadDecodesSubmittedFromTheGuidesJson() {
        val waiting = json.decodeFromString(VerificationDto.serializer(), """{ "status": "PENDING", "required": true, "canStart": false, "reason": null, "retryAt": null, "submitted": true }""").toDomain()
        assertEquals(RampVerification(KycStatus.Pending, required = true, canStart = false, submitted = true), waiting)
        assertTrue(waiting.awaitingDecision)
        val declined = json.decodeFromString(VerificationDto.serializer(), """{ "status": "DECLINED", "required": true, "canStart": true, "reason": "face_match", "retryAt": null, "submitted": false }""").toDomain()
        assertEquals(RampVerification(KycStatus.Declined, required = true, canStart = true, reason = "face_match"), declined)
        assertTrue(declined.startable)
    }

    @Test fun submittedIsFalseWhenTheServerDoesNotSendIt() {
        val old = json.decodeFromString(VerificationDto.serializer(), """{"status":"PENDING","required":true,"canStart":true}""").toDomain()
        assertFalse(old.submitted)
        assertTrue(old.startable)
        assertFalse(json.decodeFromString(KycResponse.serializer(), """{"status":"IN_REVIEW","required":true,"canStart":false,"session":null}""").toDomain().verification.submitted)
    }

    @Test fun inReviewIsSubmittedAndAwaitsTheDecision() {
        val review = json.decodeFromString(VerificationDto.serializer(), """{"status":"IN_REVIEW","required":true,"canStart":false,"reason":null,"retryAt":null,"submitted":true}""").toDomain()
        assertTrue(review.awaitingDecision)
        assertFalse(review.startable)
    }

    @Test fun r1CarriesSubmittedInsideTheVerification() {
        val status = json.decodeFromString(
            RampStatusResponse.serializer(),
            """{ "canTrade": false, "network": "TESTNET", "kyc": "NONE", "blocked": false,
                 "verification": { "status": "NONE", "required": true, "canStart": true, "reason": null, "retryAt": null,
                                   "submitted": false },
                 "accountActive": true, "blockchains": ["BASE-SEPOLIA"], "merchantOnline": true, "offRampAvailable": false,
                 "openOrderId": null, "limits": {"currency":"KES","minOrder":{"unit":"KES","amountMinor":"10000","amount":"100"},"maxOrder":{"unit":"KES","amountMinor":"15000000","amount":"150000"},"daily":{"unit":"KES","amountMinor":"30000000","amount":"300000"}}, "feePercent": "1.5" }""",
        ).toDomain()
        assertEquals(RampVerification(KycStatus.None, required = true, canStart = true, submitted = false), status.verification)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theFrameCarriesSubmittedAndItsNetwork() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        val heard = mutableListOf<KycUpdate>()
        val job = launch { f.repo.kycUpdates.collect { heard += it } }
        // The guide's frame, as its `data`.
        f.repo.onFrame("kyc.updated", json.parseToJsonElement("""{ "network": "TESTNET", "status": "VERIFIED", "required": true, "canStart": false, "reason": null, "retryAt": null, "submitted": false }""").jsonObject)
        // And the one sent when `submitted` flips.
        f.repo.onFrame("kyc.updated", json.parseToJsonElement("""{"network":"MAINNET","status":"PENDING","required":true,"canStart":false,"reason":null,"retryAt":null,"submitted":true}""").jsonObject)
        assertEquals(
            listOf(
                KycUpdate(WalletNetwork.TESTNET, RampVerification(KycStatus.Verified, required = true, canStart = false)),
                KycUpdate(WalletNetwork.MAINNET, RampVerification(KycStatus.Pending, required = true, canStart = false, submitted = true)),
            ),
            heard,
        )
        job.cancel()
    }
}

/**
 * The phone's copy of the identity check (offline-first: the profile's status is a **Read**): kept per wallet network,
 * replaced by every answer the server gives about the check, never by a failure, and never holding a session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentityCopyTest {
    private val json = circasoJson()
    private suspend fun RampFixture.copy(network: WalletNetwork): IdentitySnapshot? = withContext(PinnedNetwork(network)) { repo.identity.first() }
    private fun both() = WalletNetworks.fixed(WalletNetwork.TESTNET, switchable = true, known = true)

    @Test fun nothingIsKnownUntilTheServerHasAnswered() = runTest {
        val f = RampFixture()
        assertNull(f.repo.identity.first())
    }

    @Test fun theReadIsKeptWithWhenItWasSaid() = runTest {
        val f = RampFixture(clock = { 1_000L })
        f.remote.kyc = AppResult.Success(checkDto("DECLINED", canStart = true, reason = KycReasons.FaceMatch))
        f.repo.kyc()
        assertEquals(IdentitySnapshot(RampVerification(KycStatus.Declined, required = true, canStart = true, reason = "face_match"), unavailable = false, checkedAt = 1_000L), f.repo.identity.first())
    }

    @Test fun submittedAndTheRetryTimeSurviveBeingKept() = runTest {
        val f = RampFixture()
        f.remote.kyc = AppResult.Success(checkDto("PENDING", canStart = false, submitted = true))
        f.repo.kyc()
        assertTrue(assertNotNull(f.repo.identity.first()?.verification).awaitingDecision)
        f.remote.kyc = AppResult.Success(checkDto("DECLINED", canStart = false, reason = "liveness", retryAt = "2026-11-23T09:05:00Z"))
        f.repo.kyc()
        assertEquals(1795424700000L, f.repo.identity.first()?.verification?.retryAt)
    }

    @Test fun eachNetworkHasItsOwnCopy() = runTest {
        val f = RampFixture(networks = both())
        f.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false))
        withContext(PinnedNetwork(WalletNetwork.TESTNET)) { f.repo.kyc() }
        assertEquals(KycStatus.Verified, f.copy(WalletNetwork.TESTNET)?.verification?.status)
        assertNull(f.copy(WalletNetwork.MAINNET), "a Testnet practice check says nothing about Mainnet")
        f.remote.kyc = AppResult.Success(checkDto("NONE"))
        withContext(PinnedNetwork(WalletNetwork.MAINNET)) { f.repo.kyc() }
        assertEquals(KycStatus.None, f.copy(WalletNetwork.MAINNET)?.verification?.status)
        assertEquals(KycStatus.Verified, f.copy(WalletNetwork.TESTNET)?.verification?.status)
    }

    @Test fun noAnswerLeavesTheCopyAsItWas() = runTest {
        val f = RampFixture()
        f.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false))
        f.repo.kyc()
        f.remote.kyc = AppResult.Failure(AppError.Network)
        f.repo.kyc()
        assertEquals(KycStatus.Verified, f.repo.identity.first()?.verification?.status)
        f.remote.kyc = refusal("service.unavailable", http = 503)
        f.repo.kyc()
        assertEquals(KycStatus.Verified, f.repo.identity.first()?.verification?.status)
    }

    @Test fun notOpenHereIsRememberedAsThatAndClearsWhenItOpens() = runTest {
        val f = RampFixture(clock = { 5L })
        f.remote.kyc = refusal("ramp.unavailable", http = 403)
        f.repo.kyc()
        assertEquals(IdentitySnapshot(null, unavailable = true, checkedAt = 5L), f.repo.identity.first())
        // The server opens the routes: the next answer is a state like any other.
        f.remote.kyc = AppResult.Success(checkDto("NONE"))
        f.repo.kyc()
        assertEquals(false, f.repo.identity.first()?.unavailable)
        assertEquals(KycStatus.None, f.repo.identity.first()?.verification?.status)
    }

    @Test fun r0sAnswerIsKeptButNeverItsSession() = runTest {
        val f = RampFixture()
        f.repo.startKyc("k1")
        assertEquals(KycStatus.Pending, f.repo.identity.first()?.verification?.status)
        val kept = assertNotNull(f.identitySlot(WalletNetwork.TESTNET).value)
        assertFalse(kept.contains(SessionToken), kept)
        assertFalse(kept.contains("didit"), kept)
        // And a refusal that means "not open here" is remembered from R0 too.
        f.remote.kycStart = refusal("ramp.unavailable", http = 403)
        f.repo.startKyc("k2")
        assertTrue(f.repo.identity.first()?.unavailable == true)
    }

    @Test fun r1sVerificationIsKeptAndR1BeingClosedSaysNothingAboutTheCheck() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture()
        f.remote.status = AppResult.Success(unverified("IN_REVIEW", canStart = false))
        f.repo.status()
        assertEquals(KycStatus.InReview, f.repo.identity.first()?.verification?.status)
        // The ramp is off for this person: the verification routes may still be open, so the copy stays.
        f.remote.status = refusal("ramp.unavailable", http = 403)
        f.repo.status()
        assertEquals(KycStatus.InReview, f.repo.identity.first()?.verification?.status)
        assertEquals(false, f.repo.identity.first()?.unavailable)
    }

    @Test fun aFrameIsFiledUnderItsOwnNetwork() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture(networks = both())
        f.repo.onFrame("kyc.updated", json.parseToJsonElement("""{"network":"MAINNET","status":"VERIFIED","required":true,"canStart":false,"submitted":false}""").jsonObject)
        assertEquals(KycStatus.Verified, f.copy(WalletNetwork.MAINNET)?.verification?.status)
        assertNull(f.copy(WalletNetwork.TESTNET))
        // A frame with no network can't be filed anywhere.
        f.repo.onFrame("kyc.updated", json.parseToJsonElement("""{"status":"DECLINED","required":true,"canStart":true}""").jsonObject)
        assertEquals(KycStatus.Verified, f.copy(WalletNetwork.MAINNET)?.verification?.status)
        assertNull(f.copy(WalletNetwork.TESTNET))
    }

    @Test fun aStatusThisVersionDoesNotKnowIsKeptAsUnknown() = runTest {
        val f = RampFixture()
        f.remote.kyc = AppResult.Success(checkDto("ESCALATED", canStart = false))
        f.repo.kyc()
        assertEquals(KycStatus.Unknown, f.repo.identity.first()?.verification?.status)
    }

    @Test fun aCopyThatCannotBeReadIsNothingKnown() = runTest {
        val f = RampFixture()
        f.identitySlot(WalletNetwork.TESTNET).value = "not json"
        assertNull(f.repo.identity.first())
        // Only the state and its reason code are ever kept.
        assertEquals(setOf("status", "required", "canStart", "reason", "retryAt", "submitted", "unavailable", "checkedAt"), SavedIdentity.serializer().descriptor.let { d -> (0 until d.elementsCount).map(d::getElementName).toSet() })
    }

    @Test fun theSettingIsOnePerNetworkAndGoesAtSignOut() {
        assertEquals("wallet.ramp.kyc.MAINNET", com.circaso.feature.ramp.data.repository.RampRepositoryImpl.identityKey(WalletNetwork.MAINNET))
        assertEquals("wallet.ramp.kyc.TESTNET", com.circaso.feature.ramp.data.repository.RampRepositoryImpl.identityKey(WalletNetwork.TESTNET))
        // `text.wallet.*` is what sign-out wipes (AppPreferences), so the copy is one account's.
        assertTrue(com.circaso.feature.ramp.data.repository.RampRepositoryImpl.identityKey(WalletNetwork.MAINNET).startsWith("wallet."))
    }

    @Test fun theVerificationPushReadsTheCheckForItsNetwork() = runTest(UnconfinedTestDispatcher()) {
        val f = RampFixture(networks = both())
        f.remote.kyc = AppResult.Success(checkDto("VERIFIED", canStart = false))
        assertTrue(IdentityPush.received(mapOf("kind" to "ramp.kyc", "network" to "MAINNET"), this) { f.repo })
        assertEquals(1, f.remote.kycReads)
        assertEquals(listOf<WalletNetwork?>(WalletNetwork.MAINNET), f.remote.networks)
        assertEquals(KycStatus.Verified, f.copy(WalletNetwork.MAINNET)?.verification?.status)
        // Without a network it is the one in use; any other push is left alone.
        assertTrue(IdentityPush.received(mapOf("kind" to "ramp.kyc"), this) { f.repo })
        assertEquals(KycStatus.Verified, f.copy(WalletNetwork.TESTNET)?.verification?.status)
        assertFalse(IdentityPush.received(mapOf("kind" to "ramp.order", "orderId" to "o1"), this) { f.repo })
        assertFalse(IdentityPush.received(mapOf("kind" to null), this) { f.repo })
        assertEquals(2, f.remote.kycReads)
    }
}

/** The profile's Verification row: what it says for every status the server can give, and when the server can't be asked. */
class IdentityRowTest {
    private fun row(v: RampVerification?, online: Boolean = true, asking: Boolean = false, unanswered: Boolean = false, testnet: Boolean = false, unavailable: Boolean = false) =
        identityRow(if (v == null && !unavailable) null else IdentitySnapshot(v, unavailable, checkedAt = 0), asking = asking, unanswered = unanswered, online = online, testnet = testnet, now = 0)
    private fun look(v: RampVerification, online: Boolean = true, unanswered: Boolean = false, testnet: Boolean = false) = assertIs<IdentityRowUi.Shown>(row(v, online, unanswered = unanswered, testnet = testnet)).look
    private fun v(status: KycStatus, canStart: Boolean = false, reason: String? = null, required: Boolean = true, submitted: Boolean = false) = RampVerification(status, required, canStart, reason, null, submitted)

    @Test fun verifiedSaysSo() {
        val l = look(v(KycStatus.Verified))
        assertEquals(IdentityRowKind.Verified, l.kind)
        assertEquals("Your identity is confirmed", l.sub)
        assertEquals("Verified" to IdentityBadgeTone.Neutral, l.badge?.text to l.badge?.tone)
        assertNull(l.action)
        assertFalse(l.lastKnown)
    }

    @Test fun notVerifiedOffersVerify() {
        val l = look(v(KycStatus.None, canStart = true))
        assertEquals(IdentityRowKind.NotVerified, l.kind)
        assertEquals("Needed to move money in or out of Circaso", l.sub)
        assertEquals("Verify", l.action)
        assertNull(l.badge)
        // Where the server doesn't ask for it, it isn't said to be needed.
        assertEquals("Not verified", look(v(KycStatus.None, canStart = true, required = false)).sub)
    }

    @Test fun anOpenCheckOffersContinue() {
        val l = look(v(KycStatus.Pending, canStart = true))
        assertEquals(IdentityRowKind.InProgress to "Continue", l.kind to l.action)
        assertEquals("Started. Pick up where you left off", l.sub)
        val more = look(v(KycStatus.Pending, canStart = true, reason = KycReasons.ResubmissionRequested))
        assertEquals(IdentityRowKind.OneMoreThing to "Continue", more.kind to more.action)
        assertEquals("One more step is needed", more.sub)
    }

    @Test fun submittedIsReadBeforeCanStart() {
        val l = look(v(KycStatus.Pending, canStart = true, submitted = true))
        assertEquals(IdentityRowKind.Checking, l.kind)
        assertEquals("We're checking your details", l.sub)
        assertEquals("Checking" to IdentityBadgeTone.Warning, l.badge?.text to l.badge?.tone)
        assertNull(l.action, "nothing is offered while the decision is awaited")
    }

    @Test fun inReviewWearsTheDesignsBadge() {
        val l = look(v(KycStatus.InReview, submitted = true))
        assertEquals(IdentityRowKind.InReview, l.kind)
        assertEquals("In review" to IdentityBadgeTone.Warning, l.badge?.text to l.badge?.tone)
        assertEquals("A reviewer is going over your details", l.sub)
        assertNull(l.action)
    }

    @Test fun aDeclineAnotherTryCanFixNeedsAnotherTry() {
        val l = look(v(KycStatus.Declined, canStart = true, reason = KycReasons.FaceMatch))
        assertEquals(IdentityRowKind.NeedsAnotherTry to "Try Again", l.kind to l.action)
        // The screen's own word for its button: an expired check is verified again.
        assertEquals("Verify Again", look(v(KycStatus.Expired, canStart = true, reason = KycReasons.SessionExpired)).action)
        assertEquals("The last check didn't pass", l.sub)
        assertEquals("The last check ran out of time", look(v(KycStatus.Expired, canStart = true, reason = KycReasons.SessionExpired)).sub)
        assertEquals("Your ID has expired", look(v(KycStatus.Expired, canStart = true, reason = KycReasons.DocumentExpired)).sub)
        assertEquals("Your ID has expired", look(v(KycStatus.Declined, canStart = true, reason = KycReasons.DocumentExpired)).sub)
    }

    @Test fun aDeclineForGoodCouldNotVerify() {
        for (reason in listOf(KycReasons.NotApproved, KycReasons.IdentityInUse, KycReasons.Blocked, "a_new_reason")) {
            val l = look(v(KycStatus.Declined, reason = reason))
            assertEquals(IdentityRowKind.CouldNotVerify, l.kind, reason)
            assertEquals("Couldn't verify" to IdentityBadgeTone.Critical, l.badge?.text to l.badge?.tone)
            assertEquals("Contact Support for help", l.sub)
            assertNull(l.action)
        }
    }

    @Test fun triesUsedUpWaitWithNothingToTap() {
        for (waiting in listOf(v(KycStatus.Declined, reason = KycReasons.Liveness).copy(retryAt = 10_000), v(KycStatus.Expired).copy(retryAt = 10_000), v(KycStatus.Pending), v(KycStatus.None).copy(retryAt = 10_000))) {
            val l = look(waiting)
            assertEquals(IdentityRowKind.Waiting, l.kind, waiting.toString())
            assertTrue(l.sub.startsWith("Today's tries are used up. You can try again"), l.sub)
            assertNull(l.action)
            assertNull(l.badge)
        }
    }

    @Test fun whereItIsNotAskedForAndCannotBeStartedItIsNotNeeded() {
        val l = look(v(KycStatus.None, required = false))
        assertEquals(IdentityRowKind.NotNeeded, l.kind)
        assertEquals("Not needed right now", l.sub)
        assertNull(l.action)
    }

    @Test fun aNewerServersStatusIsNeverAnEnding() {
        val l = look(v(KycStatus.Unknown))
        assertEquals(IdentityRowKind.InHand, l.kind)
        assertEquals("In progress", l.badge?.text)
        assertEquals("Continue", look(v(KycStatus.Unknown, canStart = true)).action)
    }

    @Test fun onTestnetEveryStatusSaysSo() {
        assertEquals("Testnet · Your identity is confirmed", look(v(KycStatus.Verified), testnet = true).sub)
        assertEquals("Testnet · Needed to move money in or out of Circaso", look(v(KycStatus.None, canStart = true), testnet = true).sub)
        assertEquals("Testnet · Needed to move money in or out of Circaso", look(v(KycStatus.None, canStart = true), online = false, testnet = true).sub)
        assertFalse(look(v(KycStatus.Verified)).sub.contains("Testnet"))
    }

    @Test fun offlineItIsTheLastKnownStatusAndSaysVerifyingNeedsAConnection() {
        val verified = look(v(KycStatus.Verified), online = false)
        assertEquals(IdentityRowKind.Verified, verified.kind)
        assertEquals("Verified", verified.badge?.text)
        assertTrue(verified.lastKnown)
        // The status still says what it was; the line under it says it is the last known one.
        assertEquals("Your identity is confirmed", verified.sub)
        assertEquals("Last known status. Updates when you're back online.", verified.note)
        val open = look(v(KycStatus.None, canStart = true), online = false)
        assertEquals("Verify", open.action)
        assertTrue(open.lastKnown, "the action waits")
        assertEquals("Needed to move money in or out of Circaso", open.sub)
        assertEquals("Last known status. Verifying needs a connection.", open.note)
        val waiting = look(v(KycStatus.Declined, reason = KycReasons.Liveness).copy(retryAt = 10_000), online = false)
        assertTrue(waiting.sub.startsWith("Today's tries are used up."), "a wait keeps saying what it waits for: ${waiting.sub}")
        val retry = look(v(KycStatus.Declined, canStart = true, reason = KycReasons.DocumentExpired), online = false)
        assertEquals("Your ID has expired", retry.sub, "and why another try is needed")
        assertEquals("Last known status. Updates when you're back online.", look(v(KycStatus.InReview, submitted = true), online = false).note)
        assertNull(look(v(KycStatus.Verified)).note)
    }

    @Test fun aReadThatGotNoAnswerMarksTheStatusAsLastKnown() {
        val l = look(v(KycStatus.Verified), unanswered = true)
        assertTrue(l.lastKnown)
        assertEquals("Your identity is confirmed", l.sub)
        assertEquals("Last known status. Couldn't check just now.", l.note)
    }

    @Test fun withNothingKnownItIsASkeletonWhileAskingAndPlainWordsAfter() {
        assertEquals(IdentityRowUi.Loading, row(null, asking = true))
        val failed = assertIs<IdentityRowUi.Shown>(row(null, unanswered = true)).look
        assertEquals(IdentityRowKind.Unknown to "Couldn't check just now", failed.kind to failed.sub)
        val offline = assertIs<IdentityRowUi.Shown>(row(null, online = false, asking = true)).look
        assertEquals("Shows when you're back online", offline.sub)
        assertNull(offline.action)
        assertNull(offline.badge)
    }

    @Test fun whereVerificationIsNotOpenTheRowIsNotThere() {
        assertEquals(IdentityRowUi.Hidden, row(null, unavailable = true))
        assertEquals(IdentityRowUi.Hidden, row(null, unavailable = true, online = false))
    }
}
