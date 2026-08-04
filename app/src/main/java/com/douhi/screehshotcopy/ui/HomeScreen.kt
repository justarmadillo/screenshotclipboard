package com.douhi.screehshotcopy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.douhi.screehshotcopy.R
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.util.MonitorStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    status: MonitorStatus,
    hasAllFilesAccess: Boolean,
    hasNotifPermission: Boolean,
    onToggle: (Boolean) -> Unit,
    onGrantAllFiles: () -> Unit,
    onGrantNotifications: () -> Unit,
    onPickFolder: () -> Unit,
    onTimeoutChange: (Long) -> Unit,
    onTestCopy: () -> Unit,
) {
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MonitoringCard(settings, status, onToggle, onTestCopy)

            if (settings.enabled && !hasNotifPermission) {
                WarningCard(
                    text = stringResource(R.string.warn_no_notifications),
                    actionLabel = stringResource(R.string.grant),
                    onAction = onGrantNotifications,
                )
            }

            FolderCard(settings, status, onPickFolder)

            TimeoutCard(settings, onTimeoutChange)

            PermissionsCard(hasAllFilesAccess, hasNotifPermission, onGrantAllFiles, onGrantNotifications)

            Text(
                stringResource(R.string.oem_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonitoringCard(
    settings: AppSettings,
    status: MonitorStatus,
    onToggle: (Boolean) -> Unit,
    onTestCopy: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.monitoring), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(
                            if (settings.enabled) R.string.status_running else R.string.status_stopped
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.enabled, onCheckedChange = onToggle)
            }
            if (status.pendingCount > 0) {
                Text(
                    pluralStringResource(R.plurals.pending_count, status.pendingCount, status.pendingCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            status.lastAction?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            status.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onTestCopy) { Text(stringResource(R.string.test_copy)) }
        }
    }
}

@Composable
private fun FolderCard(settings: AppSettings, status: MonitorStatus, onPickFolder: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.watched_folder), style = MaterialTheme.typography.titleMedium)
            Text(
                status.folder.ifEmpty { settings.folderPath },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onPickFolder) { Text(stringResource(R.string.change_folder)) }
        }
    }
}

@Composable
private fun TimeoutCard(settings: AppSettings, onTimeoutChange: (Long) -> Unit) {
    val storedSeconds = settings.keepTimeoutMs / 1000
    var text by remember { mutableStateOf(storedSeconds.toString()) }
    val minSeconds = AppSettings.MIN_TIMEOUT_MS / 1000
    val maxSeconds = AppSettings.MAX_TIMEOUT_MS / 1000

    // Adopt the stored value when it changes underneath us — the first real value arrives after
    // the initial composition — but only when the field does not already say the same thing, so a
    // half-typed or out-of-range entry is never overwritten while the user is still editing.
    LaunchedEffect(storedSeconds) {
        if (text.toLongOrNull() != storedSeconds) text = storedSeconds.toString()
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.decision_window), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.decision_window_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    val digits = input.filter { it.isDigit() }.take(4)
                    text = digits
                    // Empty or out-of-range input leaves the stored value untouched, so the app
                    // never ends up with a nonsensical window mid-edit.
                    digits.toLongOrNull()
                        ?.takeIf { it in minSeconds..maxSeconds }
                        ?.let { onTimeoutChange(it * 1000) }
                },
                label = { Text(stringResource(R.string.timeout_seconds)) },
                supportingText = {
                    Text(stringResource(R.string.timeout_range, minSeconds, maxSeconds))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermissionsCard(
    hasAllFilesAccess: Boolean,
    hasNotifPermission: Boolean,
    onGrantAllFiles: () -> Unit,
    onGrantNotifications: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.permissions), style = MaterialTheme.typography.titleMedium)
            PermissionRow(
                title = stringResource(R.string.all_files_access),
                granted = hasAllFilesAccess,
                onAction = onGrantAllFiles,
            )
            PermissionRow(
                title = stringResource(R.string.notifications),
                granted = hasNotifPermission,
                onAction = onGrantNotifications,
            )
        }
    }
}

@Composable
private fun WarningCard(text: String, actionLabel: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        if (granted) {
            Text(
                stringResource(R.string.granted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAction) { Text(stringResource(R.string.grant)) }
        }
    }
}
