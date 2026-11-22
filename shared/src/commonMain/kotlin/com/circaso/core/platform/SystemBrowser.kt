package com.circaso.core.platform

/**
 * Opens a page in the **system's** in-app browser (a Chrome Custom Tab on Android, `SFSafariViewController` on iOS)
 * and returns once the person is back in Circaso. For the few pages Circaso's own in-app browser must not show: ones
 * that use the camera or keep a sign-in the phone's browser holds (Didit's hosted identity check, the fallback when
 * its SDK can't run). Circaso's web view has no camera access by design; the system's browser asks for it itself.
 *
 * False when nothing on the phone could open it. The address is never logged.
 */
expect suspend fun openInSystemBrowserAndWait(url: String): Boolean
