package com.circaso.feature.messaging

import androidx.lifecycle.viewModelScope
import com.circaso.core.network.NetworkHealth
import com.circaso.core.platform.ConnectionStatus
import com.circaso.core.platform.ConnectivityMonitor
import com.circaso.core.sync.ConnectionState
import com.circaso.feature.contacts.domain.ContactsRepository
import com.circaso.feature.contacts.domain.ObserveContactsUseCase
import com.circaso.feature.messaging.domain.AddMembersUseCase
import com.circaso.feature.messaging.domain.Chat
import com.circaso.feature.messaging.domain.ChatKind
import com.circaso.feature.messaging.domain.ChatMember
import com.circaso.feature.messaging.domain.ChatsRepository
import com.circaso.feature.messaging.domain.LeaveGroupUseCase
import com.circaso.feature.messaging.domain.MemberRole
import com.circaso.feature.messaging.domain.ObserveChatUseCase
import com.circaso.feature.messaging.domain.RemoveMemberUseCase
import com.circaso.feature.messaging.domain.RenameGroupUseCase
import com.circaso.feature.messaging.domain.SecurityCode
import com.circaso.feature.messaging.domain.SecurityCodeUseCase
import com.circaso.feature.messaging.domain.SetMembersCanAddUseCase
import com.circaso.feature.messaging.domain.SetRoleUseCase
import com.circaso.feature.messaging.domain.VerifiedCodes
import com.circaso.feature.messaging.presentation.ChatInfoViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private inline fun <reified T : Any> fake(noinline answer: (String, List<Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        val list = args.orEmpty().toList().filterNot { it is kotlin.coroutines.Continuation<*> }
        when (method.name) {
            "toString" -> "fake ${T::class.simpleName}"
            "hashCode" -> 0
            "equals" -> false
            else -> answer(method.name, list)
        }
    } as T

private class ScMonitor : ConnectivityMonitor { override val osStatus = MutableStateFlow(ConnectionStatus.Online) }

/** This phone's note of checked codes, in memory. */
private class MemoryVerifiedCodes(initial: Map<String, String> = emptyMap()) : VerifiedCodes {
    val saved = MutableStateFlow(initial)
    override fun verifiedCode(peerId: String): Flow<String?> = saved.map { it[peerId] }
    override suspend fun setVerifiedCode(peerId: String, digits: String?) { saved.value = if (digits == null) saved.value - peerId else saved.value + (peerId to digits) }
}

/**
 * Mark as Verified (2026-11-13): this phone's note, kept with the very code it was given for, so a new code (the other
 * person signed in again) is not verified until it is checked again.
 */
class SecurityCodeVerifiedTest {
    private val dispatcher = StandardTestDispatcher()
    private val first = "1".repeat(60)
    private val second = "2".repeat(60)
    private var current = first
    private val members = listOf(ChatMember("me", "Amina", null, null, MemberRole.Member, ""), ChatMember("w", "Wanjiru", null, null, MemberRole.Member, ""))
    private val chat = MutableStateFlow<Chat?>(Chat("dm", ChatKind.Direct, "Wanjiru", null, members, false, 1, 0, MemberRole.Member, "w"))
    private val chats = fake<ChatsRepository> { name, _ ->
        when (name) {
            "chat" -> chat
            "securityCode" -> SecurityCode(current)
            else -> Unit
        }
    }
    private val contacts = fake<ContactsRepository> { name, _ -> if (name == "getContacts") flowOf(emptyList<Any>()) else null }

    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.info(store: VerifiedCodes): ChatInfoViewModel {
        val connection = ConnectionState(ScMonitor(), NetworkHealth(), CoroutineScope(dispatcher), offlineDelayMs = 0)
        return ChatInfoViewModel(
            "dm", ObserveChatUseCase(chats), ObserveContactsUseCase(contacts), SecurityCodeUseCase(chats), RenameGroupUseCase(chats),
            SetMembersCanAddUseCase(chats), AddMembersUseCase(chats), RemoveMemberUseCase(chats), LeaveGroupUseCase(chats), SetRoleUseCase(chats),
            connection, myUserId = "me", verifiedCodes = store,
        ).also { runCurrent() }
    }

    private fun ChatInfoViewModel.stop() = viewModelScope.coroutineContext[Job]?.cancel()

    @Test fun markingSavesTheCodeAndClearingForgetsIt() = runTest(dispatcher) {
        val store = MemoryVerifiedCodes()
        val vm = info(store)
        try {
            assertTrue(vm.state.value.securityCodeLoaded)
            assertFalse(vm.state.value.codeVerified, "not checked yet")
            vm.onVerified(true)
            runCurrent()
            assertTrue(vm.state.value.codeVerified)
            assertEquals(first, store.saved.value["w"], "kept with the code itself")
            vm.onVerified(false)
            runCurrent()
            assertFalse(vm.state.value.codeVerified)
            assertEquals(null, store.saved.value["w"])
        } finally { vm.stop() }
    }

    @Test fun aCodeCheckedBeforeIsVerifiedWhenTheScreenOpens() = runTest(dispatcher) {
        val vm = info(MemoryVerifiedCodes(mapOf("w" to first)))
        try {
            assertTrue(vm.state.value.codeVerified)
            assertFalse(vm.state.value.codeChangedSinceVerified)
        } finally { vm.stop() }
    }

    @Test fun aNewCodeIsNotVerifiedAndSaysItChanged() = runTest(dispatcher) {
        val store = MemoryVerifiedCodes(mapOf("w" to first))
        val vm = info(store)
        try {
            assertTrue(vm.state.value.codeVerified)
            // They signed in again: new keys, a new code.
            current = second
            chat.value = chat.value?.copy(version = 2)
            runCurrent()
            assertEquals(second, vm.state.value.securityCode?.digits)
            assertFalse(vm.state.value.codeVerified, "a changed code is not verified")
            assertTrue(vm.state.value.codeChangedSinceVerified, "and the screen says it changed")
            vm.onVerified(true)
            runCurrent()
            assertTrue(vm.state.value.codeVerified)
            assertFalse(vm.state.value.codeChangedSinceVerified)
            assertEquals(second, store.saved.value["w"])
        } finally { vm.stop() }
    }
}
