package com.circaso.core.platform

/** A place on the map: what a shared location carries (WhatsApp-style "Send your current location"). */
data class GeoPoint(val latitude: Double, val longitude: Double, val accuracyMeters: Double? = null)

/**
 * The phone's current location, asked only when the person taps "Location" in a chat. The platform's own permission
 * prompt (when in use) appears then; null means it was refused or no fix came in time.
 */
interface LocationProvider {
    val available: Boolean
    suspend fun current(): GeoPoint?
}

object NoLocation : LocationProvider {
    override val available = false
    override suspend fun current(): GeoPoint? = null
}

/** Opens the phone's maps app at a shared location. */
expect fun openMap(latitude: Double, longitude: Double, label: String?)

/**
 * Turns the coins sound for money received on or off. iOS plays a push's sound from the app's `Library/Sounds`, so
 * the file is put there or removed (the default sound plays without it); Android picks the channel when it shows the
 * notification, so there is nothing to do here.
 */
expect fun setMoneySound(on: Boolean)

/** Plays the coins sound once, now (turning Money sound on lets the person hear what they chose). */
expect fun playMoneySound()

/** Closes the app (Android's Back from the home tabs); false where an app may not close itself (iOS). */
expect fun exitApp(): Boolean

/** Whether this platform lets the app close itself (Android yes, iOS no). */
expect val canExitApp: Boolean

/**
 * Opens a link the way Circaso opens links (Pasaka, 2026-11-06): a web page (a block explorer for a payment, a link in
 * Asha's answer) in the in-app browser, so the person stays in the app; mail, phone, app store and other apps' links
 * in the system; anything that could run code or read files nowhere. See `core/web/Links.kt`.
 */
fun openUrl(url: String) {
    when (com.circaso.core.web.classifyLink(url)) {
        com.circaso.core.web.LinkTarget.InApp -> com.circaso.core.web.InAppBrowser.open(url)
        com.circaso.core.web.LinkTarget.System -> openInSystem(url.trim())
        com.circaso.core.web.LinkTarget.Blocked -> Unit
    }
}

/**
 * Hands a link to the system: the phone's browser for a web page ("Open in Browser"), the mail, phone or store app for
 * theirs. Does nothing if nothing can open it. App code calls [openUrl]; this is for the in-app browser and links
 * that must leave the app.
 */
expect fun openInSystem(url: String)

/** Opens this app's page in the phone's Settings, where a refused permission (camera, microphone) can be turned on. */
expect fun openAppSettings()

/**
 * Decrypted chat media kept on this phone (photos, voice notes), by attachment id, in the app's private storage so a
 * file is downloaded and decrypted once. Cleared with the rest of the chats on sign-out.
 */
interface MediaFiles {
    suspend fun read(name: String): ByteArray?
    suspend fun write(name: String, bytes: ByteArray)
    suspend fun clear()

    /** Where [name] is on disk, for a player that reads a file; null when it isn't there. */
    suspend fun path(name: String): String? = null

    /** Removes one file (a moment's media once its 24 hours are over). */
    suspend fun delete(name: String) {}
}
