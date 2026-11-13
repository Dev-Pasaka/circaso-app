package com.circaso.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.circaso.core.sync.nowMillis
import com.circaso.designsystem.chrome.CircasoNavBar
import com.circaso.designsystem.chrome.CircasoScaffold
import com.circaso.designsystem.components.SectionHeader
import com.circaso.designsystem.theme.CircasoSpacing
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.DeliveryState
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.Message
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.presentation.SecurityCodeSection
import com.circaso.feature.messaging.presentation.ThreadContent
import com.circaso.feature.messaging.presentation.ThreadUiState

private val sampleCode = SecurityCode("052183419760337124985006142739581620447395180263749015836472")

/**
 * The security code (2026-11-13), `-preview=`: Contact Info's Encryption section, not verified, verified, before there
 * is a code, `security-code-changed` (checked once, changed since), and `security-code-live` where Mark as Verified works (the sheen). `chat-security-notice` is a thread
 * with "security code changed" notices after my message, after theirs, next to a day label and several in a row.
 */
@Composable
internal fun SecurityCodePreview(name: String): Boolean {
    when (name) {
        "security-code" -> SecurityCodeInfo(sampleCode, verified = false)
        "security-code-verified" -> SecurityCodeInfo(sampleCode, verified = true)
        "security-code-pending" -> SecurityCodeInfo(null, verified = false)
        "security-code-changed" -> SecurityCodeInfo(sampleCode, verified = false, changed = true)
        "security-code-live" -> {
            var verified by remember { mutableStateOf(false) }
            SecurityCodeInfo(sampleCode, verified) { verified = it }
        }
        "chat-security-notice", "group-security-notice" -> SecurityNoticeThread(group = name.startsWith("group"))
        else -> return false
    }
    return true
}

@Composable
private fun SecurityCodeInfo(code: SecurityCode?, verified: Boolean, changed: Boolean = false, onVerified: (Boolean) -> Unit = {}) {
    CircasoScaffold(grouped = true, topBar = { CircasoNavBar(title = "Contact Info", onBack = {}) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + CircasoSpacing.s6)) {
            item {
                SectionHeader("Encryption", Modifier.padding(top = CircasoSpacing.s4))
                SecurityCodeSection(
                    code, verified, changedSinceVerified = changed, myName = "Amina Odhiambo", myPhoto = DemoPhotos.of("Amina"),
                    peerName = "Wanjiru Kamau", peerPhoto = DemoPhotos.Wanjiru, onVerified = onVerified,
                    modifier = Modifier.padding(top = CircasoSpacing.s1, bottom = CircasoSpacing.s3),
                )
            }
        }
    }
}

@Composable
private fun SecurityNoticeThread(group: Boolean) {
    val now = remember { nowMillis() }
    val members = listOf(ChatMember("me", "Amina", "amina", null, MemberRole.Member, ""), ChatMember("w", "Wanjiru Kamau", "wanjiru", DemoPhotos.Wanjiru, MemberRole.Member, ""))
    val chat = if (group) Chat("g", ChatKind.Group, "Holiday Fund", DemoPhotos.HolidayFund, members, false, 2, 0, MemberRole.Member, null)
    else Chat("w", ChatKind.Direct, "Wanjiru Kamau", DemoPhotos.Wanjiru, members, false, 1, 0, MemberRole.Member, "w")
    var id = 0L
    fun at(minutesAgo: Int) = now - minutesAgo * 60_000L
    fun said(text: String, mine: Boolean, minutesAgo: Int) =
        Message.Text(++id, chat.chatId, at(minutesAgo), null, "m$id", if (mine) "me" else "w", if (mine) "You" else "Wanjiru Kamau", mine, text, DeliveryState.Read, null, null)
    fun notice(minutesAgo: Int) = Message.System(++id, chat.chatId, at(minutesAgo), null, "Wanjiru Kamau's security code changed", securityNotice = true)
    val messages = listOf(
        // Yesterday: after their message, then after mine.
        said("Are you coming on Sunday?", false, 1_500),
        notice(1_499),
        said("Yes, after church. I'll bring the chapati.", true, 1_495),
        notice(1_494),
        // Today: first thing of the day (beside the day label), then three in a row.
        notice(40),
        notice(39),
        notice(38),
        said("Did you get a new phone?", true, 30),
        said("Yes! Signed in again this morning.", false, 29),
    )
    ThreadContent(ThreadUiState(chat = chat, messages = messages, myUserId = "me"), {}, {}, null, {}, {}, {}, {}, {}, {}, {}, onSecurityCode = {})
}
