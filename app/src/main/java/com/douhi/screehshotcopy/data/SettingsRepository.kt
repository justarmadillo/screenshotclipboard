package com.douhi.screehshotcopy.data

import android.os.Environment
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** Probing the filesystem is only worth doing once per process. */
    private val detectedFolder: String by lazy { detectDefaultFolder() }

    val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            // Never let a corrupt/unreadable store kill the collector: fall back to defaults.
            Log.w(TAG, "Settings read failed, using defaults", e)
            emit(emptyPreferences())
        }
        .map { prefs ->
            AppSettings(
                enabled = prefs[KEY_ENABLED] ?: false,
                folderPath = prefs[KEY_FOLDER]?.let { AppSettings.sanitizeFolder(it) } ?: detectedFolder,
                keepTimeoutMs = AppSettings.sanitizeTimeout(
                    prefs[KEY_TIMEOUT] ?: AppSettings.DEFAULT_TIMEOUT_MS
                ),
            )
        }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    suspend fun setEnabled(value: Boolean) = editSafely { it[KEY_ENABLED] = value }

    suspend fun setFolder(path: String) = editSafely { it[KEY_FOLDER] = AppSettings.sanitizeFolder(path) }

    suspend fun setKeepTimeoutMs(ms: Long) = editSafely { it[KEY_TIMEOUT] = AppSettings.sanitizeTimeout(ms) }

    private suspend fun editSafely(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            dataStore.edit(block)
        } catch (e: Exception) {
            Log.w(TAG, "Settings write failed", e)
        }
    }

    private fun detectDefaultFolder(): String = try {
        val root = Environment.getExternalStorageDirectory()
        FOLDER_CANDIDATES.firstOrNull { File(root, it).isDirectory } ?: AppSettings.DEFAULT_FOLDER
    } catch (e: Exception) {
        Log.w(TAG, "Folder detection failed", e)
        AppSettings.DEFAULT_FOLDER
    }

    private companion object {
        const val TAG = "SettingsRepository"
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_FOLDER = stringPreferencesKey("folder")

        /** Reused from v1.x, where it stored the "delete after" delay. Same meaning, new name. */
        val KEY_TIMEOUT = longPreferencesKey("delay")

        val FOLDER_CANDIDATES = listOf(
            "Pictures/Screenshots",
            "DCIM/Screenshots",
            "Pictures/Screenshot",
            "DCIM/Screenshot",
            "Pictures/ScreenCapture",
        )
    }
}
