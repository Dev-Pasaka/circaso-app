package com.circaso.core.push

import com.circaso.core.common.AppError
import com.circaso.core.common.AppResult
import com.circaso.core.platform.PushTokenProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class PushTokenRegistrarTest {
    private class Provider(var token: String?) : PushTokenProvider { override suspend fun token() = token }

    private val dispatcher = UnconfinedTestDispatcher()
    private val ready = MutableStateFlow(false)
    private var user: String? = "u1"
    private val provider = Provider("tok-1")
    private val sent = mutableListOf<String>()
    private var result: AppResult<Unit> = AppResult.Success(Unit)

    private fun registrar(scope: TestScope) = PushTokenRegistrar(ready, { user }, provider, { sent += it; result }, scope.backgroundScope)

    @Test fun theTokenIsSentOnceWhenTheyAreSignedInAndOnline() = runTest(dispatcher) {
        registrar(this).start()
        assertEquals(emptyList(), sent) // not ready yet
        ready.value = true
        assertEquals(listOf("tok-1"), sent)
        ready.value = false; ready.value = true // a reconnect with nothing new
        assertEquals(listOf("tok-1"), sent)
    }

    @Test fun aNewTokenFromThePlatformIsSent() = runTest(dispatcher) {
        val r = registrar(this); r.start(); ready.value = true
        r.onNewToken("tok-2")
        assertEquals(listOf("tok-1", "tok-2"), sent)
    }

    @Test fun aFailureIsTriedAgainAtTheNextTrigger() = runTest(dispatcher) {
        result = AppResult.Failure(AppError.Network)
        registrar(this).start(); ready.value = true
        assertEquals(listOf("tok-1"), sent)
        result = AppResult.Success(Unit)
        ready.value = false; ready.value = true
        assertEquals(listOf("tok-1", "tok-1"), sent)
    }

    @Test fun noTokenOrNoPersonMeansNothingIsSent() = runTest(dispatcher) {
        provider.token = null
        val r = registrar(this); r.start(); ready.value = true
        assertEquals(emptyList(), sent)
        user = null
        r.onNewToken("tok-9")
        assertEquals(emptyList(), sent)
    }

    @Test fun aDifferentPersonOnTheSamePhoneGetsTheTokenToo() = runTest(dispatcher) {
        val r = registrar(this); r.start(); ready.value = true
        user = "u2"
        r.sync()
        assertEquals(listOf("tok-1", "tok-1"), sent)
    }
}
