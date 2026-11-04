package com.circaso.feature.assistant

import com.circaso.feature.assistant.data.remote.AshaTagsParser
import com.circaso.feature.assistant.data.remote.LiveFrame
import com.circaso.feature.assistant.data.remote.LiveSocket
import com.circaso.feature.assistant.data.remote.LiveVoiceSocketSession
import com.circaso.feature.assistant.data.remote.VoiceDto
import com.circaso.feature.assistant.data.remote.VoicesResponse
import com.circaso.feature.assistant.data.remote.authFrame
import com.circaso.feature.assistant.data.repository.AshaLookRepository
import com.circaso.feature.assistant.domain.AshaActivity
import com.circaso.feature.assistant.domain.AshaAvatarState
import com.circaso.feature.assistant.domain.AshaLook
import com.circaso.feature.assistant.domain.AshaMascotContext
import com.circaso.feature.assistant.domain.AshaMascotRules
import com.circaso.feature.assistant.domain.AshaMascotTags
import com.circaso.feature.assistant.domain.AshaMood
import com.circaso.feature.assistant.domain.AshaReaction
import com.circaso.feature.assistant.domain.AshaScene
import com.circaso.feature.assistant.domain.AshaTags
import com.circaso.feature.assistant.domain.LiveVoiceEvent
import com.circaso.feature.assistant.domain.SassLevel
import com.circaso.feature.assistant.presentation.AshaUiState
import com.circaso.feature.assistant.presentation.VoiceEnding
import com.circaso.feature.assistant.presentation.VoicePhase
import com.circaso.feature.assistant.presentation.VoiceUiState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

class AshaMascotTest {
    private val online = AshaMascotContext(online = true)

    // The Rive file's enum strings, exactly (asha.riv view model `Asha`).

    @Test fun enumsMapToTheRiveStrings() {
        assertEquals(listOf("asha", "robbie"), AshaLook.entries.map { it.rive })
        assertEquals("beach_sunset", AshaScene.BeachSunset.rive)
        assertEquals("searching_web", AshaActivity.SearchingWeb.rive)
        assertEquals("building_sandcastle", AshaActivity.BuildingSandcastle.rive)
        assertEquals("idle_quirk", AshaReaction.IdleQuirk.rive)
        assertEquals(15, AshaScene.entries.size)
        assertEquals(12, AshaMood.entries.size)
        assertEquals(23, AshaActivity.entries.size)
        assertEquals(7, AshaReaction.entries.size)
    }

    @Test fun unknownServerValuesAreIgnored() {
        assertEquals(AshaScene.Crypto, AshaScene.parse("crypto"))
        assertEquals(AshaMood.Proud, AshaMood.parse(" PROUD "))
        assertNull(AshaScene.parse("moon_base"))
        assertNull(AshaMood.parse(null))
        assertNull(AshaActivity.parse(""))
        assertNull(AshaReaction.parse("backflip"))
    }

    @Test fun unknownValuesFallBackToNoSceneCalmIdle() {
        val tags = AshaMascotTags().merge(AshaTags(scene = AshaScene.parse("moon_base"), mood = AshaMood.parse("??"), activity = AshaActivity.parse("dancing")))
        assertEquals(AshaAvatarState(), AshaMascotRules.resolve(AshaLook.Asha, tags, online))
    }

    // Look from the voice's gender (A12 `gender`).

    @Test fun lookFollowsTheVoicesGender() {
        assertEquals(AshaLook.Robbie, AshaLook.forVoiceGender("male"))
        assertEquals(AshaLook.Robbie, AshaLook.forVoiceGender("Male"))
        assertEquals(AshaLook.Asha, AshaLook.forVoiceGender("female"))
        assertEquals(AshaLook.Asha, AshaLook.forVoiceGender("neutral"))
        assertEquals(AshaLook.Asha, AshaLook.forVoiceGender(null))
    }

    @Test fun theVoiceInUseIsTheChoiceElseTheDefault() {
        val voices = VoicesResponse(listOf(VoiceDto("aura-asteria", "Asteria", gender = "female", default = true), VoiceDto("aura-orion", "Orion", gender = "male")))
        assertEquals("male", AshaLookRepository.genderInUse(voices, "aura-orion"))
        assertEquals("female", AshaLookRepository.genderInUse(voices, null))
        assertEquals("female", AshaLookRepository.genderInUse(voices, "a-voice-that-was-removed"))
        assertNull(AshaLookRepository.genderInUse(VoicesResponse(), null))
    }

    // Sass rules (asha-mascot-personality.md).

    @Test fun sassyStaysSassyInAnOrdinaryConversation() {
        assertEquals(AshaMood.Sassy, AshaMascotRules.moodFor(AshaMood.Sassy, sensitive = false, context = online))
        assertEquals(AshaMood.Sassy, AshaMascotRules.moodFor(AshaMood.Sassy, sensitive = false, context = online.copy(sass = SassLevel.Sassy)))
    }

    @Test fun noSassInSupportOnSensitiveTurnsOrWhenTheSettingIsCalm() {
        assertEquals(AshaMood.Calm, AshaMascotRules.moodFor(AshaMood.Sassy, sensitive = false, context = online.copy(support = true)))
        assertEquals(AshaMood.Calm, AshaMascotRules.moodFor(AshaMood.Sassy, sensitive = true, context = online))
        assertEquals(AshaMood.Calm, AshaMascotRules.moodFor(AshaMood.Sassy, sensitive = false, context = online.copy(sass = SassLevel.Calm)))
        // Only sassy is replaced: caring stays caring in support.
        assertEquals(AshaMood.Caring, AshaMascotRules.moodFor(AshaMood.Caring, sensitive = true, context = online.copy(support = true)))
    }

    @Test fun aLaterFrameOfASensitiveTurnCantMakeSassAcceptable() {
        val tags = AshaMascotTags()
            .merge(AshaTags(mood = AshaMood.Sassy, sensitive = true))
            .merge(AshaTags(activity = AshaActivity.ReadingTransactions)) // same answer, no sensitive flag repeated
        assertEquals(AshaMood.Calm, AshaMascotRules.resolve(AshaLook.Asha, tags, online).mood)
        // A new, ordinary mood resets it.
        assertEquals(AshaMood.Happy, AshaMascotRules.resolve(AshaLook.Asha, tags.merge(AshaTags(mood = AshaMood.Happy)), online).mood)
    }

    // Offline.

    @Test fun offlineIsNoSceneCalmIdleWithTheLookKept() {
        val tags = AshaMascotTags(scene = AshaScene.Beach, mood = AshaMood.Excited, activity = AshaActivity.Sipping)
        val shown = AshaMascotRules.resolve(AshaLook.Robbie, tags, AshaMascotContext(online = false), appActivity = AshaActivity.Speaking, voiceLevel = 0.8f)
        assertEquals(AshaAvatarState(look = AshaLook.Robbie, scene = AshaScene.None, mood = AshaMood.Calm, activity = AshaActivity.Idle, voiceLevel = 0f), shown)
        assertTrue(!AshaMascotRules.allowReaction(AshaReaction.Thanks, AshaMascotContext(online = false)))
        assertTrue(AshaMascotRules.allowReaction(AshaReaction.Poke, AshaMascotContext(online = false)))
    }

    @Test fun offlineChatShowsTheOfflinePose() {
        val state = AshaUiState(online = false, mascotTags = AshaMascotTags(scene = AshaScene.Gym, mood = AshaMood.Proud))
        assertEquals(AshaAvatarState.Offline, state.mascot)
    }

    // Activities: what the screen knows vs what the server says.

    @Test fun theServersActivityShowsOnlyWhileSheWorks() {
        val tags = AshaMascotTags(activity = AshaActivity.MakingChart)
        assertEquals(AshaActivity.MakingChart, AshaMascotRules.resolve(AshaLook.Asha, tags, online, appActivity = AshaActivity.Thinking).activity)
        assertEquals(AshaActivity.Thinking, AshaMascotRules.resolve(AshaLook.Asha, AshaMascotTags(), online, appActivity = AshaActivity.Thinking).activity)
        assertEquals(AshaActivity.Listening, AshaMascotRules.resolve(AshaLook.Asha, tags, online, appActivity = AshaActivity.Listening).activity)
        // Done with the answer: the desk work is released, scene and mood stay.
        val after = tags.copy(scene = AshaScene.Office, mood = AshaMood.Focused).turnDone()
        assertEquals(AshaMascotTags(scene = AshaScene.Office, mood = AshaMood.Focused), after)
    }

    @Test fun voiceLevelMovesTheMouthOnlyWhileSpeaking() {
        assertEquals(0.6f, AshaMascotRules.resolve(AshaLook.Asha, AshaMascotTags(), online, AshaActivity.Speaking, 0.6f).voiceLevel)
        assertEquals(1f, AshaMascotRules.resolve(AshaLook.Asha, AshaMascotTags(), online, AshaActivity.Speaking, 3f).voiceLevel)
        assertEquals(0f, AshaMascotRules.resolve(AshaLook.Asha, AshaMascotTags(), online, AshaActivity.Listening, 0.6f).voiceLevel)
    }

    @Test fun voiceModeMapsItsPhases() {
        assertEquals(AshaActivity.Listening, VoiceUiState(phase = VoicePhase.Listening).mascot.activity)
        val speaking = VoiceUiState(phase = VoicePhase.Speaking, voiceLevel = 0.4f).mascot
        assertEquals(AshaActivity.Speaking, speaking.activity)
        assertEquals(0.4f, speaking.voiceLevel)
        assertEquals(AshaActivity.ReadingTransactions, VoiceUiState(phase = VoicePhase.Thinking, mascotTags = AshaMascotTags(activity = AshaActivity.ReadingTransactions)).mascot.activity)
        assertEquals(AshaAvatarState.Offline, VoiceUiState(phase = VoicePhase.Ended, ending = VoiceEnding.Offline, mascotTags = AshaMascotTags(scene = AshaScene.Beach)).mascot)
    }

    // The server's tags on status events.

    @Test fun tagsAreParsedAdditively() {
        val o = json.parseToJsonElement("""{"label":"Checking…","scene":"trading","mood":"focused","activity":"reading_transactions","reaction":"thanks","sensitive":true}""").jsonObject
        assertEquals(AshaTags(AshaScene.Trading, AshaMood.Focused, AshaActivity.ReadingTransactions, AshaReaction.Thanks, sensitive = true), AshaTagsParser.parse(o))
        assertNull(AshaTagsParser.parse(json.parseToJsonElement("""{"label":"Checking…"}""").jsonObject))
        // Wrong types and unknown values are dropped one by one.
        val odd = json.parseToJsonElement("""{"scene":42,"mood":"giddy","activity":"thinking","sensitive":"yes"}""").jsonObject
        assertEquals(AshaTags(activity = AshaActivity.Thinking), AshaTagsParser.parse(odd))
    }

    @Test fun liveVoiceStatusFramesCarryTheTags() = runTest {
        val socket = TagSocket()
        val session = LiveVoiceSocketSession(socket, json, authFrame("app", "agent", "en", true))
        socket.server("""{"type":"tool_status","label":"Checking your transactions…","scene":"office","activity":"reading_transactions"}""")
        socket.server("""{"type":"tool_done","mood":"happy","reaction":"goal_reached"}""")
        socket.server("""{"type":"tool_status","label":"Looking…"}""")
        socket.close()
        val events = session.events.toList()
        assertEquals(LiveVoiceEvent.Status("Checking your transactions…", AshaTags(scene = AshaScene.Office, activity = AshaActivity.ReadingTransactions)), events[0])
        assertEquals(LiveVoiceEvent.Status(null, AshaTags(mood = AshaMood.Happy, reaction = AshaReaction.GoalReached)), events[1])
        assertEquals(LiveVoiceEvent.Status("Looking…", null), events[2])
    }
}

private class TagSocket : LiveSocket {
    private val fromServer = Channel<LiveFrame>(Channel.UNLIMITED)
    override val incoming: Flow<LiveFrame> = fromServer.consumeAsFlow()
    override suspend fun sendText(text: String) {}
    override suspend fun sendBinary(bytes: ByteArray) {}
    override suspend fun close() { fromServer.close() }
    fun server(text: String) { fromServer.trySend(LiveFrame.Text(text)) }
}
