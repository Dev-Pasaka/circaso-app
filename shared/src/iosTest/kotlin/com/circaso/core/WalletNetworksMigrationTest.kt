package com.circaso.core

import androidx.room.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.circaso.core.database.CircasoDatabase
import com.circaso.core.database.buildDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * An install from before wallet networks (database version 23) opens on version 24: every copy of money gains its
 * `network`, and everything that was there is Testnet's (wallet_snapshot and wallet_list go from a single row to one
 * per network; wallet_cache, wallet_activity, chama_snapshot and group_money add it to their keys; transfer gains the
 * column). Nothing is lost and nothing shows under Mainnet. The version-23 tables are created from the exported schema
 * (`shared/schemas/.../23.json`), so Room sees a real version-23 file and runs the same migration a phone does.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class WalletNetworksMigrationTest {
    @Test
    fun anInstallFromBeforeNetworksKeepsItsMoneyAsTestnet() = runBlocking {
        val path = NSTemporaryDirectory() + "circaso-migration-" + NSUUID().UUIDString + ".db"
        val old = BundledSQLiteDriver().open(path)
        Version23.forEach { old.execSQL(it) }
        old.execSQL("INSERT INTO wallet_snapshot (id, json, fetchedAt) VALUES (1, '{\"status\":\"ACTIVE\"}', 11)")
        old.execSQL("INSERT INTO wallet_list (id, json, fetchedAt) VALUES (1, '{}', 12)")
        old.execSQL("INSERT INTO wallet_cache (walletId, json, fetchedAt) VALUES ('gw-1', '{}', 13)")
        old.execSQL("INSERT INTO wallet_activity (walletId, rowId, direction, state, blockchain, asset, amountMinor, amount, occurredAt) VALUES ('gw-1', 'r1', 'IN', 'CONFIRMED', 'BASE-SEPOLIA', 'USDC', '1', '0.000001', 14)")
        old.execSQL("INSERT INTO transfer (transferId, direction, state, blockchain, fromAddress, toAddress, asset, amountMinor, amount, createdAt, updatedAt) VALUES ('t-1', 'Out', 'CONFIRMED', 'BASE-SEPOLIA', '', '', 'USDC', '1', '0.000001', 1, 1)")
        old.execSQL("INSERT INTO chama_snapshot (chatId, json, fetchedAt) VALUES ('g1', '{}', 15)")
        old.execSQL("INSERT INTO group_money (chatId, json, fetchedAt) VALUES ('g1', '{}', 16)")
        old.execSQL("INSERT INTO chat (chatId, kind, name, metaVersion, version, membersCanAdd, left, readUpToOrderAt, updatedAt) VALUES ('c1', 'DIRECT', 'Mum', 0, 1, 0, 0, 0, 0)")
        old.execSQL("PRAGMA user_version = 23")
        old.close()

        val db = buildDatabase(Room.databaseBuilder<CircasoDatabase>(name = path))
        // Reading through the DAO opens the file, which runs the migration (and Room checks the result against version 24).
        val wallet = db.walletDao()
        assertEquals(11L, wallet.snapshot("TESTNET")?.fetchedAt, "the wallet that was there is Testnet's")
        assertNull(wallet.snapshot("MAINNET"), "and nothing shows under Mainnet")
        assertEquals(12L, wallet.list("TESTNET")?.fetchedAt)
        assertNull(wallet.list("MAINNET"))
        assertEquals(13L, wallet.observeCached("gw-1", "TESTNET").first()?.fetchedAt)
        assertNull(wallet.observeCached("gw-1", "MAINNET").first())
        assertEquals(listOf("r1"), wallet.observeActivity("gw-1", "TESTNET").first().map { it.rowId })
        assertEquals(emptyList(), wallet.observeActivity("gw-1", "MAINNET").first())
        assertEquals(listOf("t-1"), wallet.observeTransfers("TESTNET").first().map { it.transferId })
        assertEquals(emptyList(), wallet.observeTransfers("MAINNET").first())
        assertEquals("TESTNET", wallet.transfer("t-1")?.network)
        assertEquals(15L, db.chamaDao().observe("g1", "TESTNET").first()?.fetchedAt)
        assertNull(db.chamaDao().observe("g1", "MAINNET").first())
        assertEquals(16L, db.chamaDao().observeMoney("g1", "TESTNET").first()?.fetchedAt)
        assertNull(db.chamaDao().observeMoney("g1", "MAINNET").first())
        // The other network's copy sits beside it afterwards.
        wallet.saveSnapshot(com.circaso.feature.wallet.data.local.WalletSnapshotEntity("MAINNET", "{}", 20))
        assertEquals(11L, wallet.snapshot("TESTNET")?.fetchedAt)
        assertEquals(20L, wallet.snapshot("MAINNET")?.fetchedAt)
        db.close()

        val after = BundledSQLiteDriver().open(path)
        try {
            assertEquals(24L, after.long("PRAGMA user_version"))
            assertEquals(1L, after.long("SELECT COUNT(*) FROM chat"), "everything else is kept")
            assertEquals(0L, after.long("SELECT COUNT(*) FROM pragma_table_info('wallet_snapshot') WHERE name = 'id'"), "the single-row id is gone")
        } finally {
            after.close()
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }

    private fun SQLiteConnection.long(sql: String): Long = prepare(sql).use { it.step(); it.getLong(0) }

    private companion object {
        /** Every statement of the exported version-23 schema, in order (generated from 23.json). */
        val Version23 = listOf(
        "CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `payload` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `lastErrorCode` TEXT, `ownerId` TEXT NOT NULL DEFAULT '')",
        "CREATE INDEX IF NOT EXISTS `index_outbox_state` ON `outbox` (`state`)",
        "CREATE INDEX IF NOT EXISTS `index_outbox_type` ON `outbox` (`type`)",
        "CREATE TABLE IF NOT EXISTS `profile` (`userId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `about` TEXT, `handle` TEXT, `handleChangeableAt` TEXT, `avatarUrl` TEXT, `privacyAvatar` TEXT NOT NULL, `privacyAbout` TEXT NOT NULL, `privacyHandleLookup` INTEGER NOT NULL, `version` INTEGER NOT NULL, `pendingSync` INTEGER NOT NULL, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS `contact` (`userId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `handle` TEXT, `avatarUrl` TEXT, `about` TEXT, `source` TEXT NOT NULL, `addedAt` TEXT NOT NULL, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS `blocked` (`userId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `handle` TEXT, `avatarUrl` TEXT, `about` TEXT, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS `synced_phone` (`phone` TEXT NOT NULL, PRIMARY KEY(`phone`))",
        "CREATE TABLE IF NOT EXISTS `security_overview` (`id` INTEGER NOT NULL, `maskedPhone` TEXT, `providers` TEXT NOT NULL, `deviceModel` TEXT, `deviceSince` TEXT, `verification` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `security_event` (`eventId` TEXT NOT NULL, `type` TEXT NOT NULL, `deviceModel` TEXT, `detail` TEXT, `at` TEXT NOT NULL, PRIMARY KEY(`eventId`))",
        "CREATE TABLE IF NOT EXISTS `asha_message` (`messageId` TEXT NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `replyTo` TEXT, `askedFrom` TEXT, `readChatIds` TEXT, `readChatNames` TEXT, `readMessageCount` INTEGER, `at` TEXT NOT NULL, `conversationId` TEXT, PRIMARY KEY(`messageId`))",
        "CREATE TABLE IF NOT EXISTS `signal_identity` (`id` INTEGER NOT NULL, `keyPair` BLOB NOT NULL, `publicKey` BLOB NOT NULL, `registrationId` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `publishedAt` INTEGER, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `signal_signed_prekey` (`kind` TEXT NOT NULL, `keyId` INTEGER NOT NULL, `record` BLOB NOT NULL, `publicKey` BLOB NOT NULL, `signature` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, `retiredAt` INTEGER, PRIMARY KEY(`kind`, `keyId`))",
        "CREATE TABLE IF NOT EXISTS `signal_prekey` (`keyId` INTEGER NOT NULL, `record` BLOB NOT NULL, `publicKey` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`keyId`))",
        "CREATE TABLE IF NOT EXISTS `signal_session` (`userId` TEXT NOT NULL, `record` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS `signal_peer_identity` (`userId` TEXT NOT NULL, `identityKey` BLOB NOT NULL, `firstSeenAt` INTEGER NOT NULL, `changedAt` INTEGER, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS `signal_sender_key` (`chatId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `distributionId` TEXT NOT NULL, `record` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`, `senderId`, `distributionId`))",
        "CREATE TABLE IF NOT EXISTS `signal_counter` (`name` TEXT NOT NULL, `value` INTEGER NOT NULL, PRIMARY KEY(`name`))",
        "CREATE TABLE IF NOT EXISTS `chat` (`chatId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT, `sealedMeta` TEXT, `metaVersion` INTEGER NOT NULL, `version` INTEGER NOT NULL, `membersCanAdd` INTEGER NOT NULL, `lastMessageId` TEXT, `left` INTEGER NOT NULL, `readUpToOrderAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `viaSpaceId` TEXT DEFAULT NULL, `avatarMediaId` TEXT DEFAULT NULL, `description` TEXT DEFAULT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `chat_member` (`chatId` TEXT NOT NULL, `userId` TEXT NOT NULL, `role` TEXT NOT NULL, `displayName` TEXT NOT NULL, `handle` TEXT, `avatarUrl` TEXT, `joinedAt` TEXT NOT NULL, PRIMARY KEY(`chatId`, `userId`))",
        "CREATE TABLE IF NOT EXISTS `message` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `clientMessageId` TEXT NOT NULL, `messageId` TEXT, `chatId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `outgoing` INTEGER NOT NULL, `kind` TEXT NOT NULL, `text` TEXT, `replyTo` TEXT, `status` TEXT NOT NULL, `errorCode` TEXT, `cachedRequest` TEXT, `sentAt` TEXT, `orderAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `transferId` TEXT, `amountMinor` TEXT, `asset` TEXT, `blockchain` TEXT, `momentQuote` TEXT DEFAULT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_message_chatId_orderAt` ON `message` (`chatId`, `orderAt`)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_clientMessageId` ON `message` (`clientMessageId`)",
        "CREATE INDEX IF NOT EXISTS `index_message_messageId` ON `message` (`messageId`)",
        "CREATE TABLE IF NOT EXISTS `message_receipt` (`messageId` TEXT NOT NULL, `userId` TEXT NOT NULL, `status` TEXT NOT NULL, `at` TEXT NOT NULL, PRIMARY KEY(`messageId`, `userId`))",
        "CREATE TABLE IF NOT EXISTS `pending_inbox` (`messageId` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderId` TEXT NOT NULL, `kind` TEXT NOT NULL, `ciphertext` TEXT NOT NULL, `sentAt` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`))",
        "CREATE TABLE IF NOT EXISTS `group_meta_key` (`chatId` TEXT NOT NULL, `metaVersion` INTEGER NOT NULL, `key` BLOB NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`, `metaVersion`))",
        "CREATE TABLE IF NOT EXISTS `sender_key_delivery` (`chatId` TEXT NOT NULL, `userId` TEXT NOT NULL, `distributionId` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`chatId`, `userId`, `distributionId`))",
        "CREATE TABLE IF NOT EXISTS `wallet_snapshot` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `transfer` (`transferId` TEXT NOT NULL, `direction` TEXT NOT NULL, `state` TEXT NOT NULL, `blockchain` TEXT NOT NULL, `fromAddress` TEXT NOT NULL, `toUserId` TEXT, `toAddress` TEXT NOT NULL, `asset` TEXT NOT NULL, `amountMinor` TEXT NOT NULL, `amount` TEXT NOT NULL, `feeMinor` TEXT, `chatId` TEXT, `txHash` TEXT, `failureReason` TEXT, `counterpartName` TEXT, `note` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `counterpartHandle` TEXT DEFAULT NULL, `counterpartAvatar` TEXT DEFAULT NULL, `walletId` TEXT DEFAULT NULL, `fromGroupId` TEXT DEFAULT NULL, `toGroupId` TEXT DEFAULT NULL, `payoutRequestId` TEXT DEFAULT NULL, `counterpartUserId` TEXT DEFAULT NULL, PRIMARY KEY(`transferId`))",
        "CREATE TABLE IF NOT EXISTS `chama_snapshot` (`chatId` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `wallet_cache` (`walletId` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`walletId`))",
        "CREATE TABLE IF NOT EXISTS `wallet_list` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `wallet_activity` (`walletId` TEXT NOT NULL, `rowId` TEXT NOT NULL, `transferId` TEXT, `direction` TEXT NOT NULL, `state` TEXT NOT NULL, `blockchain` TEXT NOT NULL, `asset` TEXT NOT NULL, `amountMinor` TEXT NOT NULL, `amount` TEXT NOT NULL, `counterpartName` TEXT, `counterpartHandle` TEXT, `counterpartAvatar` TEXT, `counterpartUserId` TEXT, `counterpartAddress` TEXT, `counterpartGroupId` TEXT, `chatId` TEXT, `txHash` TEXT, `occurredAt` INTEGER NOT NULL, PRIMARY KEY(`walletId`, `rowId`))",
        "CREATE TABLE IF NOT EXISTS `group_money` (`chatId` TEXT NOT NULL, `json` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`chatId`))",
        "CREATE TABLE IF NOT EXISTS `moment` (`momentId` TEXT NOT NULL, `posterId` TEXT NOT NULL, `body` TEXT, `ciphertext` TEXT, `keyCopy` TEXT, `postedAt` INTEGER NOT NULL, `expiresAt` INTEGER NOT NULL, `mine` INTEGER NOT NULL, `viewed` INTEGER NOT NULL, `reacted` INTEGER NOT NULL, `viewCount` INTEGER NOT NULL, `reaction` TEXT DEFAULT NULL, `reactionCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`momentId`))",
        "CREATE TABLE IF NOT EXISTS `moment_pending` (`localId` TEXT NOT NULL, `kind` TEXT NOT NULL, `draft` TEXT NOT NULL, `mediaId` TEXT, `mediaKey` TEXT, `mediaDigest` TEXT, `state` TEXT NOT NULL, `error` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`localId`))",
        "CREATE TABLE IF NOT EXISTS `moment_muted` (`userId` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS `moment_privacy` (`id` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `message_reaction` (`chatId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `userId` TEXT NOT NULL, `emoji` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `userId`))",
        "CREATE INDEX IF NOT EXISTS `index_message_reaction_chatId` ON `message_reaction` (`chatId`)",
        "CREATE TABLE IF NOT EXISTS `message_retry` (`messageId` TEXT NOT NULL, `peerId` TEXT NOT NULL, `role` TEXT NOT NULL, `chatId` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `peerId`, `role`))",
        "CREATE TABLE IF NOT EXISTS `saved_name` (`userId` TEXT NOT NULL, `name` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`userId`))",
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'd883101dd04ecce1e09a29d2ca6c2807')",
        )
    }
}
