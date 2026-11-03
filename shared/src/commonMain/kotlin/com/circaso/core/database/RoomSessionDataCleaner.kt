package com.circaso.core.database

import com.circaso.core.realtime.RealtimeCursors
import com.circaso.core.session.SessionDataCleaner
import com.circaso.feature.messaging.data.local.clearAll

/**
 * Empties every table that holds a person's data: cached profile, contacts, blocks, security info, Asha history and the
 * unsent queue. Also forgets the realtime sequence numbers: they count per user, so another account (or the same one
 * after a server reset) starts again from 1 and the old numbers would make the app drop every new frame.
 */
class RoomSessionDataCleaner(private val db: CircasoDatabase, private val cursors: RealtimeCursors) : SessionDataCleaner {
    override suspend fun clearUserData() {
        cursors.clear()
        db.profileDao().clear()
        db.contactsDao().apply { clearContacts(); clearBlocked(); clearSynced() }
        db.accountDao().apply { clearOverview(); clearEvents() }
        db.ashaDao().clear()
        db.outboxDao().clearAll()
        db.signalDao().clearAll() // another person's Signal identity, sessions and prekeys never carry over
        db.chatsDao().clearAll()
        db.messagesDao().clearAll()
        db.walletDao().apply { clearSnapshot(); clearTransfers() }
        db.chamaDao().clearAll()
        db.reactionsDao().clear()
        db.momentsDao().apply { clearMoments(); clearPending() } // the moments sender keys go with the Signal store above
    }
}
