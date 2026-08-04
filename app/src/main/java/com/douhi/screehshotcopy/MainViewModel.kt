package com.douhi.screehshotcopy

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.service.MonitorService
import com.douhi.screehshotcopy.util.MonitorStatus
import com.douhi.screehshotcopy.util.StatusBus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as App).container
    private val repository = container.settingsRepository

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val status: StateFlow<MonitorStatus> = StatusBus.status

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            // Persist first: the service reads this on start, and on a cold start it must never
            // find a stale "disabled" and shut itself down immediately.
            repository.setEnabled(value)
            if (value) {
                startService()
            } else {
                // Clear the queue here rather than relying on the service to do it, so outstanding
                // prompts disappear even if the service is already gone.
                container.janitor.cancelAllPending()
                if (StatusBus.status.value.running) stopService()
            }
        }
    }

    fun setFolder(path: String) {
        viewModelScope.launch { repository.setFolder(path) }
    }

    fun setKeepTimeoutMs(ms: Long) {
        viewModelScope.launch { repository.setKeepTimeoutMs(ms) }
    }

    fun ensureServiceIfEnabled() {
        viewModelScope.launch {
            if (repository.current().enabled) startService()
        }
    }

    fun testCopy() {
        viewModelScope.launch {
            val result = container.clipboardHelper.testCopy()
            val text = if (result.ok) {
                "Test copy OK — ${result.mime}, clip=[${result.clipMimes}]"
            } else {
                "Test copy FAILED — ${result.source}"
            }
            StatusBus.update { it.copy(lastAction = text, error = null) }
        }
    }

    private fun startService() {
        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(app, Intent(app, MonitorService::class.java))
        } catch (e: Exception) {
            // Android 12+ can refuse a background start; surface it instead of failing silently.
            Log.e(TAG, "Could not start monitor service", e)
            StatusBus.update { it.copy(error = app.getString(R.string.err_service_blocked)) }
        }
    }

    /**
     * Asks the service to stop itself rather than killing it with stopService(). The graceful path
     * cancels the outstanding keep-prompts and clears the queue, so switching monitoring off can
     * never leave a timer armed against a file the app is no longer managing.
     */
    private fun stopService() {
        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, MonitorService::class.java).setAction(MonitorService.ACTION_STOP),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Graceful stop failed, forcing stopService", e)
            try {
                app.stopService(Intent(app, MonitorService::class.java))
            } catch (inner: Exception) {
                Log.w(TAG, "stopService failed", inner)
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
    }
}
