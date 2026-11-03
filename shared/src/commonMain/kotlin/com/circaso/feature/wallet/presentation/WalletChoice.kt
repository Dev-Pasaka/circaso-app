package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.SheetHead
import com.circaso.designsystem.components.Avatar
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.components.CircasoIconImage
import com.circaso.designsystem.components.RowDivider
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoShapes
import com.circaso.designsystem.theme.CircasoSizes
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import com.circaso.feature.chama.presentation.amountText
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.wallet.domain.SignSubject
import com.circaso.feature.wallet.domain.Wallet
import com.circaso.feature.wallet.domain.WalletListing
import com.circaso.feature.wallet.domain.WalletOwnerType
import com.circaso.feature.wallet.domain.WalletStatus

/**
 * A wallet as the screens name it. The server never knows a group wallet's name (the group's name is sealed), so it
 * comes from the group on this phone; the person's own is "Personal". [walletId] null is the personal wallet, so the
 * wallet routes are called without `walletId` for it, exactly as before group wallets.
 */
data class NamedWallet(
    val walletId: String?,
    val chatId: String?,
    val name: String,
    val avatarUrl: String?,
    val kind: WalletOwnerType,
    val canSign: Boolean,
    val status: WalletStatus,
    val signerUserId: String?,
) {
    val personal get() = walletId == null
    val subject get() = if (walletId == null) SignSubject(null, PersonalName) else SignSubject.group(walletId, name)

    companion object {
        const val PersonalName = "Personal"
        val Personal = NamedWallet(null, null, PersonalName, null, WalletOwnerType.Person, true, WalletStatus.Active, null)
    }
}

/** W16's list with names from the person's chats. A group the phone doesn't know yet reads "Group wallet". */
fun nameWallets(listings: List<WalletListing>, chats: List<ChatSummary>, myAvatar: String? = null): List<NamedWallet> = listings.map { w ->
    if (w.personal) NamedWallet(null, null, NamedWallet.PersonalName, myAvatar, WalletOwnerType.Person, true, w.status, w.owner.signerUserId)
    else {
        val chat = chats.firstOrNull { it.chat.chatId == w.owner.chatId }?.chat
        NamedWallet(w.walletId, w.owner.chatId, chat?.title ?: "Group wallet", chat?.avatarUrl, w.owner.type, w.canSign, w.status, w.owner.signerUserId)
    }
}

/**
 * The wallet's picture, with the wallet mark on it for a group or space (GW2, GW5): so the group's own photo can't be
 * mistaken for a person's. Personal wallets show the person's photo alone.
 */
@Composable
fun WalletAvatar(name: String, avatarUrl: String?, group: Boolean, size: Dp = CircasoSizes.avatarLg) {
    val c = CircasoTheme.colors
    Box {
        Avatar(name, size = size, imageUrl = avatarUrl)
        if (group) {
            val badge = (size.value * 0.42f).coerceAtLeast(18f).dp
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(badge + 6.dp).clip(CircasoShapes.circle).background(c.bg),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(badge).clip(CircasoShapes.circle).background(c.action), contentAlignment = Alignment.Center) {
                    CircasoIconImage(CircasoIcon.Wallet, c.onAction, Modifier.size(badge * 0.55f))
                }
            }
        }
    }
}

/** The "Paying from" chip on the review (GW9b): the wallet's picture and name, and a chevron that opens GW10. */
@Composable
fun PayingFromChip(wallet: NamedWallet, onClick: (() -> Unit)?) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    Row(
        Modifier.clip(CircasoShapes.pill).background(c.fill)
            .then(if (onClick != null) Modifier.pressable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = if (onClick != null) "Paying from ${wallet.name}, change" else "Paying from ${wallet.name}, fixed by the approval" }
            .padding(start = 4.dp, end = if (onClick != null) 10.dp else 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Avatar(wallet.name, size = 22.dp, imageUrl = wallet.avatarUrl)
        BasicText(wallet.name, Modifier.widthIn(max = 150.dp), style = t.subhead.copy(color = c.ink, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        CircasoIconImage(if (onClick != null) CircasoIcon.ChevronDown else CircasoIcon.Lock, c.inkSecondary, Modifier.size(14.dp))
    }
}

/**
 * GW10: every wallet the person can send from, with its balance and kind. A group wallet is a request (payouts need
 * approval); each one asks for its own PIN. Offline it still shows the last balances, "as of" when they were read.
 */
@Composable
fun ColumnScope.PayFromSheetContent(
    wallets: List<NamedWallet>,
    selected: String?,
    balances: Map<String?, Wallet?>,
    online: Boolean,
    asOf: String?,
    onPick: (NamedWallet) -> Unit,
    onClose: () -> Unit,
) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    SheetHead("Pay from", onClose)
    if (!online) Banner("You're offline. Money sends only while you're connected.", CircasoIcon.Offline)
    Column(
        Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.lg)
            .semantics { contentDescription = "Pay from" },
    ) {
        wallets.forEachIndexed { i, w ->
            if (i > 0) RowDivider(Modifier.padding(start = 72.dp))
            val on = w.walletId == selected
            val balance = balances[w.walletId]
            val money = balance?.let { b -> b.usdc?.let { amountText(it.amountMinor, b.fiat) } }
            val kind = when { w.personal -> "your own money"; w.kind == WalletOwnerType.Space -> "space wallet"; else -> "group wallet, payouts need approval" }
            val sub = listOfNotNull(money, kind).joinToString(" · ") + (if (!online && asOf != null) " · as of $asOf" else "")
            Row(
                Modifier.fillMaxWidth().pressable(role = Role.RadioButton, onClick = { onPick(w) })
                    .semantics(mergeDescendants = true) { this.selected = on }
                    .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                Avatar(w.name, size = CircasoSizes.avatarMd, imageUrl = w.avatarUrl)
                Column(Modifier.weight(1f)) {
                    BasicText(w.name, style = t.body.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(sub, style = t.subhead.copy(color = c.inkSecondary), maxLines = 2)
                }
                RadioMark(on)
            }
        }
    }
    BasicText("Only wallets you can send from are here. Each one asks for its own PIN.", Modifier.fillMaxWidth(), style = t.footnote.copy(color = c.inkSecondary, textAlign = TextAlign.Center))
}

/** The design's `.radio`: a ring, filled with the action colour when chosen. */
@Composable
fun RadioMark(on: Boolean) {
    val c = CircasoTheme.colors
    Box(
        Modifier.size(22.dp).clip(CircasoShapes.circle).border(2.dp, if (on) c.action else c.fillStrong, CircasoShapes.circle),
        contentAlignment = Alignment.Center,
    ) { if (on) Box(Modifier.size(12.dp).clip(CircasoShapes.circle).background(c.action)) }
}

/** GW16's switcher: every wallet the person can see. Members can look at a group's wallet; only its signer sends. */
@Composable
fun ColumnScope.WalletPickContent(wallets: List<NamedWallet>, selected: String?, onPick: (NamedWallet) -> Unit, onClose: () -> Unit) {
    val c = CircasoTheme.colors
    val t = CircasoTheme.typography
    SheetHead("Wallets", onClose)
    Column(Modifier.fillMaxWidth().clip(CircasoShapes.lg).background(c.surfaceRaised).border(1.dp, c.separator, CircasoShapes.lg)) {
        wallets.forEachIndexed { i, w ->
            if (i > 0) RowDivider(Modifier.padding(start = 72.dp))
            val on = w.walletId == selected
            val sub = when {
                w.personal -> "Your own money"
                w.status == WalletStatus.SettingUp -> "Group wallet · being set up"
                w.canSign -> "Group wallet · you send, after approval"
                else -> "Group wallet · you can see it"
            }
            Row(
                Modifier.fillMaxWidth().pressable(role = Role.RadioButton, onClick = { onPick(w) })
                    .semantics(mergeDescendants = true) { this.selected = on }
                    .padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s3),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CircasoSpacing.s3),
            ) {
                WalletAvatar(w.name, w.avatarUrl, group = !w.personal, size = CircasoSizes.avatarMd)
                Column(Modifier.weight(1f)) {
                    BasicText(w.name, style = t.body.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicText(sub, style = t.subhead.copy(color = c.inkSecondary))
                }
                RadioMark(on)
            }
        }
    }
}
