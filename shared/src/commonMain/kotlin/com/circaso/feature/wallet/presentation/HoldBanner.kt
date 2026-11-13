package com.circaso.feature.wallet.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.Banner
import com.circaso.designsystem.chrome.BannerTone
import com.circaso.designsystem.chrome.CircasoSheet
import com.circaso.designsystem.components.ButtonSize
import com.circaso.designsystem.components.ButtonStyle
import com.circaso.designsystem.components.CircasoButton
import com.circaso.designsystem.components.CircasoIcon
import com.circaso.designsystem.motion.pressable
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.designsystem.theme.CircasoTheme
import kotlin.time.Instant

/** Hours until an ISO instant, rounded up; null when it has passed or does not parse. */
fun hoursUntil(iso: String?, now: Long = nowMillis()): Long? {
    val at = try { Instant.parse(iso ?: return null).toEpochMilliseconds() } catch (e: Exception) { return null }
    val left = at - now
    return if (left <= 0) null else (left + 3_599_999) / 3_600_000
}

/** The server's hold reason (`GET /v1/wallet` `hold.reason`): the PIN Vault was opened (2026-11-11); otherwise a recovery. */
const val VaultOpenedHold = "VAULT_OPENED"

/** Design ID-23: the hold banner on the Chats tab, with its sheet; tap "Details" or the banner. */
@Composable
fun HoldBanner(holdUntil: String?, reason: String? = null, floating: Boolean = false, onNotMe: () -> Unit) {
    val hours = hoursUntil(holdUntil) ?: return
    var sheet by remember { mutableStateOf(false) }
    val why = if (reason == VaultOpenedHold) "Your PIN Vault was opened" else "Your account was recovered"
    Box(Modifier.fillMaxWidth().padding(horizontal = CircasoSpacing.s4, vertical = CircasoSpacing.s2).pressable(role = Role.Button, onClick = { sheet = true })) {
        Banner("Sending resumes in $hours h. $why. Details", CircasoIcon.Clock, tone = BannerTone.Warning, floating = floating)
    }
    // The sheet lives in whatever Box the banner sits in; the scaffold's banner slot is one.
    androidx.compose.foundation.layout.Box { HoldSheet(sheet, hours, { sheet = false }, onNotMe, reason) }
}

@Composable
fun androidx.compose.foundation.layout.BoxScope.HoldSheet(visible: Boolean, hours: Long, onDismiss: () -> Unit, onNotMe: () -> Unit, reason: String? = null) {
    CircasoSheet(visible, onDismiss, label = "Sending is paused") {
        val body = if (reason == VaultOpenedHold) "Your PIN Vault was opened on this phone, so sending is paused for 24 hours to keep your money safe. You can still chat and receive money."
        else "Your account was recovered on this phone, so sending is paused for 48 hours to keep your money safe. You can still chat and receive money."
        // Design ID-23: the clock in its grey round badge, the countdown as the title, OK, and the way to say it wasn't
        // you. "This Wasn't Me" is a full-width plain button like every other sheet's way out (the frame's is compact).
        com.circaso.designsystem.chrome.SheetMessage("Sending resumes in $hours h", body, CircasoIcon.Clock)
        com.circaso.designsystem.chrome.SheetActions("OK", onDismiss, "This Wasn't Me", { onDismiss(); onNotMe() })
    }
}
