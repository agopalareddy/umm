package io.github.agopalareddy.umm.core.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

fun buildUmmDatabase(file: File): UmmDatabase {
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<UmmDatabase>(file.absolutePath).configured()
}

fun inMemoryUmmDatabase(): UmmDatabase = Room.inMemoryDatabaseBuilder<UmmDatabase>().configured()

private fun RoomDatabase.Builder<UmmDatabase>.configured(): UmmDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(SeedCallback)
        .build()
