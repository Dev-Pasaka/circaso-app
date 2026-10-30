package com.circaso.feature.support

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.account.domain.AccountRepository
import com.circaso.feature.account.domain.RecoveryCase
import com.circaso.feature.account.domain.RequestRecoveryUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatSummary
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.MessagesRepository
import com.circaso.feature.messaging.domain.ObserveChatsUseCase
import com.circaso.feature.messaging.domain.OpenDirectChatUseCase
import com.circaso.feature.messaging.domain.SendMessageUseCase
import com.circaso.feature.profile.domain.FindByHandleUseCase
import com.circaso.feature.profile.domain.ProfileRepository
import com.circaso.feature.support.presentation.HelpViewModel
import com.circaso.feature.support.presentation.LostAccessViewModel
import com.circaso.feature.support.presentation.caseNumber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun <reified T : Any> fake(noinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (m.name) { "toString" -> "fake"; "hashCode" -> 0; "equals" -> false; else -> answer(m.name, list) }
    } as T

private fun member(id: String, handle: String?) = ChatMember(id, id, handle, null, MemberRole.Member, "2026-10-01T00:00:00Z")
private fun direct(chatId: String, peer: String, handle: String?) = Chat(chatId, ChatKind.Direct, peer, null, listOf(member("me", "me"), member(peer, handle)), false, 1, 0, MemberRole.Member, peer)

private class Monitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

@OptIn(ExperimentalCoroutinesApi::class)
class SupportTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var connection: ConnectionState
    private val chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    private val opened = mutableListOf<String>()
    private val sent = mutableListOf<Pair<String, String>>()

    private val chatsRepo = fake<ChatsRepository> { name, args ->
        when (name) {
            "getChats" -> chats
            "openDirect" -> { opened += args[0] as String; AppResult.Success(direct("c-" + args[0], args[0] as String, null)) }
            else -> null
        }
    }
    private val messages = fake<MessagesRepository> { name, args -> if (name == "send") { sent += (args[0] as String) to (args[1] as String); Unit } else null }
    private val profiles = fake<ProfileRepository> { _, _ -> null }

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        connection = ConnectionState(Monitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        Support.UserId = "support-id"
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); Support.UserId = null }

    private fun help() = HelpViewModel(ObserveChatsUseCase(chatsRepo), FindByHandleUseCase(profiles), OpenDirectChatUseCase(chatsRepo), SendMessageUseCase(messages), connection, myUserId = { "me" })

    @Test fun onlyThePinnedSupportAccountIsSupport() {
        assertTrue(Support.isSupportChat(direct("c1", "support-id", "anything"), "me"))
        // Whoever holds the handle is not support: handles can be claimed.
        assertFalse(Support.isSupportChat(direct("c2", "someone", "circaso"), "me"))
        Support.UserId = null
        assertFalse(Support.isSupportChat(direct("c1", "support-id", "circaso"), "me"))
    }

    @Test fun aTopicOpensTheSupportChatWithItsOpener() = runTest(dispatcher) {
        val vm = help()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val open = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { vm.opened.collect { open += it } }
        vm.onTopic(Support.Topic.Payment)
        assertEquals(listOf("support-id"), opened)
        assertEquals(listOf("c-support-id" to Support.Topic.Payment.opener), sent)
        assertEquals(listOf("c-support-id"), open)
    }

    @Test fun anExistingSupportChatIsReused() = runTest(dispatcher) {
        chats.value = listOf(ChatSummary(direct("old", "support-id", "circaso"), null, 0))
        val vm = help()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        val open = mutableListOf<String>()
        backgroundScope.launch(dispatcher) { vm.opened.collect { open += it } }
        vm.onChat()
        assertTrue(opened.isEmpty())
        assertEquals(listOf("old"), open)
    }

    @Test fun withoutTheSupportAccountHelpSaysSo() = runTest(dispatcher) {
        Support.UserId = null
        val vm = help()
        backgroundScope.launch(dispatcher) { vm.state.collect { } }
        vm.onChat()
        assertTrue(opened.isEmpty())
        assertEquals("Support isn't set up yet. Ask Asha, or try again later.", vm.state.value.error)
    }

    @Test fun caseNumbersAreCircasoAndReadable() {
        assertEquals("CIR-1A2B-3C4D", caseNumber("0192f0aa-0000-7000-8000-00001a2b3c4d"))
    }

    @Test fun aLostAccessCaseNeedsAnEmailAndShowsItsNumber() = runTest(dispatcher) {
        var asked: List<Any?>? = null
        val accounts = fake<AccountRepository> { name, args ->
            if (name == "requestRecovery") { asked = args; AppResult.Success(RecoveryCase("0192f0aa-0000-7000-8000-00001a2b3c4d", "OPEN", null, null)) } else null
        }
        val vm = LostAccessViewModel("+254712345678", RequestRecoveryUseCase(accounts))
        vm.onEmail("not-an-email")
        assertFalse(vm.state.value.canSend)
        vm.onEmail("amina@example.com")
        vm.onNote("Lost my Google account")
        vm.onSend()
        assertEquals("CIR-1A2B-3C4D", vm.state.value.caseNumber)
        assertEquals(listOf("+254712345678", "amina@example.com", "Lost my Google account"), asked)
    }

    @Test fun aRefusedCaseSaysWhy() = runTest(dispatcher) {
        val accounts = fake<AccountRepository> { name, _ -> if (name == "requestRecovery") AppResult.Failure(AppError.Api("identity.recovery_not_eligible", 409, null, null, emptyMap())) else null }
        val vm = LostAccessViewModel("+254712345678", RequestRecoveryUseCase(accounts))
        vm.onEmail("amina@example.com")
        vm.onSend()
        assertNull(vm.state.value.caseNumber)
        assertEquals("We can't start a recovery for that number.", vm.state.value.error)
    }
}
