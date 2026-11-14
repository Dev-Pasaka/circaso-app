package com.circaso.core.database

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.DeleteTable
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.circaso.core.sync.OutboxDao
import com.circaso.core.sync.OutboxEntity
import com.circaso.feature.account.data.local.AccountDao
import com.circaso.feature.account.data.local.SecurityEventEntity
import com.circaso.feature.account.data.local.SecurityOverviewEntity
import com.circaso.feature.assistant.data.local.AshaDao
import com.circaso.feature.assistant.data.local.AshaMessageEntity
import com.circaso.feature.contacts.data.local.BlockedEntity
import com.circaso.feature.contacts.data.local.ContactEntity
import com.circaso.feature.contacts.data.local.ContactsDao
import com.circaso.feature.contacts.data.local.SyncedPhoneEntity
import com.circaso.feature.messaging.data.local.ChatEntity
import com.circaso.feature.messaging.data.local.ChatMemberEntity
import com.circaso.feature.messaging.data.local.ChatsDao
import com.circaso.feature.messaging.data.local.GroupMetaKeyEntity
import com.circaso.feature.messaging.data.local.MessageEntity
import com.circaso.feature.messaging.data.local.MessageReceiptEntity
import com.circaso.feature.messaging.data.local.MessagesDao
import com.circaso.feature.messaging.data.local.PeerIdentityEntity
import com.circaso.feature.messaging.data.local.PendingInboxEntity
import com.circaso.feature.messaging.data.local.SenderKeyDeliveryEntity
import com.circaso.feature.chama.data.local.ChamaDao
import com.circaso.feature.chama.data.local.ChamaSnapshotEntity
import com.circaso.feature.wallet.data.local.TransferEntity
import com.circaso.feature.wallet.data.local.WalletDao
import com.circaso.feature.wallet.data.local.WalletSnapshotEntity
import com.circaso.feature.messaging.data.local.PreKeyEntity
import com.circaso.feature.messaging.data.local.SenderKeyEntity
import com.circaso.feature.messaging.data.local.SessionEntity
import com.circaso.feature.messaging.data.local.SignalCounterEntity
import com.circaso.feature.messaging.data.local.SignalDao
import com.circaso.feature.messaging.data.local.SignalIdentityEntity
import com.circaso.feature.messaging.data.local.SignedPreKeyEntity
import com.circaso.feature.profile.data.local.ProfileDao
import com.circaso.feature.profile.data.local.ProfileEntity
import kotlinx.coroutines.Dispatchers

/**
 * The one on-device database. Features add their entities here (contacts, profile cache, Asha history,
 * security log) with a version bump and a migration; the schema is exported to `shared/schemas`.
 */
@Database(
    entities = [OutboxEntity::class, ProfileEntity::class, ContactEntity::class, BlockedEntity::class, SyncedPhoneEntity::class,
        SecurityOverviewEntity::class, SecurityEventEntity::class, AshaMessageEntity::class,
        SignalIdentityEntity::class, SignedPreKeyEntity::class, PreKeyEntity::class, SessionEntity::class,
        PeerIdentityEntity::class, SenderKeyEntity::class, SignalCounterEntity::class,
        ChatEntity::class, ChatMemberEntity::class, MessageEntity::class, MessageReceiptEntity::class,
        PendingInboxEntity::class, GroupMetaKeyEntity::class, SenderKeyDeliveryEntity::class,
        WalletSnapshotEntity::class, TransferEntity::class, ChamaSnapshotEntity::class,
        com.circaso.feature.wallet.data.local.WalletCacheEntity::class, com.circaso.feature.wallet.data.local.WalletListEntity::class,
        com.circaso.feature.wallet.data.local.WalletActivityEntity::class, com.circaso.feature.chama.data.local.GroupMoneyEntity::class,
        com.circaso.feature.status.data.local.MomentEntity::class, com.circaso.feature.status.data.local.PendingMomentEntity::class,
        com.circaso.feature.status.data.local.MutedPosterEntity::class, com.circaso.feature.status.data.local.MomentPrivacyEntity::class,
        com.circaso.feature.messaging.data.local.MessageReactionEntity::class, com.circaso.feature.messaging.data.local.MessageRetryEntity::class,
        com.circaso.feature.names.data.SavedNameEntity::class,
    ],
    version = 23,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13), AutoMigration(from = 13, to = 14), AutoMigration(from = 14, to = 15), AutoMigration(from = 15, to = 16),
        // 17: group wallets (Spaces phase 2): wallet_cache, wallet_list, wallet_activity, group_money, transfer.walletId.
        AutoMigration(from = 16, to = 17),
        // 18: Spaces core (phase 3): space, space_mine, space_post, space_invite, space_receipt, space_meta, chat.viaSpaceId.
        AutoMigration(from = 17, to = 18),
        // 19: retry receipts (message_retry): which unreadable messages were asked for again, and which I sent again.
        AutoMigration(from = 18, to = 19),
        // 20: Spaces stopped (2026-11-05, tag archive/spaces-v1): its six tables go, and its queued posts and reactions
        // with them. Everything else is kept as it is; chat.viaSpaceId stays (such a chat is an ordinary chat now).
        AutoMigration(from = 19, to = 20, spec = DropSpacesTables::class),
        // 21: chat.avatarMediaId, the group photo the server keeps (backend PR #47). Null until the next refresh.
        AutoMigration(from = 20, to = 21),
        // 22: saved names (saved_name: names I give people, null = cleared) and transfer.counterpartUserId, so a
        // payment row shows the name I saved for the other person.
        AutoMigration(from = 21, to = 22),
        // 23: chat.description, the group's description opened from its sealed metadata (group join links, 2026-11-14).
        AutoMigration(from = 22, to = 23),
    ],
)
@ConstructedBy(CircasoDatabaseConstructor::class)
abstract class CircasoDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun profileDao(): ProfileDao
    abstract fun contactsDao(): ContactsDao
    abstract fun accountDao(): AccountDao
    abstract fun ashaDao(): AshaDao
    abstract fun signalDao(): SignalDao
    abstract fun chatsDao(): ChatsDao
    abstract fun messagesDao(): MessagesDao
    abstract fun walletDao(): WalletDao
    abstract fun chamaDao(): ChamaDao
    abstract fun momentsDao(): com.circaso.feature.status.data.local.MomentsDao
    abstract fun reactionsDao(): com.circaso.feature.messaging.data.local.ReactionsDao
    abstract fun recoveryDao(): com.circaso.feature.messaging.data.local.RecoveryDao
    abstract fun savedNamesDao(): com.circaso.feature.names.data.SavedNamesDao
}

/** Version 19 → 20: Spaces was removed from the app; its tables and its outbox rows are dropped. */
@DeleteTable(tableName = "space")
@DeleteTable(tableName = "space_mine")
@DeleteTable(tableName = "space_post")
@DeleteTable(tableName = "space_invite")
@DeleteTable(tableName = "space_receipt")
@DeleteTable(tableName = "space_meta")
class DropSpacesTables : AutoMigrationSpec {
    override fun onPostMigrate(connection: SQLiteConnection) {
        // A queued space post or reaction has no sender any more: drop it rather than show it as failed forever.
        connection.execSQL("DELETE FROM outbox WHERE type IN ('space.post', 'space.reaction')")
    }
}

// Room generates the actual for each platform.
@Suppress("KotlinNoActualForExpect")
expect object CircasoDatabaseConstructor : RoomDatabaseConstructor<CircasoDatabase> {
    override fun initialize(): CircasoDatabase
}

const val DatabaseName = "circaso.db"

/** Same SQLite on both platforms (the bundled driver), so behaviour does not depend on the OS version. */
fun buildDatabase(builder: RoomDatabase.Builder<CircasoDatabase>): CircasoDatabase =
    builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.Default).build()
