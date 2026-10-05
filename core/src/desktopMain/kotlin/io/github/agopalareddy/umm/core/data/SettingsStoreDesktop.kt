package io.github.agopalareddy.umm.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import okio.Path.Companion.toOkioPath

/** The settings DataStore at [file], e.g. `~/.config/umm/settings.preferences_pb`. */
fun buildSettingsStore(file: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.createWithPath(produceFile = { file.absoluteFile.toOkioPath() })
