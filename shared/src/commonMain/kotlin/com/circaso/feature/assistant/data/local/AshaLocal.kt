package com.circaso.feature.assistant.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The chat with Asha, kept so the history reads offline. The server holds the originals. */
@Entity(tableName = "asha_message")
data class AshaMessageEntity(
    @PrimaryKey val messageId: String,
    val role: String,
    val text: String,
    val replyTo: String?,
    val askedFrom: String?,
    /** Chat ids Asha read, joined by a unit separator (U+001F); null when she read none. */
    val readChatIds: String?,
    val readChatNames: String?,
    val readMessageCount: Int?,
    val at: String,
    /** The conversation it belongs to (C1–C5); null for rows saved before conversations existed. */
    val conversationId: String? = null,
)

@Dao
interface AshaDao {
    @Query("SELECT * FROM asha_message ORDER BY at ASC")
    fun observe(): Flow<List<AshaMessageEntity>>

    @Upsert
    suspend fun upsert(rows: List<AshaMessageEntity>)

    @Query("DELETE FROM asha_message WHERE messageId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM asha_message")
    suspend fun clear()

    @Query("DELETE FROM asha_message WHERE conversationId = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}
