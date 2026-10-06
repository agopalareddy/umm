package io.github.agopalareddy.umm.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver

/** Opens the app's `umm.db`, at the same path the pre-KMP builds used. */
fun buildUmmDatabase(context: Context): UmmDatabase =
    Room.databaseBuilder<UmmDatabase>(context, context.getDatabasePath("umm.db").absolutePath)
        .setDriver(AndroidSQLiteDriver())
        .addCallback(SeedCallback)
        .build()
