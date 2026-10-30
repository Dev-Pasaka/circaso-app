package com.circaso.core.sync

import kotlin.time.Clock

/** The current time in epoch milliseconds. One place to read the clock. */
fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
