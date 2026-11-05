package com.circaso.testing

import com.circaso.feature.messaging.data.local.MessageKind
import com.circaso.feature.messaging.data.local.MessageRetryEntity
import com.circaso.feature.messaging.data.local.MessageStatus
import com.circaso.feature.messaging.data.local.RecoveryDao
import com.circaso.feature.messaging.data.local.RetryRole

/** [RecoveryDao] over the rows of a [FakeMessagesDao]. */
class FakeRecoveryDao(private val messages: FakeMessagesDao) : RecoveryDao {
    val retries = mutableMapOf<Triple<String, String, String>, MessageRetryEntity>()

    override suspend fun unreadableToAsk(since: Long, limit: Int) =
        messages.rows.value.filter { m ->
            m.status == MessageStatus.Undecryptable && !m.outgoing && m.messageId != null && m.createdAt >= since &&
                retries.values.none { it.messageId == m.messageId && it.role == RetryRole.Asked }
        }.sortedByDescending { it.orderAt }.take(limit)

    override suspend fun retry(messageId: String, peerId: String, role: String) = retries[Triple(messageId, peerId, role)]

    override suspend fun insertRetry(row: MessageRetryEntity): Long {
        val key = Triple(row.messageId, row.peerId, row.role)
        if (key in retries) return -1
        retries[key] = row
        return retries.size.toLong()
    }

    override suspend fun paymentCard(chatId: String, transferId: String) =
        messages.rows.value.filter { it.chatId == chatId && it.kind == MessageKind.Payment && it.transferId == transferId }.minByOrNull { it.localId }

    override suspend fun clear() = retries.clear()
}
