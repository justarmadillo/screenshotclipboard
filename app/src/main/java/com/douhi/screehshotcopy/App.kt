package com.douhi.screehshotcopy

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.douhi.screehshotcopy.clipboard.ClipboardHelper
import com.douhi.screehshotcopy.data.SettingsRepository
import com.douhi.screehshotcopy.deletion.DeletionHelper

class App : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(context: Context) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.preferencesDataStoreFile("settings") },
    )
    val settingsRepository = SettingsRepository(dataStore)
    val clipboardHelper = ClipboardHelper(context)
    val deletionHelper = DeletionHelper(context)
}
