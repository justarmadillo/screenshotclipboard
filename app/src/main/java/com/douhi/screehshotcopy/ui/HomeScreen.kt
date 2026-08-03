package com.douhi.screehshotcopy.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.douhi.screehshotcopy.R
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.data.DeleteBehavior
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
    onBehaviorChange: (DeleteBehavior) -> Unit,
    onDelayChange: (Long) -> Unit,
    onTestCopy: () -> Unit,
) {
    var delaySecondsText by remember(settings.deleteDelayMs) {
        mutableStateOf((settings.deleteDelayMs / 1000).toString())
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.monitoring),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                stringResource(
                                    if (settings.enabled) R.string.status_running
                                    else R.string.status_stopped
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = settings.enabled, onCheckedChange = onToggle)
                    }
                    status.lastAction?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    status.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onTestCopy) {
                        Text(stringResource(R.string.test_copy))
                    }
                }
            }

            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.watched_folder),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        status.folder.ifEmpty { settings.folderPath },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onPickFolder) {
                        Text(stringResource(R.string.change_folder))
                    }
                }
            }

            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.after_copy),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    RadioButtonRow(
                        text = stringResource(R.string.keep_file),
                        selected = settings.deleteBehavior == DeleteBehavior.KEEP,
                        onClick = { onBehaviorChange(DeleteBehavior.KEEP) },
                    )
                    RadioButtonRow(
                        text = stringResource(R.string.delete_after),
                        selected = settings.deleteBehavior == DeleteBehavior.DELETE,
                        onClick = { onBehaviorChange(DeleteBehavior.DELETE) },
                    )
                    if (settings.deleteBehavior == DeleteBehavior.DELETE) {
                        OutlinedTextField(
                            value = delaySecondsText,
                            onValueChange = { input ->
                                delaySecondsText = input.filter { it.isDigit() }.take(3)
                                delaySecondsText.toLongOrNull()?.let { seconds ->
                                    onDelayChange(seconds.coerceIn(1, 600) * 1000)
                                }
                            },
                            label = { Text(stringResource(R.string.delay_seconds)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.permissions),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    PermissionRow(
                        title = stringResource(R.string.all_files_access),
                        granted = hasAllFilesAccess,
                        actionLabel = stringResource(R.string.grant),
                        onAction = onGrantAllFiles,
                    )
                    PermissionRow(
                        title = stringResource(R.string.notifications),
                        granted = hasNotifPermission,
                        actionLabel = stringResource(R.string.grant),
                        onAction = onGrantNotifications,
                    )
                }
            }

            Text(
                stringResource(R.string.oem_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        if (granted) {
            Text(
                stringResource(R.string.granted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun RadioButtonRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
