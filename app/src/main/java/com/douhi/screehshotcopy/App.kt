package com.douhi.screehshotcopy

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.douhi.screehshotcopy.clipboard.ClipboardHelper
import com.douhi.screehshotcopy.data.PendingRepository
import com.douhi.screehshotcopy.data.SettingsRepository
import com.douhi.screehshotcopy.deletion.DeletionHelper
import com.douhi.screehshotcopy.deletion.ScreenshotJanitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/**
 * Manual dependency graph. Every component runs in the same process, so these singletons are what
 * make the service, the notification receiver and the UI agree on a single source of truth —
 * DataStore in particular must not be instantiated twice for the same file.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Outlives any one component. Broadcast receivers get a few seconds of guaranteed runtime via
     * goAsync(); a scope tied to a receiver instance would be the wrong lifetime for work that has
     * to finish.
     */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e("AppContainer", "Unhandled background error", e)
        }
    )

    private val settingsStore: DataStore<Preferences> = createStore("settings")
    private val pendingStore: DataStore<Preferences> = createStore("pending")

    val settingsRepository = SettingsRepository(settingsStore)
    val pendingRepository = PendingRepository(pendingStore)
    val clipboardHelper = ClipboardHelper(appContext)
    val deletionHelper = DeletionHelper(appContext)
    val janitor = ScreenshotJanitor(appContext, pendingRepository, deletionHelper)

    /**
     * A corrupted preferences file would otherwise throw on every read for the life of the install
     * — with no update ever shipping to fix it. Replacing it with defaults loses settings, which
     * is recoverable; a permanently broken app is not.
     */
    private fun createStore(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            Log.e("AppContainer", "Preferences file '$name' was corrupt; resetting to defaults")
            emptyPreferences()
        },
        produceFile = { appContext.preferencesDataStoreFile(name) },
    )
}
