package io.github.agopalareddy.umm.core.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

import kotlin.coroutines.CoroutineContext

fun buildUmmDatabase(file: File): UmmDatabase {
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<UmmDatabase>(file.absolutePath).configured()
}

fun inMemoryUmmDatabase(coroutineContext: CoroutineContext = Dispatchers.IO): UmmDatabase =
    Room.inMemoryDatabaseBuilder<UmmDatabase>().configured(coroutineContext)

private fun RoomDatabase.Builder<UmmDatabase>.configured(coroutineContext: CoroutineContext = Dispatchers.IO): UmmDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(coroutineContext)
        .addMigrations(MIGRATION_2_3)
        .addCallback(SeedCallback)
        .build()
