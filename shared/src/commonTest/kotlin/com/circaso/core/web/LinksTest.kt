package com.circaso.core.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    @Test
    fun webPagesOpenInApp() {
        assertEquals(LinkTarget.InApp, classifyLink("https://sepolia.basescan.org/tx/0xabc"))
        assertEquals(LinkTarget.InApp, classifyLink("http://example.com"))
        assertEquals(LinkTarget.InApp, classifyLink("  HTTPS://Explorer.Solana.com/tx/1?cluster=devnet "))
    }

    @Test
    fun otherAppsLinksGoToTheSystem() {
        listOf(
            "mailto:help@circaso.com", "tel:+254700000000", "sms:+254700000000", "geo:0,0?q=1,2",
            "market://details?id=com.circaso", "itms-apps://itunes.apple.com/app/id1", "app-settings:", "whatsapp://send?text=hi",
            "https://apps.apple.com/app/id123", "https://play.google.com/store/apps/details?id=com.circaso",
        ).forEach { assertEquals(LinkTarget.System, classifyLink(it), it) }
    }

    @Test
    fun aPlayPageThatIsNotAnAppStaysInApp() {
        assertEquals(LinkTarget.InApp, classifyLink("https://play.google.com/about/howplayworks"))
    }

    @Test
    fun codeAndLocalFilesAreBlocked() {
        listOf(
            "javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,<b>x</b>", "file:///etc/hosts",
            "content://com.circaso/x", "blob:https://x/1", "about:blank", "intent://x#Intent;end", "", "   ", "not a link",
            "https://", "https:///path", "1http://x",
        ).forEach { assertEquals(LinkTarget.Blocked, classifyLink(it), it) }
    }

    @Test
    fun hostIsTheRealOneAfterUserInfo() {
        assertEquals("evil.example", linkHost("https://basescan.org@evil.example/tx"))
        assertEquals("basescan.org", linkHost("https://user:pw@BaseScan.org:443/tx?x=1#y"))
        assertEquals("[::1]", linkHost("http://[::1]:8080/"))
        assertNull(linkHost("mailto:a@b.c"))
    }

    @Test
    fun displayHostDropsWww() {
        assertEquals("etherscan.io", displayHost("https://www.etherscan.io/tx/1"))
        assertEquals("sepolia.basescan.org", displayHost("https://sepolia.basescan.org/tx/1"))
        assertNull(displayHost("tel:1"))
    }

    @Test
    fun pagesLoadInPlace() {
        assertEquals(NavDecision.Load, decideNavigation("https://basescan.org/address/0x1", mainFrame = true, userGesture = true))
        assertEquals(NavDecision.Load, decideNavigation("https://basescan.org/", mainFrame = true, userGesture = false))
        assertEquals(NavDecision.Load, decideNavigation("https://ads.example/frame", mainFrame = false, userGesture = false))
    }

    @Test
    fun anotherAppOpensOnlyWhenThePersonTapped() {
        assertEquals(NavDecision.OpenInSystem, decideNavigation("mailto:a@b.c", mainFrame = true, userGesture = true))
        assertEquals(NavDecision.OpenInSystem, decideNavigation("tel:+254700000000", mainFrame = true, userGesture = true))
        assertEquals(NavDecision.Block, decideNavigation("tel:+254700000000", mainFrame = true, userGesture = false))
        assertEquals(NavDecision.Block, decideNavigation("whatsapp://send", mainFrame = false, userGesture = false))
        assertEquals(NavDecision.OpenInSystem, decideNavigation("https://apps.apple.com/app/id1", mainFrame = true, userGesture = true))
        // A page redirecting to a store on its own stays a web page in the view.
        assertEquals(NavDecision.Load, decideNavigation("https://apps.apple.com/app/id1", mainFrame = true, userGesture = false))
    }

    @Test
    fun framesMayUseInternalSchemesButThePageMayNot() {
        assertEquals(NavDecision.Load, decideNavigation("about:blank", mainFrame = false, userGesture = false))
        assertEquals(NavDecision.Load, decideNavigation("about:srcdoc", mainFrame = false, userGesture = false))
        assertEquals(NavDecision.Load, decideNavigation("data:text/html,x", mainFrame = false, userGesture = false))
        assertEquals(NavDecision.Load, decideNavigation("blob:https://x/1", mainFrame = false, userGesture = false))
        assertEquals(NavDecision.Block, decideNavigation("data:text/html,x", mainFrame = true, userGesture = true))
        assertEquals(NavDecision.Block, decideNavigation("about:srcdoc", mainFrame = true, userGesture = true))
        assertEquals(NavDecision.Block, decideNavigation("javascript:alert(1)", mainFrame = true, userGesture = true))
        assertEquals(NavDecision.Block, decideNavigation("file:///sdcard/x", mainFrame = true, userGesture = true))
    }

    @Test
    fun intentLinksFollowOnlyTheirWebFallback() {
        val withFallback = "intent://scan/#Intent;scheme=zxing;package=com.x;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fapp;end"
        assertEquals(NavDecision.LoadInstead("https://example.com/app"), decideNavigation(withFallback, mainFrame = true, userGesture = true))
        assertEquals(NavDecision.Block, decideNavigation(withFallback, mainFrame = true, userGesture = false))
        assertEquals(NavDecision.Block, decideNavigation("intent://scan/#Intent;scheme=zxing;package=com.x;end", mainFrame = true, userGesture = true))
        val jsFallback = "intent://x#Intent;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertEquals(NavDecision.Block, decideNavigation(jsFallback, mainFrame = true, userGesture = true))
    }

    @Test
    fun aFailedLoadIsOfflineOnlyWithoutANetwork() {
        assertEquals(WebLoadError.Offline, loadError(notConnected = true, osOffline = false))
        assertEquals(WebLoadError.Offline, loadError(notConnected = false, osOffline = true))
        assertEquals(WebLoadError.Failed, loadError(notConnected = false, osOffline = false))
    }

    @Test
    fun openingTheSameLinkTwiceLoadsItFresh() {
        InAppBrowser.open("https://a.example")
        val first = InAppBrowser.page.value
        InAppBrowser.open("https://a.example")
        val second = InAppBrowser.page.value
        assertEquals(first?.url, second?.url)
        kotlin.test.assertNotEquals(first?.id, second?.id)
        InAppBrowser.close()
        assertNull(InAppBrowser.page.value)
    }
}
