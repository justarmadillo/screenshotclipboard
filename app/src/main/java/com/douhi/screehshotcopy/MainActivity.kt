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
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.douhi.screehshotcopy.data.AppSettings
import com.douhi.screehshotcopy.ui.HomeScreen
import com.douhi.screehshotcopy.ui.theme.ScreenshotClipboardTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /**
     * Permission grants happen in system UI, outside this process, so they are re-read on every
     * resume. Holding them in Compose state is what makes the cards update when the user comes
     * back from Settings instead of showing a stale "not granted".
     */
    private var storageGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)

    /**
     * Survives the activity being recreated while the user is away in the system permission
     * screen — rotating the device there would otherwise silently drop the pending enable.
     */
    private var pendingEnable = false

    private val allFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { finishEnableIfPossible() }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { finishEnableIfPossible() }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val path = uri?.let { resolveFolderPath(it) }
        if (path != null) {
            viewModel.setFolder(path)
        } else {
            Toast.makeText(this, R.string.unsupported_folder, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingEnable = savedInstanceState?.getBoolean(STATE_PENDING_ENABLE) ?: false
        enableEdgeToEdge()
        refreshPermissions()
        viewModel.ensureServiceIfEnabled()
        setContent {
            val settings by viewModel.settings.collectAsState()
            val status by viewModel.status.collectAsState()
            ScreenshotClipboardTheme {
                HomeScreen(
                    settings = settings,
                    status = status,
                    hasAllFilesAccess = storageGranted,
                    hasNotifPermission = notificationsGranted,
                    onToggle = ::onToggleMonitoring,
                    onGrantAllFiles = { requestStorageAccess() },
                    onGrantNotifications = { requestNotifications() },
                    onPickFolder = { openFolderPicker() },
                    onTimeoutChange = viewModel::setKeepTimeoutMs,
                    onTestCopy = viewModel::testCopy,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PENDING_ENABLE, pendingEnable)
    }

    private fun refreshPermissions() {
        storageGranted = hasExternalAccess()
        notificationsGranted = hasNotificationPermission()
    }

    private fun onToggleMonitoring(want: Boolean) {
        if (!want) {
            viewModel.setEnabled(false)
            return
        }
        if (!hasExternalAccess()) {
            // Storage access is the one hard requirement; everything else is requested alongside.
            pendingEnable = true
            requestStorageAccess()
            return
        }
        // The keep prompt is a notification, so ask for it — but never block monitoring on it.
        requestNotifications()
        viewModel.setEnabled(true)
    }

    private fun finishEnableIfPossible() {
        refreshPermissions()
        if (!pendingEnable) return
        pendingEnable = false
        if (!storageGranted) return
        requestNotifications()
        viewModel.setEnabled(true)
    }

    private fun openFolderPicker() {
        try {
            folderPicker.launch(null)
        } catch (e: Exception) {
            Log.w(TAG, "No document picker available", e)
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestAllFilesAccess()
        } else {
            legacyStorageLauncher.launch(legacyStoragePermissions())
        }
    }

    /** Before API 29, deleting a file in shared storage needs the write permission as well. */
    private fun legacyStoragePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun requestAllFilesAccess() {
        if (hasExternalAccess()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // The per-app screen is the useful one; the global list is the fallback for OEM builds
        // that do not implement it.
        val direct = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        if (launchOrNull(direct)) return
        if (launchOrNull(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))) return
        pendingEnable = false
        Toast.makeText(this, R.string.grant_manually, Toast.LENGTH_LONG).show()
    }

    private fun launchOrNull(intent: Intent): Boolean = try {
        allFilesLauncher.launch(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not launch ${intent.action}", e)
        false
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || hasNotificationPermission()) return
        try {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.w(TAG, "Notification permission request failed", e)
        }
    }

    private fun hasExternalAccess(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            legacyStoragePermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Storage permission check failed", e)
        false
    }

    private fun hasNotificationPermission(): Boolean = try {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        Log.w(TAG, "Notification permission check failed", e)
        false
    }

    /**
     * The app watches a real directory, so a picked tree only helps if it maps to one on the
     * primary volume. SD cards and provider-backed trees have no such path and are rejected
     * outright rather than silently watching the wrong place.
     */
    private fun resolveFolderPath(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val raw = docId.removePrefix(PRIMARY_PREFIX).trim('/')
        if (!docId.startsWith(PRIMARY_PREFIX) || raw.isEmpty()) {
            // Storage root would mean watching the entire volume; that is never what is wanted.
            null
        } else {
            val relative = AppSettings.sanitizeFolder(raw)
            val dir = File(Environment.getExternalStorageDirectory(), relative)
            if (dir.isDirectory) relative else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not resolve picked folder", e)
        null
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PRIMARY_PREFIX = "primary:"
        const val STATE_PENDING_ENABLE = "pending_enable"
    }
}
