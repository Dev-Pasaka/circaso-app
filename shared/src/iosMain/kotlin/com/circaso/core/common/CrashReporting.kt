package com.circaso.core.common

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.getStackTraceAddresses
import kotlin.native.setUnhandledExceptionHook

/**
 * Crashlytics on iPhone (2026-11-13), implemented in Swift (CrashReporterImpl.swift) because the Firebase pods live in
 * the app, not in this framework. Kotlin crashes would otherwise show in Crashlytics as an unreadable abort in the
 * Kotlin runtime: [installCrashReporting] records each uncaught Kotlin exception with its Kotlin stack (the
 * addresses, symbolised from the app's dSYM) before the runtime ends the app, the way Touchlab's CrashKiOS does.
 */
interface CrashReporterProvider {
    /** A breadcrumb: Crashlytics keeps the last ones with the next crash report. */
    fun log(message: String)

    /** An uncaught Kotlin exception, about to end the app: its type, message and stack addresses. */
    fun recordKotlinCrash(name: String, reason: String, addresses: List<Long>)
}

object CrashReporterBridge {
    var provider: CrashReporterProvider? = null
}

/** Sends Diagnostics notes to Crashlytics as breadcrumbs and records uncaught Kotlin exceptions. Once, at launch. */
@OptIn(ExperimentalNativeApi::class)
fun installCrashReporting() {
    val reporter = CrashReporterBridge.provider ?: return
    Diagnostics.breadcrumbs = { reporter.log(it) }
    var previous: ReportUnhandledExceptionHook? = null
    previous = setUnhandledExceptionHook { e ->
        runCatching {
            val name = e::class.qualifiedName ?: "Throwable"
            reporter.recordKotlinCrash(name, LogScrubber.scrub(e.message.orEmpty()).take(500), e.getStackTraceAddresses())
        }
        previous?.invoke(e)
    }
}
