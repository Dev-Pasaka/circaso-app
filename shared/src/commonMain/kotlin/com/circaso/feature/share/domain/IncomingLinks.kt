package com.circaso.feature.share.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Share links opened from outside the app: `https://<share host>/s/{code}` (Android App Links, iOS Universal Links)
 * and `circaso://s/{code}` (the page's "Open in Circaso" button). The platform hands every URL here; the code waits in
 * [pending] until someone is signed in, then the navigation opens it once. An object, not a Koin binding, because the
 * system can hand over a link before Koin has started.
 */
object IncomingLinks {
    /** The dev host; the build adds the others (Android manifest meta-data, iOS Info.plist `CircasoShareHost`). */
    private val hosts = mutableSetOf("sandbox.circaso.com")

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun addHost(host: String) {
        val h = host.trim().lowercase()
        if (h.isNotEmpty()) hosts += h
    }

    /** True when [url] was a share link and is now pending. */
    fun open(url: String): Boolean {
        val code = codeOf(url, hosts) ?: return false
        _pending.value = code
        return true
    }

    /** Takes the pending code, so it opens once. */
    fun take(): String? = _pending.value.also { _pending.value = null }

    private val CodePattern = Regex("^[A-Za-z0-9]{8,32}$")

    fun isCode(code: String): Boolean = CodePattern.matches(code)

    /**
     * The code in a share link, or null for anything else. Strict: https only on a known host (or the circaso scheme),
     * exactly `/s/{code}` with an optional trailing slash, a base62 code of 8 to 32 characters. Query and fragment are
     * ignored.
     */
    fun codeOf(url: String, knownHosts: Set<String> = hosts): String? {
        val trimmed = url.trim().substringBefore('#').substringBefore('?')
        val rest = when {
            trimmed.startsWith("circaso://s/", ignoreCase = true) -> trimmed.substring("circaso://s/".length)
            trimmed.startsWith("https://", ignoreCase = true) -> {
                val afterScheme = trimmed.substring("https://".length)
                val host = afterScheme.substringBefore('/').lowercase()
                if (host !in knownHosts) return null
                val path = afterScheme.substring(host.length)
                if (!path.startsWith("/s/")) return null
                path.substring("/s/".length)
            }
            else -> return null
        }
        val code = rest.removeSuffix("/")
        return code.takeIf { isCode(it) }
    }
}
