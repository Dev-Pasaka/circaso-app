package com.circaso.gallery

import androidx.compose.runtime.Composable
import com.circaso.core.sync.nowMillis
import com.circaso.feature.assistant.domain.AshaConversation
import com.circaso.feature.assistant.domain.AshaMessage
import com.circaso.feature.assistant.domain.AshaUsage
import com.circaso.feature.assistant.domain.UsageMeter
import com.circaso.feature.assistant.presentation.AshaContent
import com.circaso.feature.assistant.presentation.AshaMenuActions
import com.circaso.feature.assistant.presentation.AshaSheet
import com.circaso.feature.assistant.presentation.AshaUiState
import com.circaso.feature.assistant.presentation.UsageUi
import com.circaso.feature.assistant.presentation.VoiceContent
import com.circaso.feature.assistant.presentation.VoicePhase
import com.circaso.feature.assistant.presentation.VoiceTurn
import com.circaso.feature.assistant.presentation.VoiceUiState
import kotlin.time.Instant

/** Asha's menu and voice mode with sample data: `--es preview <name>`. */
@Composable
internal fun AshaPreview(name: String): Boolean {
    when (name) {
        "asha-menu" -> Chat(AshaSheet.Menu)
        "asha-menu-offline" -> Chat(AshaSheet.Menu, online = false, usage = UsageUi.Offline)
        "asha-recents" -> Chat(AshaSheet.Recents)
        "asha-recents-delete" -> Chat(AshaSheet.Recents, confirmDelete = "c2")
        "asha-usage" -> Chat(AshaSheet.Usage)
        "asha-usage-unavailable" -> Chat(AshaSheet.Usage, usage = UsageUi.Unavailable)
        "asha-usage-limit" -> Chat(AshaSheet.Usage, usage = UsageUi.Loaded(sampleUsage(used = 50)))
        "asha-voice-listening" -> VoiceContent(
            VoiceUiState(
                phase = VoicePhase.Listening,
                turns = listOf(VoiceTurn("Who did I chat with this week?", listOf("Mostly Amina and Otieno.", "You also caught up with the Kilimani group on Tuesday."), done = true)),
                heard = "And how much did I send Amina",
                micLevel = 0.55f,
                voiceSecondsLeft = 412,
            ),
            {}, {}, {}, {},
        )
        "asha-voice-speaking" -> VoiceContent(
            VoiceUiState(
                phase = VoicePhase.Speaking,
                turns = listOf(
                    VoiceTurn("Who did I chat with this week?", listOf("Mostly Amina and Otieno."), done = true),
                    VoiceTurn("And how much did I send Amina?", listOf("You sent Amina **KES 2,400** this week, in two payments.", "The last one was on Thursday for lunch.")),
                ),
                voiceLevel = 0.7f,
            ),
            {}, {}, {}, {},
        )
        "asha-voice-thinking" -> VoiceContent(
            VoiceUiState(phase = VoicePhase.Thinking, turns = listOf(VoiceTurn("What did I spend on transport last month?")), status = "Checking your transactions…"),
            {}, {}, {}, {},
        )
        "asha-voice-muted" -> VoiceContent(VoiceUiState(phase = VoicePhase.Listening, muted = true), {}, {}, {}, {})
        else -> return false
    }
    return true
}

private val now get() = nowMillis()
private fun ago(minutes: Long) = Instant.fromEpochMilliseconds(now - minutes * 60_000).toString()

private val sampleConversations = listOf(
    AshaConversation("c1", "Lunch spending this week", ago(4)),
    AshaConversation("c2", "Splitting the Naivasha trip", ago(95)),
    AshaConversation("c3", "How chamas pay out", ago(60 * 26)),
    AshaConversation("c4", "Sending money to my sister", ago(60 * 24 * 3)),
    AshaConversation("c5", "Is my wallet backed up?", ago(60 * 24 * 12)),
    AshaConversation("c6", "Fees on a USDC transfer", ago(60 * 24 * 40)),
)

private fun sampleUsage(used: Int = 18) = AshaUsage(
    plan = "free",
    messages = UsageMeter(used, 50),
    voiceSeconds = UsageMeter(7 * 60 + 20, 15 * 60),
    resetsAt = Instant.fromEpochMilliseconds(now + 9L * 24 * 3600_000).toString(),
    periodStart = Instant.fromEpochMilliseconds(now - 21L * 24 * 3600_000).toString(),
    periodEnd = Instant.fromEpochMilliseconds(now + 9L * 24 * 3600_000).toString(),
)

@Composable
private fun Chat(sheet: AshaSheet, online: Boolean = true, usage: UsageUi = UsageUi.Loaded(sampleUsage()), confirmDelete: String? = null) {
    AshaContent(
        AshaUiState(
            messages = listOf(
                AshaMessage("1", "USER", "What did I spend on lunch this week?", null, null, null, "2026-11-05T10:01:00Z", "c1"),
                AshaMessage("2", "ASHA", "About **KES 1,850** over four lunches. Thursday was the biggest at KES 650.", "1", null, null, "2026-11-05T10:01:05Z", "c1"),
            ),
            online = online,
            conversations = sampleConversations,
            currentConversation = "c1",
            sheet = sheet,
            usage = usage,
            confirmDelete = confirmDelete,
        ),
        {}, {}, {}, {}, {}, {},
        menu = AshaMenuActions(onVoiceSettings = {}),
    )
}
