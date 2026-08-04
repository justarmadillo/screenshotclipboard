package com.douhi.screehshotcopy.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class MonitorStatus(
    val running: Boolean = false,
    val folder: String = "",
    val lastAction: String? = null,
    val error: String? = null,
    val pendingCount: Int = 0,
    val notificationsBlocked: Boolean = false,
)

/**
 * Process-wide status shared between the service and the UI. Deliberately not persisted: it
 * describes what is happening right now, and a stale "last action" from a previous boot would be
 * misleading rather than useful.
 */
object StatusBus {
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status

    fun update(transform: (MonitorStatus) -> MonitorStatus) = _status.update(transform)
}
