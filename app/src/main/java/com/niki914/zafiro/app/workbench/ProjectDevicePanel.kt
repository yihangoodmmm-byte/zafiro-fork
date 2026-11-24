package com.niki914.zafiro.app.workbench

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ProjectDevicePanel(modifier: Modifier = Modifier) {
    var virtualMode by remember { mutableStateOf(false) }
    if (virtualMode) {
        Column(modifier.fillMaxSize()) {
            TextButton(onClick = { virtualMode = false }) { Text(stringResource(R.string.project_virtual_physical)) }
            ProjectVirtualDisplayPanel(Modifier.weight(1f))
        }
        return
    }
    val configuration = LocalConfiguration.current
    var generation by remember { mutableIntStateOf(0) }
    var permissions by remember { mutableStateOf<Map<Permission, PermissionState>?>(null) }
    var inspection by remember { mutableStateOf<AccessibilityController.WindowInspection?>(null) }
    var inspectionFailed by remember { mutableStateOf(false) }
    var navigationFailed by remember { mutableStateOf(false) }
    var captureRequest by remember { mutableIntStateOf(0) }
    var screenshot by remember { mutableStateOf<Bitmap?>(null) }
    var captureFailed by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    LaunchedEffect(captureRequest) {
        if (captureRequest == 0) return@LaunchedEffect
        val bitmap = AccessibilityController.captureScreenImage().getOrNull()
        screenshot = bitmap?.let { original ->
            try { original.copy(Bitmap.Config.ARGB_8888, false) }
            finally { original.recycle() }
        }
        captureFailed = screenshot == null
        capturing = false
    }
    LaunchedEffect(generation) {
        permissions = withContext(Dispatchers.IO) {
            val manager = requireService<PermissionManager>()
            Permission.entries.associateWith { permission ->
                runCatching { manager.status(permission) }.getOrDefault(PermissionState.UNKNOWN)
            }
        }
    }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Text(Build.MANUFACTURER + " " + Build.MODEL, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.project_device_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT))
            Text(stringResource(R.string.project_device_display, configuration.screenWidthDp, configuration.screenHeightDp, configuration.densityDpi))
            Text(Build.SUPPORTED_ABIS.joinToString())
            TextButton(onClick = { generation++ }) { Text(stringResource(R.string.project_device_refresh)) }
            TextButton(onClick = { virtualMode = true }) { Text(stringResource(R.string.project_virtual_mode)) }
        }
        item {
            TextButton(
                onClick = { capturing = true; captureFailed = false; screenshot = null; captureRequest++ },
                enabled = !capturing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    permissions?.get(Permission.ACCESSIBILITY) == PermissionState.GRANTED,
            ) { Text(stringResource(R.string.project_device_capture)) }
            if (captureFailed) Text(stringResource(R.string.project_device_capture_failed))
            screenshot?.let { bitmap ->
                TextButton(onClick = { screenshot = null }) { Text(stringResource(R.string.project_device_capture_clear)) }
                Image(bitmap.asImageBitmap(), stringResource(R.string.project_device_capture), Modifier.fillMaxWidth())
            }
        }
        item {
            TextButton(onClick = {
                inspection = runCatching { AccessibilityController.inspectConnectedWindow() }.getOrNull()
                inspectionFailed = inspection == null
            }, enabled = permissions?.get(Permission.ACCESSIBILITY) == PermissionState.GRANTED) {
                Text(stringResource(R.string.project_device_tree))
            }
            if (inspectionFailed) Text(stringResource(R.string.project_device_tree_failed))
            if (inspection?.limited == true) Text(stringResource(R.string.project_device_tree_limited))
        }
        item {
            Row {
                AccessibilityController.ManualNavigation.entries.forEach { action ->
                    val label = when (action) {
                        AccessibilityController.ManualNavigation.Back -> R.string.project_device_back
                        AccessibilityController.ManualNavigation.Home -> R.string.project_device_home
                        AccessibilityController.ManualNavigation.Recents -> R.string.project_device_recents
                    }
                    TextButton(onClick = {
                        navigationFailed = !runCatching { AccessibilityController.navigateManually(action) }.getOrDefault(false)
                        if (!navigationFailed) { inspection = null; screenshot = null }
                    }, enabled = permissions?.get(Permission.ACCESSIBILITY) == PermissionState.GRANTED) {
                        Text(stringResource(label))
                    }
                }
            }
            if (navigationFailed) Text(stringResource(R.string.project_device_navigation_failed))
        }
        val nodes = inspection?.nodes.orEmpty()
        items(nodes.size) { index ->
            val node = nodes[index]
            Text("  ".repeat(node.depth) + node.className.substringAfterLast('.') + " " + node.bounds,
                style = MaterialTheme.typography.bodySmall)
        }
        val snapshot = permissions
        if (snapshot != null) items(Permission.entries.size) { index ->
            val permission = Permission.entries[index]
            val label = when (permission) {
                Permission.ROOT -> R.string.project_device_root
                Permission.SHIZUKU -> R.string.project_device_shizuku
                Permission.NOTIFICATION -> R.string.project_device_notification
                Permission.OVERLAY -> R.string.project_device_overlay
                Permission.ACCESSIBILITY -> R.string.project_device_accessibility
                Permission.STORAGE -> R.string.project_device_storage
            }
            val status = when (snapshot[permission]) {
                PermissionState.GRANTED -> R.string.project_device_granted
                PermissionState.DENIED_BY_USER -> R.string.project_device_denied
                PermissionState.UNAVAILABLE -> R.string.project_device_unavailable
                PermissionState.FAILED -> R.string.project_device_failed
                else -> R.string.project_device_unknown
            }
            Text(stringResource(R.string.project_device_status, stringResource(label), stringResource(status)),
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}
