package com.circaso.feature.chama.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.circaso.core.common.AppResult
import com.circaso.core.common.ErrorMessages
import com.circaso.core.datastore.AppPreferences
import com.circaso.core.flags.GroupWalletsFlag
import com.circaso.core.platform.LocalTimeStyle
import com.circaso.core.platform.formatLocalTime
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.CircasoSwitch
import com.circaso.designsystem.components.ListRow
import com.circaso.designsystem.components.RowChevron
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.components.RowValue
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.components.SegmentedControl
import com.circaso.designsystem.components.Spinner
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.domain.ApprovalPolicy
import com.circaso.feature.chama.domain.GroupMoney
import com.circaso.feature.chama.domain.ObserveGroupMoneyUseCase
import com.circaso.feature.chama.domain.RefreshGroupMoneyUseCase
import com.circaso.feature.chama.domain.SetApprovalPolicyUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.wallet.domain.EnableWalletBiometricsUseCase
import com.circaso.feature.wallet.domain.ObserveWalletOfUseCase
import com.circaso.feature.wallet.domain.RefreshWalletOfUseCase
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.presentation.WalletAvatar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/** What group info shows of a group's money (GW6–GW8), read from the phone's copy. */
data class GroupInfoMoney(
    /** Group wallets are on: the rows below replace the treasurer-model "Group wallet" row. */
    val enabled: Boolean = false,
    /** CH7 has been read at least once (here or earlier); until then the rows say they're loading. */
    val money: GroupMoney? = null,
    /** The group wallet's own W1 copy (balance and fiat). */
    val balance: Wallet? = null,
    /** This wallet approves with biometrics on this phone. */
    val biometricsOn: Boolean = false,
    /** Circle reported biometrics lost for this wallet (GW8b). */
    val biometricsLost: Boolean = false,
    /** "Fingerprint" / "Face ID"; null when the phone has none. */
    val biometricLabel: String? = null,
    /** The last refresh failed and there is no copy: say so, with its reference (never an empty screen). */
    val loadError: String? = null,
    val loadReference: String? = null,
)

/**
 * Group info's window onto the group's money: CH7 and the group wallet's balance, refreshed when it opens, and the
 * per-wallet settings (biometrics, who approves payouts). Reads come from Room; every change is online-only.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupInfoMoneySource(
    private val flag: GroupWalletsFlag,
    private val observeMoney: ObserveGroupMoneyUseCase,
    private val refreshMoney: RefreshGroupMoneyUseCase,
    private val observeWallet: ObserveWalletOfUseCase,
    private val refreshWallet: RefreshWalletOfUseCase,
    private val setPolicy: SetApprovalPolicyUseCase,
    private val enableBiometrics: EnableWalletBiometricsUseCase,
    private val prefs: AppPreferences,
    private val biometricLabel: String?,
) {
    private val error = MutableStateFlow<Pair<String, String?>?>(null)

    fun observe(chatId: String): Flow<GroupInfoMoney> = combine(flag.enabled, observeMoney(chatId), error) { on, money, err -> Triple(on, money, err) }
        .flatMapLatest { (on, money, err) ->
            val walletId = money?.wallet?.walletId
            if (!on) flowOf(GroupInfoMoney(enabled = false))
            else if (walletId == null) flowOf(GroupInfoMoney(true, money, biometricLabel = biometricLabel, loadError = err?.first?.takeIf { money == null }, loadReference = err?.second))
            else combine(observeWallet(walletId), prefs.walletBiometrics(walletId), prefs.flag(lostFlag(walletId))) { w, bio, lost ->
                GroupInfoMoney(true, money, w, bio, lost && !bio, biometricLabel)
            }
        }

    suspend fun refresh(chatId: String) {
        if (!flag.isOn) return
        when (val r = refreshMoney(chatId)) {
            is AppResult.Success -> { error.value = null; r.value.wallet?.walletId?.let { refreshWallet(it) } }
            is AppResult.Failure -> error.value = ErrorMessages.forError(r.error) to ErrorMessages.reference(r.error)
        }
    }

    /** GW8: turn on fingerprint / Face ID for this wallet only (its own Circle user, its PIN once). */
    suspend fun turnOnBiometrics(walletId: String, name: String): AppResult<Unit> {
        val r = enableBiometrics(SignSubject.group(walletId, name))
        if (r is AppResult.Success) { prefs.setWalletBiometrics(walletId, true); prefs.setFlag(lostFlag(walletId), false) }
        return r
    }

    suspend fun setApprovalPolicy(chatId: String, policy: ApprovalPolicy, key: String) = setPolicy(chatId, policy, key)

    /** Back to the wallet's PIN on this phone (the personal Security switch does the same). */
    suspend fun turnOffBiometrics(walletId: String) = prefs.setWalletBiometrics(walletId, false)

    companion object {
        /** Set when Circle reports a wallet's biometrics gone, so its row says why it's off (GW8b). */
        fun lostFlag(walletId: String?) = "biometric_lost.${walletId ?: "personal"}"
    }
}

/** Who holds what in group info's money rows, and what the viewer may do. */
private fun signerName(chat: Chat?, money: GroupMoney): String =
    chat?.members?.firstOrNull { it.userId == money.wallet?.signerUserId }?.displayName?.substringBefore(' ') ?: "The signer"

/** The signer left the group (§11.8): the wallet is locked until they're back. */
fun signerLeft(chat: Chat?, money: GroupMoney?): Boolean {
    val signer = money?.wallet?.signerUserId ?: return false
    val members = chat?.members ?: return false
    return money.walletActive && members.isNotEmpty() && members.none { it.userId == signer }
}

private fun policyWords(policy: ApprovalPolicy) = if (policy == ApprovalPolicy.AnyAdmin) "an admin approves" else "2 admins approve"

/** "KES 50,000 by 14 Dec · 65%". */
private fun targetLine(money: GroupMoney, fiat: com.circaso.feature.wallet.domain.FiatValue?): String {
    val g = money.goal ?: return ""
    val by = g.deadline?.let { runCatching { formatLocalTime(Instant.parse(it).toEpochMilliseconds(), LocalTimeStyle.ShortDate) }.getOrNull() }
    return amountText(g.targetMinor, fiat) + (by?.let { " by $it" }.orEmpty()) + " · ${(money.progress * 100).toInt()}%"
}

/** The rows' icon, as the design's `cx-icon-btn`: a glyph on a quiet disc. */
@Composable
fun RowIcon(icon: CircasoIcon, tint: androidx.compose.ui.graphics.Color = CircasoTheme.colors.ink, dim: Boolean = false) {
    val c = CircasoTheme.colors
    Box(Modifier.size(36.dp).clip(CircasoShapes.circle).background(c.fill).alpha(if (dim) 0.45f else 1f), contentAlignment = Alignment.Center) {
        CircasoIconImage(icon, tint, Modifier.size(20.dp))
    }
}

/** What group info's money rows ask of the screen. */
class GroupMoneyActions(
    val onSetUpWallet: (thenTarget: Boolean) -> Unit,
    val onOpenWallet: () -> Unit,
    val onTarget: () -> Unit,
    val onTargetNeedsWallet: () -> Unit,
    /** GW8: this wallet's biometrics on (Circle's set-up with its PIN) or off (back to its PIN). */
    val onBiometrics: (Boolean) -> Unit,
    val onChangePin: () -> Unit,
    val onApprovals: () -> Unit,
    val onRetry: () -> Unit,
)

/**
 * GW6–GW8b in group info. No wallet: the owner sees Add a Group Wallet and Add a Target (members see neither: a group
 * without a wallet shows no money anywhere). Setting up: Finish Wallet Setup (owner) or "being set up". With a wallet:
 * its balance and who can send, the target, and the wallet's own security for its signer. Offline, every action is
 * disabled before the tap with "Needs a connection" (GW6c); the copy reads from the phone.
 */
@Composable
fun ColumnScope.GroupMoneyRows(info: GroupInfoMoney, chat: Chat?, myUserId: String?, online: Boolean, busy: Boolean, actions: GroupMoneyActions) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val money = info.money
    val name = chat?.title ?: "This group"
    val amOwner = chat?.myRole == com.circaso.feature.messaging.domain.MemberRole.Owner
    val off = !online
    fun sub(text: String) = if (off) "Needs a connection" else text
    when {
        money == null && info.loadError != null -> {
            ListRow("Couldn’t load the group’s money", sub = info.loadError + (info.loadReference?.let { "\nRef $it" } ?: ""), leading = { RowIcon(CircasoIcon.Warning, c.warning) },
                trailing = { if (online) CircasoButton("Try Again", actions.onRetry, style = ButtonStyle.Secondary, size = ButtonSize.Small) })
        }
        money == null -> ListRow("Group money", sub = "Loading…", leading = { RowIcon(CircasoIcon.Wallet) }, trailing = { Spinner(c.inkSecondary, Modifier.size(16.dp)) })
        money.wallet == null -> {
            // GW6: only the owner can set up the wallet; a target needs one, so it walks through the set-up first (GW7).
            if (amOwner) {
                ListRow("Add a Group Wallet", sub = sub("Members pay into it; it has its own PIN"), leading = { RowIcon(CircasoIcon.Wallet, dim = off) }, trailing = { RowChevron() },
                    onClick = if (online && !busy) ({ actions.onSetUpWallet(false) }) else null)
                RowDivider(Modifier.padding(start = 64.dp))
                ListRow("Add a Target", sub = sub("Save towards a goal together"), leading = { RowIcon(CircasoIcon.Target, dim = off) }, trailing = { RowChevron() },
                    onClick = if (online && !busy) actions.onTargetNeedsWallet else null)
            }
        }
        money.settingUp -> {
            if (amOwner) ListRow("Finish Wallet Setup", sub = sub("$name’s wallet isn’t ready yet"), leading = { RowIcon(CircasoIcon.Clock, c.warning, dim = off) }, trailing = { RowChevron() },
                onClick = if (online && !busy) ({ actions.onSetUpWallet(false) }) else null)
            else ListRow("Wallet being set up", sub = "Money actions appear once it’s ready", leading = { RowIcon(CircasoIcon.Clock, c.warning) })
        }
        else -> {
            val locked = signerLeft(chat, money)
            val balance = info.balance?.usdc?.let { amountText(it.amountMinor, info.balance.fiat) }
            val who = if (money.isSigner) "only you can send" else "only ${signerName(chat, money)} can send"
            val walletSub = when {
                locked -> "Locked. ${signerName(chat, money)} signs for it and has left the group; nothing can be sent until they’re back."
                else -> listOfNotNull(balance, "$who, after ${policyWords(money.approvalPolicy)}").joinToString(" · ")
            }
            ListRow("Group Wallet", sub = walletSub, leading = { RowIcon(if (locked) CircasoIcon.Lock else CircasoIcon.Wallet, if (locked) c.warning else c.ink) }, trailing = { RowChevron() }, onClick = actions.onOpenWallet)
            if (money.goal != null || money.isAdmin) RowDivider(Modifier.padding(start = 64.dp))
            if (money.goal != null) {
                ListRow("Target", sub = targetLine(money, info.balance?.fiat), leading = { RowIcon(CircasoIcon.Target) }, trailing = { RowChevron() },
                    onClick = if (money.isAdmin) (if (online) actions.onTarget else null) else actions.onOpenWallet)
            } else if (money.isAdmin) {
                ListRow("Add a Target", sub = sub("Save towards a goal together"), leading = { RowIcon(CircasoIcon.Target, dim = off) }, trailing = { RowChevron() },
                    onClick = if (online && !busy) actions.onTarget else null)
            }
            if (money.goal != null || money.isAdmin) RowDivider(Modifier.padding(start = 64.dp))
            ListRow(
                "Who approves payouts", leading = { RowIcon(CircasoIcon.People) },
                trailing = { RowValue(if (money.approvalPolicy == ApprovalPolicy.AnyAdmin) "Any admin" else "2 admins") },
                onClick = if (money.isOwner && online && !busy) actions.onApprovals else null,
            )
            if (money.isSigner) {
                SectionHeader("$name wallet security", Modifier.padding(top = CircasoSpacing.s4))
                info.biometricLabel?.let { label ->
                    ListRow(
                        label,
                        sub = when {
                            info.biometricsLost -> "Stopped working for this wallet. Its PIN approves payments until you turn it on again."
                            off && !info.biometricsOn -> "Needs a connection"
                            else -> "Approve this wallet’s payments"
                        },
                        leading = { RowIcon(if (label.endsWith(" ID")) CircasoIcon.FaceId else CircasoIcon.Fingerprint, if (info.biometricsLost) c.warning else c.ink) },
                        // Turning it on runs Circle's set-up with this wallet's PIN; it stays on until Circle says it stopped.
                        // Off needs no server: the wallet goes back to its PIN on this phone. On needs Circle (online).
                        trailing = { CircasoSwitch(info.biometricsOn, actions.onBiometrics, enabled = !busy && (online || info.biometricsOn), modifier = Modifier.semantics { contentDescription = "$label for $name" }) },
                    )
                    RowDivider(Modifier.padding(start = 64.dp))
                }
                ListRow("Change PIN", leading = { RowIcon(CircasoIcon.Lock) }, trailing = { RowChevron() }, onClick = actions.onChangePin)
            }
        }
    }
}

/** GW7: a target needs a wallet; the owner goes straight into the set-up, then on to the target form. */
@Composable
fun ColumnScope.TargetNeedsWalletContent(chat: Chat?, onSetUp: () -> Unit, onNotNow: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val name = chat?.title ?: "the group"
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WalletAvatar(name, chat?.avatarUrl, group = true)
        BasicText("A target needs a group wallet", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText("Contributions to the target go into $name’s own wallet, not anyone’s personal one. Set it up first; it takes a minute.", style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
    CircasoButton("Set Up Group Wallet", onSetUp, size = ButtonSize.Large, block = true)
    CircasoButton("Not Now", onNotNow, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
}

/** CH9 from group info: the owner picks who approves payouts; every member is told (`group.updated`). */
@Composable
fun ColumnScope.ApprovalPolicyContent(current: ApprovalPolicy, choice: ApprovalPolicy, busy: Boolean, online: Boolean, onChoose: (ApprovalPolicy) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    BasicText("Who approves payouts", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink))
    val policies = ApprovalPolicy.entries
    SegmentedControl(listOf("2 admins", "Any admin"), policies.indexOf(choice), { onChoose(policies[it]) })
    BasicText(
        if (choice == ApprovalPolicy.AnyAdmin) "One admin’s approval is enough, and an admin’s own request is approved at once. Everyone in the group is told about the change."
        else "Two admins approve each payout (every admin when there are fewer); the admin who asks counts as one. Everyone in the group is told about the change.",
        style = t.subhead.copy(color = c.inkSecondary),
    )
    BasicText("Requests already made keep the rule they were made under.", style = t.footnote.copy(color = c.inkSecondary))
    CircasoButton("Save", onSave, size = ButtonSize.Large, block = true, enabled = online && choice != current, busy = busy)
    if (!online) BasicText("Needs a connection", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    CircasoButton("Cancel", onCancel, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
}

/**
 * §11.9 (2): a signer can't leave while the group wallet holds money, because only they can send from it; leaving
 * would lock everyone's money. Says how much is there and the way out (an approved payout; Hand over comes later).
 */
@Composable
fun ColumnScope.LeaveBlockedContent(chat: Chat?, balance: String?, onPayout: () -> Unit, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    val name = chat?.title ?: "the group"
    Column(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s2, vertical = CircasoSpacing.s2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        WalletAvatar(name, chat?.avatarUrl, group = true)
        BasicText("You can’t leave yet", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink, textAlign = TextAlign.Center))
        BasicText(
            "$name’s wallet holds ${balance ?: "money"}, and only you can send from it. If you left, nobody could move that money until you came back.",
            style = t.subhead.copy(color = c.inkSecondary, textAlign = TextAlign.Center),
        )
        BasicText("Pay it out first: ask for a payout, and once the admins approve it, pay it with the wallet’s PIN. Handing the wallet to someone else is coming.", style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
    }
    CircasoButton("Open the Group Wallet", onPayout, size = ButtonSize.Large, block = true)
    CircasoButton("Not Now", onClose, style = ButtonStyle.Plain, size = ButtonSize.Large, block = true)
}

/** Change PIN for a group wallet: Circle can do it, but the server has no route for it yet (W8). Honest, no fake flow. */
@Composable
fun ColumnScope.ChangePinComingContent(name: String, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    BasicText("Changing $name’s PIN is coming", Modifier.semantics { heading() }, style = t.title2.copy(color = c.ink))
    BasicText("For now the PIN you set stays.", style = t.subhead.copy(color = c.inkSecondary))
    CircasoButton("OK", onClose, size = ButtonSize.Large, block = true)
}
