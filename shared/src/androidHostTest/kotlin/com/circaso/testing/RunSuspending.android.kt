package com.circaso.testing

import kotlinx.coroutines.runBlocking

actual fun runSuspending(block: suspend () -> Unit) = runBlocking { block() }
