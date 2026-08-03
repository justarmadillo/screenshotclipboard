package com.douhi.screehshotcopy.data

import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<AppSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val folderPath = prefs[KEY_FOLDER]
            AppSettings(
                enabled = prefs[KEY_ENABLED] ?: false,
                folderPath = folderPath ?: detectDefaultFolder(),
                deleteBehavior = if (prefs[KEY_DELETE] == true) DeleteBehavior.DELETE else DeleteBehavior.KEEP,
                deleteDelayMs = prefs[KEY_DELAY] ?: AppSettings.DEFAULT_DELAY_MS,
            )
        }

    suspend fun setEnabled(value: Boolean) {
        dataStore.edit { it[KEY_ENABLED] = value }
    }

    suspend fun setFolder(path: String) {
        dataStore.edit { it[KEY_FOLDER] = path }
    }

    suspend fun setDeleteBehavior(behavior: DeleteBehavior) {
        dataStore.edit { it[KEY_DELETE] = behavior == DeleteBehavior.DELETE }
    }

    suspend fun setDeleteDelayMs(ms: Long) {
        dataStore.edit { it[KEY_DELAY] = ms }
    }

    private fun detectDefaultFolder(): String {
        val candidates = listOf(
            "Pictures/Screenshots",
            "DCIM/Screenshots",
            "Pictures/Screenshot",
            "DCIM/Screenshot",
        )
        val root = Environment.getExternalStorageDirectory()
        return candidates.firstOrNull { candidate ->
            val dir = File(root, candidate)
            dir.isDirectory || dir.exists()
        } ?: AppSettings.DEFAULT_FOLDER
    }

    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_FOLDER = stringPreferencesKey("folder")
        val KEY_DELETE = booleanPreferencesKey("delete")
        val KEY_DELAY = longPreferencesKey("delay")
    }
}