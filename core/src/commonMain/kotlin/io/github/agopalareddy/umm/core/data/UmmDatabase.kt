package io.github.agopalareddy.umm.core.data

import androidx.room.AutoMigration
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.SQLiteConnection
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.stats.StatsDao
import io.github.agopalareddy.umm.core.stats.StatsEntry

@Database(
    entities = [CategoryEntity::class, AppAssignmentEntity::class, HistoryEntity::class, StatsEntry::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(UmmDatabaseConstructor::class)
abstract class UmmDatabase : RoomDatabase() {
    internal abstract fun categoryDao(): CategoryDao
    internal abstract fun historyDao(): HistoryDao
    internal abstract fun statsDao(): StatsDao
}

/** Room's KSP processor generates the `actual` for each target. */
@Suppress("KotlinNoActualForExpect")
expect object UmmDatabaseConstructor : RoomDatabaseConstructor<UmmDatabase> {
    override fun initialize(): UmmDatabase
}

/** Fills a new database with the first-run categories and app assignments. */
internal object SeedCallback : RoomDatabase.Callback() {
    override fun onCreate(connection: SQLiteConnection) {
        Seeds.levels.forEach { (category, level) ->
            connection.prepare("INSERT INTO categories (category, level, script) VALUES (?, ?, ?)").use {
                it.bindText(1, category.name)
                if (level == null) it.bindNull(2) else it.bindText(2, level.name)
                it.bindText(3, ScriptPreference.LATIN.name)
                it.step()
            }
        }
        Seeds.apps.forEach { (pkg, category) ->
            connection.prepare("INSERT INTO app_assignments (packageName, category) VALUES (?, ?)").use {
                it.bindText(1, pkg)
                it.bindText(2, category.name)
                it.step()
            }
        }
    }
}
