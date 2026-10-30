package com.circaso.core.session

/** Wipes the data kept on this phone for the person who was signed in: cached profile, contacts, history and the unsent queue. */
fun interface SessionDataCleaner {
    suspend fun clearUserData()
}
