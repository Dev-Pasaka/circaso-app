package com.circaso.testing

/** Runs a suspending test body to completion (real time, so `delay` really waits). */
expect fun runSuspending(block: suspend () -> Unit)
