package com.douhi.screehshotcopy.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class MonitorStatus(
    val running: Boolean = false,
    val folder: String = "",
    val lastAction: String? = null,
    val error: String? = null,
)

object StatusBus {
    private val _status = MutableStateFlow(MonitorStatus())
    val status: StateFlow<MonitorStatus> = _status

    fun update(transform: (MonitorStatus) -> MonitorStatus) = _status.update(transform)
}
