package com.circaso.feature.contacts.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "contact")
data class ContactEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val about: String?,
    val source: String,
    val addedAt: String,
    /** The verified badge (2026-11-24), as the server last said. */
    @androidx.room.ColumnInfo(defaultValue = "0") val verified: Boolean = false,
)

@Entity(tableName = "blocked")
data class BlockedEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val handle: String?,
    val avatarUrl: String?,
    val about: String?,
)

/** Phone numbers already sent to the server, so a later sync sends only what is new. Numbers only, never names. */
@Entity(tableName = "synced_phone")
data class SyncedPhoneEntity(@PrimaryKey val phone: String)

@Dao
interface ContactsDao {
    @Query("SELECT * FROM contact ORDER BY displayName COLLATE NOCASE")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Upsert
    suspend fun upsertContacts(items: List<ContactEntity>)

    @Query("DELETE FROM contact WHERE userId NOT IN (:keep)")
    suspend fun deleteContactsNotIn(keep: List<String>)

    @Query("DELETE FROM contact")
    suspend fun clearContacts()

    @Query("DELETE FROM contact WHERE userId = :userId")
    suspend fun deleteContact(userId: String)

    /** [userId]'s profile was read again: their badge as the server says now. */
    @Query("UPDATE contact SET verified = :verified WHERE userId = :userId")
    suspend fun updateVerified(userId: String, verified: Boolean)

    /**
     * Everyone this phone knows to be verified (the verified badge, 2026-11-24): a contact, someone in a chat, or me.
     * What [com.circaso.designsystem.components.LocalVerifiedPeople] is made from.
     */
    @Query(
        "SELECT userId FROM contact WHERE verified = 1 UNION SELECT userId FROM chat_member WHERE verified = 1 " +
            "UNION SELECT userId FROM profile WHERE verified = 1",
    )
    fun observeVerified(): Flow<List<String>>

    @Query("SELECT * FROM blocked ORDER BY displayName COLLATE NOCASE")
    fun observeBlocked(): Flow<List<BlockedEntity>>

    @Upsert
    suspend fun upsertBlocked(items: List<BlockedEntity>)

    @Query("DELETE FROM blocked WHERE userId NOT IN (:keep)")
    suspend fun deleteBlockedNotIn(keep: List<String>)

    @Query("DELETE FROM blocked")
    suspend fun clearBlocked()

    @Query("DELETE FROM blocked WHERE userId = :userId")
    suspend fun deleteBlocked(userId: String)

    @Query("SELECT phone FROM synced_phone")
    suspend fun syncedPhones(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSynced(phones: List<SyncedPhoneEntity>)

    @Query("DELETE FROM synced_phone")
    suspend fun clearSynced()
}
