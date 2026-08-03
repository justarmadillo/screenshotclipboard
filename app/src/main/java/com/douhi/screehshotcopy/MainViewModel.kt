package com.douhi.screehshotcopy

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.data.DeleteBehavior
import com.douhi.screehshotcopy.service.MonitorService
import com.douhi.screehshotcopy.util.MonitorStatus
import com.douhi.screehshotcopy.util.StatusBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container
    private val repository = container.settingsRepository

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    val status: StateFlow<MonitorStatus> = StatusBus.status

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            repository.setEnabled(value)
            val app = getApplication<Application>()
            if (value) {
                ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
            } else {
                app.stopService(Intent(app, MonitorService::class.java))
            }
        }
    }

    fun setFolder(path: String) {
        viewModelScope.launch { repository.setFolder(path) }
    }

    fun setDeleteBehavior(behavior: DeleteBehavior) {
        viewModelScope.launch { repository.setDeleteBehavior(behavior) }
    }

    fun setDeleteDelayMs(ms: Long) {
        viewModelScope.launch { repository.setDeleteDelayMs(ms) }
    }

    fun ensureServiceIfEnabled() {
        viewModelScope.launch {
            if (repository.settings.first().enabled) {
                val app = getApplication<Application>()
                ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
            }
        }
    }

    fun testCopy() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = container.clipboardHelper.testCopy()
            val text = if (result.ok) {
                "Test copy: ${result.source}/${result.mime} clip=[${result.clipMimes}] ${result.uri}"
            } else {
                "Test copy FAILED: ${result.source}"
            }
            StatusBus.update { it.copy(lastAction = text, error = null) }
        }
    }
}
