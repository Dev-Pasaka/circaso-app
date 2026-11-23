package com.circaso.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.Section
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.feature.ramp.domain.IdentitySnapshot
import com.circaso.feature.ramp.domain.KycReasons
import com.circaso.feature.ramp.domain.KycStatus
import com.circaso.feature.ramp.domain.RampVerification
import com.circaso.feature.ramp.presentation.AddMoneyFailure
import com.circaso.feature.ramp.presentation.IdentityContent
import com.circaso.feature.ramp.presentation.IdentityRow
import com.circaso.feature.ramp.presentation.IdentityRowUi
import com.circaso.feature.ramp.presentation.IdentityUiState
import com.circaso.feature.ramp.presentation.RampBlock
import com.circaso.feature.ramp.presentation.VerifyTrouble
import com.circaso.feature.ramp.presentation.VerifyUi
import com.circaso.feature.ramp.presentation.identityRow
import com.circaso.feature.wallet.domain.WalletNetwork

/** The states the Verification row and screen are previewed in, by the name's ending. */
private fun check(name: String, tomorrow: Long): RampVerification? = when (name) {
    "verified" -> RampVerification(KycStatus.Verified, required = true, canStart = false)
    "none" -> RampVerification(KycStatus.None, required = true, canStart = true)
    "optional" -> RampVerification(KycStatus.None, required = false, canStart = true)
    "not-needed" -> RampVerification(KycStatus.None, required = false, canStart = false)
    "continue" -> RampVerification(KycStatus.Pending, required = true, canStart = true)
    "more" -> RampVerification(KycStatus.Pending, required = true, canStart = true, reason = KycReasons.ResubmissionRequested)
    // Finished by the person, the decision awaited: `submitted`, with nothing to start.
    "checking" -> RampVerification(KycStatus.Pending, required = true, canStart = false, submitted = true)
    "review" -> RampVerification(KycStatus.InReview, required = true, canStart = false, submitted = true)
    "declined" -> RampVerification(KycStatus.Declined, required = true, canStart = true, reason = KycReasons.FaceMatch)
    "expired" -> RampVerification(KycStatus.Expired, required = true, canStart = true, reason = KycReasons.SessionExpired)
    "id-expired" -> RampVerification(KycStatus.Expired, required = true, canStart = true, reason = KycReasons.DocumentExpired)
    "wait" -> RampVerification(KycStatus.Declined, required = true, canStart = false, reason = KycReasons.Liveness, retryAt = tomorrow)
    "final" -> RampVerification(KycStatus.Declined, required = true, canStart = false, reason = KycReasons.NotApproved)
    else -> null
}

/**
 * You › Verification (`-preview=`), from sample data.
 *
 * The screen, `identity-…`: `-loading`, `-load-failed`, `-load-offline`, `-unavailable` (`ramp.unavailable`),
 * `-barred`, `-restricted`, `-not-needed`, `-none` (E3) and `-none-offline`, `-none-mainnet`, `-optional`, `-starting`,
 * `-continue`, `-continue-offline`, `-more`, `-checking` (`submitted`), `-checking-slow`, `-checking-offline`,
 * `-review`, `-declined`, `-expired`, `-id-expired`, `-wait`, `-final`, `-tries`, `-down`, `-setup`, `-camera`,
 * `-verified`, `-verified-mainnet`, `-verified-offline`, `-verified-unconfirmed`, `-verified-just`.
 *
 * The profile's row in every status on one page, `identity-rows` (and `-offline`, `-unconfirmed`, `-mainnet`): the
 * first paint, each status the server can give, and nothing known.
 */
@Composable
internal fun IdentityPreview(name: String): Boolean {
    if (!name.startsWith("identity")) return false
    val now = nowMillis()
    // Tomorrow morning, and "checked a while ago", whenever the preview is opened.
    val tomorrow = now + 14 * 60 * 60 * 1000L
    val earlier = now - 3 * 60 * 60 * 1000L
    if (name.startsWith("identity-rows")) { IdentityRows(name, now, tomorrow, earlier); return true }
    fun on(key: String, testnet: Boolean = true, ui: VerifyUi.() -> VerifyUi = { this }) = IdentityUiState(
        known = true, network = if (testnet) WalletNetwork.TESTNET else WalletNetwork.MAINNET, testnet = testnet, checkedAt = earlier,
        verify = VerifyUi(check(key, tomorrow)).let { if (key == "verified") it.copy(verified = true) else it }.ui(),
    )
    val state = when (name.removePrefix("identity").removePrefix("-")) {
        "", "verified" -> on("verified")
        "verified-mainnet" -> on("verified", testnet = false)
        "verified-offline" -> on("verified", testnet = false).copy(online = false)
        "verified-unconfirmed" -> on("verified", testnet = false).copy(unconfirmed = true)
        "verified-just" -> on("verified", testnet = false).copy(justVerified = true)
        "loading" -> IdentityUiState(network = WalletNetwork.MAINNET)
        "load-failed" -> IdentityUiState(network = WalletNetwork.MAINNET, failure = AddMoneyFailure("We couldn't check your verification just now. Nothing is lost. Please try again in a moment.", "req_8f2a1c"))
        "load-offline" -> IdentityUiState(network = WalletNetwork.MAINNET, online = false, failure = AddMoneyFailure("We couldn't reach Circaso to check your verification.", null))
        "unavailable" -> IdentityUiState(known = true, network = WalletNetwork.MAINNET, gate = RampBlock.Unavailable)
        "barred" -> IdentityUiState(known = true, network = WalletNetwork.MAINNET, gate = RampBlock.Barred)
        "restricted" -> IdentityUiState(known = true, network = WalletNetwork.MAINNET, gate = RampBlock.Restricted)
        "not-needed" -> on("not-needed")
        "none" -> on("none")
        "none-mainnet" -> on("none", testnet = false)
        "none-offline" -> on("none").copy(online = false)
        "optional" -> on("optional")
        "starting" -> on("none") { copy(starting = true) }
        "continue" -> on("continue")
        "continue-offline" -> on("continue").copy(online = false)
        "more" -> on("more")
        "checking" -> on("checking") { copy(checking = true) }
        "checking-slow" -> on("checking") { copy(checking = true, slow = true) }
        "checking-offline" -> on("checking") { copy(checking = true) }.copy(online = false)
        "review" -> on("review")
        "declined" -> on("declined")
        "expired" -> on("expired")
        "id-expired" -> on("id-expired")
        "wait" -> on("wait")
        "final" -> on("final")
        "tries" -> on("declined") { copy(trouble = VerifyTrouble.TriesUsedUp, retryAt = tomorrow) }
        "down" -> on("none") { copy(trouble = VerifyTrouble.ProviderDown, reference = "req_8f2a1c") }
        "setup" -> on("none") { copy(trouble = VerifyTrouble.NotConfigured, reference = "req_8f2a1c") }
        "camera" -> on("continue") { copy(trouble = VerifyTrouble.CameraDenied) }
        else -> return false
    }
    IdentityContent(state, onBack = {}, now = now)
    return true
}

/** The profile's Money group with the Verification row in each status, between the rows it lives with. */
@Composable
private fun IdentityRows(name: String, now: Long, tomorrow: Long, earlier: Long) {
    val online = !name.endsWith("-offline")
    val unanswered = name.endsWith("-unconfirmed")
    val testnet = !name.endsWith("-mainnet")
    fun row(key: String) = identityRow(check(key, tomorrow)?.let { IdentitySnapshot(it, unavailable = false, checkedAt = earlier) }, asking = false, unanswered = unanswered, online = online, testnet = testnet, now = now)
    val rows = listOf("First paint" to IdentityRowUi.Loading) +
        listOf("verified", "none", "optional", "continue", "more", "checking", "review", "declined", "expired", "id-expired", "wait", "final", "not-needed").map { it to row(it) } +
        listOf("Nothing known" to identityRow(null, asking = false, unanswered = true, online = online, testnet = testnet, now = now))
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Verification row", onBack = {}) }) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = CircasoSpacing.s4).padding(top = CircasoSpacing.s3, bottom = padding.calculateBottomPadding() + CircasoSpacing.s6),
            verticalArrangement = Arrangement.spacedBy(CircasoSpacing.s5),
        ) {
            rows.forEach { (label, ui) ->
                Section(label) {
                    IdentityRow(ui, onClick = {})
                    RowDivider(Modifier.padding(start = CircasoSpacing.s4))
                    ListRow("Currency", sub = "How amounts are shown in your wallet and chats", trailing = { RowChevron() }, onClick = {})
                }
            }
        }
    }
}
