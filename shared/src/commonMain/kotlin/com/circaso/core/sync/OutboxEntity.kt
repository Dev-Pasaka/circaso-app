package com.circaso.core.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle of a queued write. Stored as text so the database stays readable and stable across versions. */
object OutboxState {
    const val Pending = "pending"
    const val Sending = "sending"
    const val Failed = "failed"
    const val Done = "done"
}

/** Well-known queue types. */
object OutboxType {
    const val ChatMessage = "chat.message"
}

/**
 * One queued write. [idempotencyKey] is minted when the action is queued and reused on every retry,
 * so the server never applies it twice. Rows are processed in [id] order.
 */
@Entity(tableName = "outbox", indices = [Index("state"), Index("type")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val payload: String,
    val idempotencyKey: String,
    val createdAtMillis: Long,
    val state: String = OutboxState.Pending,
    val attempts: Int = 0,
    val lastErrorCode: String? = null,
    /** The user id this write belongs to. A write is only ever sent for its own owner. Empty only for rows from before owners existed. */
    @ColumnInfo(defaultValue = "") val ownerId: String = "",
)
