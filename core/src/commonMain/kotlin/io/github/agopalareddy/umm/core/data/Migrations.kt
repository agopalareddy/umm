package io.github.agopalareddy.umm.core.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.github.agopalareddy.umm.core.sync.SyncMeta

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        val deviceId = SyncMeta.generateDeviceId()

        // 1. Create sync_meta and insert device ID
        connection.execSQL("CREATE TABLE IF NOT EXISTS `sync_meta` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        connection.prepare("INSERT OR REPLACE INTO `sync_meta` (`key`, `value`) VALUES (?, ?)").use {
            it.bindText(1, SyncMeta.KEY_DEVICE_ID)
            it.bindText(2, deviceId)
            it.step()
        }

        // 2. Create sync_ops, sync_peers, sync_settings_hlc, sync_clears
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_ops` (" +
                "`originDevice` TEXT NOT NULL, " +
                "`seq` INTEGER NOT NULL, " +
                "`hlc` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`payload` TEXT NOT NULL, " +
                "PRIMARY KEY(`originDevice`, `seq`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_peers` (" +
                "`deviceId` TEXT NOT NULL, " +
                "`marks` TEXT NOT NULL, " +
                "`lastSeen` INTEGER NOT NULL, " +
                "`transport` TEXT, " +
                "PRIMARY KEY(`deviceId`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_settings_hlc` (" +
                "`key` TEXT NOT NULL, " +
                "`hlc` INTEGER NOT NULL, " +
                "`origin` TEXT NOT NULL, " +
                "PRIMARY KEY(`key`))",
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_clears` (" +
                "`data` TEXT NOT NULL, " +
                "`origin` TEXT NOT NULL, " +
                "`before` INTEGER NOT NULL, " +
                "PRIMARY KEY(`data`, `origin`))",
        )

        // 3. Update history: add columns with defaults, stamp existing rows, and create unique index
        connection.execSQL("ALTER TABLE `history` ADD COLUMN `originDevice` TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE `history` ADD COLUMN `originId` INTEGER NOT NULL DEFAULT 0")
        connection.execSQL("ALTER TABLE `history` ADD COLUMN `appLabel` TEXT")

        connection.prepare("UPDATE `history` SET `originDevice` = ?, `originId` = `id`").use {
            it.bindText(1, deviceId)
            it.step()
        }

        connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_history_originDevice_originId` ON `history` (`originDevice`, `originId`)")

        // 4. Rebuild dictation_stats with composite primary key
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `_new_dictation_stats` (" +
                "`originDevice` TEXT NOT NULL, " +
                "`historyId` INTEGER NOT NULL, " +
                "`appLabel` TEXT, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`packageName` TEXT NOT NULL, " +
                "`level` TEXT NOT NULL, " +
                "`rawWords` INTEGER NOT NULL, " +
                "`cleanWords` INTEGER NOT NULL, " +
                "`fillerWords` INTEGER NOT NULL, " +
                "`audioMs` INTEGER NOT NULL, " +
                "`latencyMs` INTEGER, " +
                "`costUsd` REAL, " +
                "`sttModel` TEXT, " +
                "`cleanupModel` TEXT, " +
                "`succeeded` INTEGER NOT NULL, " +
                "PRIMARY KEY(`originDevice`, `historyId`))",
        )
        connection.prepare(
            "INSERT INTO `_new_dictation_stats` (" +
                "`originDevice`, `historyId`, `appLabel`, `createdAt`, `packageName`, " +
                "`level`, `rawWords`, `cleanWords`, `fillerWords`, `audioMs`, " +
                "`latencyMs`, `costUsd`, `sttModel`, `cleanupModel`, `succeeded`" +
                ") SELECT ?, `historyId`, NULL, `createdAt`, `packageName`, " +
                "`level`, `rawWords`, `cleanWords`, `fillerWords`, `audioMs`, " +
                "`latencyMs`, `costUsd`, `sttModel`, `cleanupModel`, `succeeded` " +
                "FROM `dictation_stats`",
        ).use {
            it.bindText(1, deviceId)
            it.step()
        }
        connection.execSQL("DROP TABLE `dictation_stats`")
        connection.execSQL("ALTER TABLE `_new_dictation_stats` RENAME TO `dictation_stats`")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_dictation_stats_originDevice` ON `dictation_stats` (`originDevice`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_dictation_stats_createdAt` ON `dictation_stats` (`createdAt`)")

        // 5. Insert missing desktop seeds
        Seeds.apps.forEach { (pkg, category) ->
            connection.prepare("INSERT OR IGNORE INTO `app_assignments` (`packageName`, `category`) VALUES (?, ?)").use {
                it.bindText(1, pkg)
                it.bindText(2, category.name)
                it.step()
            }
        }
    }
}
