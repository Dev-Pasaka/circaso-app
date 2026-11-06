package com.circaso.core.web

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a link the app opens goes (Pasaka, 2026-11-06): web pages stay in Circaso, in the in-app browser; anything
 * that belongs to another app (mail, phone, an app store, the OS) goes out to the system; anything that could run
 * code or read files is dropped.
 */
enum class LinkTarget { InApp, System, Blocked }

/** What the in-app browser does with a navigation the page asked for. */
sealed interface NavDecision {
    /** Let the web view load it. */
    data object Load : NavDecision

    /** Hand it to the system (another app); the web view stays where it is. */
    data object OpenInSystem : NavDecision

    /** Load this http(s) page instead (an Android `intent:` link's browser fallback). */
    data class LoadInstead(val url: String) : NavDecision

    /** Do nothing. */
    data object Block : NavDecision
}

/** Schemes that run code, read local files or reach into the app: never opened from a link. */
private val DangerousSchemes = setOf("javascript", "vbscript", "data", "file", "content", "blob", "about", "intent", "android-app", "jar")

/** Hosts whose links are an app store page: the store app opens them, not a web page. */
private val StoreHosts = setOf("apps.apple.com", "itunes.apple.com", "play.google.com", "market.android.com")

/** The lower-case scheme of [url] (`https`, `mailto`), or null when it has none. */
fun linkScheme(url: String): String? {
    val colon = url.indexOf(':').takeIf { it > 0 } ?: return null
    val scheme = url.substring(0, colon)
    if (!scheme[0].isLetter() || !scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
    return scheme.lowercase()
}

/**
 * The host of an http(s) [url], lower case, without user info or port; null when there isn't one. The part after
 * the last `@` is the real host, so `https://bank.com@evil.example` is `evil.example`.
 */
fun linkHost(url: String): String? {
    val scheme = linkScheme(url) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val rest = url.substring(scheme.length + 1)
    if (!rest.startsWith("//")) return null
    val authority = rest.substring(2).takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    return host.trimEnd('.').lowercase().takeIf { it.isNotBlank() }
}

/** The domain a person reads in the browser's top bar: the host without `www.`. */
fun displayHost(url: String): String? = linkHost(url)?.removePrefix("www.")

private fun isStoreLink(url: String): Boolean {
    val host = linkHost(url) ?: return false
    return host in StoreHosts && (host != "play.google.com" || url.contains("/store/apps"))
}

/** Where a link the app itself opens (a button, Asha's answer) goes. */
fun classifyLink(url: String): LinkTarget {
    val trimmed = url.trim()
    val scheme = linkScheme(trimmed) ?: return LinkTarget.Blocked
    return when {
        scheme == "http" || scheme == "https" -> when {
            linkHost(trimmed) == null -> LinkTarget.Blocked
            isStoreLink(trimmed) -> LinkTarget.System
            else -> LinkTarget.InApp
        }
        scheme in DangerousSchemes -> LinkTarget.Blocked
        // mailto:, tel:, sms:, geo:, market:, itms-apps:, app-settings: and other apps' own links.
        else -> LinkTarget.System
    }
}

/**
 * What the in-app browser does when the page navigates to [url]. Web pages load in place. Another app's link opens
 * only when the person tapped it ([userGesture]); a page can never open an app on its own. Frames inside the page may
 * use `about:`, `data:` and `blob:` (pages build themselves with them); the page itself never navigates to one.
 */
fun decideNavigation(url: String, mainFrame: Boolean, userGesture: Boolean): NavDecision {
    val trimmed = url.trim()
    val scheme = linkScheme(trimmed) ?: return NavDecision.Block
    return when (scheme) {
        "http", "https" -> when {
            linkHost(trimmed) == null -> NavDecision.Block
            mainFrame && userGesture && isStoreLink(trimmed) -> NavDecision.OpenInSystem
            else -> NavDecision.Load
        }
        "about" -> if (mainFrame && trimmed != "about:blank") NavDecision.Block else NavDecision.Load
        "data", "blob" -> if (mainFrame) NavDecision.Block else NavDecision.Load
        "intent" -> {
            // Never launch the component an intent names; follow its web fallback when the person tapped it.
            val fallback = intentFallback(trimmed)
            if (mainFrame && userGesture && fallback != null && classifyLink(fallback) == LinkTarget.InApp) NavDecision.LoadInstead(fallback)
            else NavDecision.Block
        }
        in DangerousSchemes -> NavDecision.Block
        else -> if (userGesture && classifyLink(trimmed) == LinkTarget.System) NavDecision.OpenInSystem else NavDecision.Block
    }
}

/** The `S.browser_fallback_url` of an Android `intent:` link, decoded; null when it has none. */
private fun intentFallback(url: String): String? {
    val extras = url.substringAfter("#Intent;", "").substringBefore(";end")
    val raw = extras.split(';').firstOrNull { it.startsWith("S.browser_fallback_url=") }?.substringAfter('=') ?: return null
    return percentDecode(raw)
}

private fun percentDecode(s: String): String {
    val bytes = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '%' && i + 2 <= s.lastIndex) {
            val b = s.substring(i + 1, i + 3).toIntOrNull(16)
            if (b != null) { bytes.add(b.toByte()); i += 3; continue }
        }
        ch.toString().encodeToByteArray().forEach { bytes.add(it) }
        i++
    }
    return bytes.toByteArray().decodeToString()
}

/** A page the in-app browser shows; [id] makes opening the same link twice load it fresh. */
data class OpenPage(val url: String, val id: Long)

/** The one in-app browser, drawn over everything by `InAppBrowserHost` at the app's root. */
object InAppBrowser {
    private val _page = MutableStateFlow<OpenPage?>(null)
    val page: StateFlow<OpenPage?> = _page.asStateFlow()
    private var next = 0L

    fun open(url: String) { _page.value = OpenPage(url.trim(), ++next) }

    fun close() { _page.value = null }
}
