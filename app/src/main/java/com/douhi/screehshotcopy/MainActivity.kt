package com.douhi.screehshotcopy

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.douhi.screehshotcopy.ui.HomeScreen
import com.douhi.screehshotcopy.ui.theme.ScreenshotClipboardTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var pendingEnable = false

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (pendingEnable) {
            pendingEnable = false
            if (hasExternalAccess()) {
                if (!hasNotificationPermission()) requestNotifications()
                viewModel.setEnabled(true)
            }
        }
    }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (pendingEnable) {
            pendingEnable = false
            if (grants.values.all { it }) {
                if (!hasNotificationPermission()) requestNotifications()
                viewModel.setEnabled(true)
            }
        }
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val path = uri?.let { resolveFolderPath(it) }
        if (path != null) {
            viewModel.setFolder(path)
        } else {
            Toast.makeText(this, R.string.unsupported_folder, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.ensureServiceIfEnabled()
        setContent {
            val settings by viewModel.settings.collectAsState()
            val status by viewModel.status.collectAsState()
            ScreenshotClipboardTheme {
                HomeScreen(
                    settings = settings,
                    status = status,
                    hasAllFilesAccess = hasExternalAccess(),
                    hasNotifPermission = hasNotificationPermission(),
                    onToggle = { want ->
                        if (want && !hasExternalAccess()) {
                            pendingEnable = true
                            requestStorageAccess()
                        } else {
                            if (want && !hasNotificationPermission()) requestNotifications()
                            viewModel.setEnabled(want)
                        }
                    },
                    onGrantAllFiles = { requestAllFilesAccess() },
                    onGrantNotifications = { requestNotifications() },
                    onPickFolder = { folderPicker.launch(null) },
                    onBehaviorChange = viewModel::setDeleteBehavior,
                    onDelayChange = viewModel::setDeleteDelayMs,
                    onTestCopy = viewModel::testCopy,
                )
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (hasExternalAccess()) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            allFilesLauncher.launch(intent)
        } catch (e: Exception) {
            allFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess()
        } else {
            val permissions = buildList {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            legacyStorageLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun hasExternalAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) return
        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun resolveFolderPath(uri: Uri): String? {
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("primary:")) docId.removePrefix("primary:").trimEnd('/') else null
        }.getOrNull()
    }
}
