package com.circaso.core.sync

/**
 * Sends the outbox when the phone next has a connection, even if Circaso has been closed by then (Android
 * WorkManager, iOS background tasks). Asked for whenever something is queued and whenever a send pauses for the
 * network; the platform keeps one job and runs it only with a connection. What the server refused stays in Needs
 * attention: trying again can't fix it.
 */
interface BackgroundSync {
    fun schedule()
}

object NoBackgroundSync : BackgroundSync {
    override fun schedule() = Unit
}
