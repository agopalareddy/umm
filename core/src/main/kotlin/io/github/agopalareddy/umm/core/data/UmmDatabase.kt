package io.github.agopalareddy.umm.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference

@Database(
    entities = [CategoryEntity::class, AppAssignmentEntity::class, HistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class UmmDatabase : RoomDatabase() {
    internal abstract fun categoryDao(): CategoryDao
    internal abstract fun historyDao(): HistoryDao

    companion object {
        fun build(context: Context): UmmDatabase =
            Room.databaseBuilder(context, UmmDatabase::class.java, "umm.db").addCallback(SeedCallback).build()

        fun inMemory(context: Context): UmmDatabase =
            Room.inMemoryDatabaseBuilder(context, UmmDatabase::class.java).addCallback(SeedCallback).build()
    }

    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            Seeds.levels.forEach { (category, level) ->
                db.execSQL(
                    "INSERT INTO categories (category, level, script) VALUES (?, ?, ?)",
                    arrayOf(category.name, level?.name, ScriptPreference.LATIN.name),
                )
            }
            Seeds.apps.forEach { (pkg, category) ->
                db.execSQL(
                    "INSERT INTO app_assignments (packageName, category) VALUES (?, ?)",
                    arrayOf(pkg, category.name),
                )
            }
        }
    }
}
