package com.niki914.zafiro.app.workbench

import android.content.ComponentName
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Process
import android.os.UserHandle
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.zafiro.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private data class DisplayApp(val component: ComponentName, val label: String)

@Composable
internal fun ProjectVirtualDisplayPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var session by remember { mutableStateOf<ProjectVirtualDisplay?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var apps by remember { mutableStateOf<List<DisplayApp>>(emptyList()) }

    fun release() {
        operation?.cancel()
        operation = null
        session?.close()
        session = null
        picker = false
    }
    fun send(error: Int, action: suspend (ProjectVirtualDisplay) -> Boolean) {
        val target = session ?: return
        if (!target.valid || operation?.isActive == true) return
        failure = null
        operation = scope.launch {
            val success = try { action(target) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            if (session === target) {
                if (!success) failure = error
                operation = null
            }
        }
    }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                enabled = false
                release()
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose {
            owner?.lifecycle?.removeObserver(observer)
            release()
        }
    }
    LaunchedEffect(picker) {
        if (!picker) return@LaunchedEffect
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(query, 0).mapNotNull { result ->
                result.activityInfo?.takeIf { it.exported && it.packageName != context.packageName }?.let {
                    DisplayApp(ComponentName(it.packageName, it.name), result.loadLabel(pm).toString())
                }
            }.distinctBy { it.component }.sortedBy { it.label.lowercase() }
        }
    }

    Column(modifier) {
        Row {
            TextButton(onClick = {
                failure = null
                if (enabled) {
                    enabled = false
                    release()
                } else if (!ProjectVirtualDisplay.supported) {
                    failure = R.string.project_virtual_unsupported
                } else if (!ProjectVirtualDisplay.hasPermission()) {
                    failure = R.string.project_virtual_permission
                } else {
                    enabled = true
                }
            }) { Text(stringResource(if (enabled) R.string.project_virtual_close else R.string.project_virtual_create)) }
            if (session != null) {
                TextButton(onClick = { picker = true }, enabled = operation?.isActive != true) {
                    Text(stringResource(R.string.project_virtual_launch))
                }
                TextButton(onClick = { send(R.string.project_virtual_input_failed) { it.back() } }, enabled = operation?.isActive != true) {
                    Text(stringResource(R.string.project_device_back))
                }
            }
        }
        failure?.let { Text(stringResource(it)) }
        session?.let { Text(stringResource(R.string.project_virtual_id, it.id)) }
        if (enabled) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val ratio = ProjectVirtualDisplay.WIDTH.toFloat() / ProjectVirtualDisplay.HEIGHT
                AndroidView(
                    modifier = Modifier.width(minOf(maxWidth, maxHeight * ratio)).aspectRatio(ratio),
                    factory = { viewContext ->
                        TextureView(viewContext).apply {
                            var surface: Surface? = null
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                    texture.setDefaultBufferSize(ProjectVirtualDisplay.WIDTH, ProjectVirtualDisplay.HEIGHT)
                                    val output = Surface(texture)
                                    surface = output
                                    var created: ProjectVirtualDisplay? = null
                                    created = runCatching {
                                        ProjectVirtualDisplay.create(viewContext, output) {
                                            if (session === created && created != null) {
                                                enabled = false
                                                release()
                                                failure = R.string.project_virtual_stopped
                                            }
                                        }
                                    }.getOrNull()
                                    session = created
                                    if (created == null) {
                                        enabled = false
                                        failure = R.string.project_virtual_create_failed
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    release()
                                    surface?.release()
                                    surface = null
                                    return true
                                }
                            }
                            var downX = 0f
                            var downY = 0f
                            setOnTouchListener { view, event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN -> {
                                        downX = event.x
                                        downY = event.y
                                        true
                                    }
                                    MotionEvent.ACTION_UP -> {
                                        if (view.width > 0 && view.height > 0 &&
                                            event.x >= 0 && event.x < view.width && event.y >= 0 && event.y < view.height
                                        ) {
                                            val x = (downX / view.width * ProjectVirtualDisplay.WIDTH).toInt()
                                            val y = (downY / view.height * ProjectVirtualDisplay.HEIGHT).toInt()
                                            val tap = abs(event.x - downX) < 8 && abs(event.y - downY) < 8
                                            val endX = if (tap) x else (event.x / view.width * ProjectVirtualDisplay.WIDTH).toInt()
                                            val endY = if (tap) y else (event.y / view.height * ProjectVirtualDisplay.HEIGHT).toInt()
                                            val duration = event.eventTime - event.downTime
                                            send(R.string.project_virtual_input_failed) { it.gesture(x, y, endX, endY, duration) }
                                        }
                                        true
                                    }
                                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL -> true
                                    else -> false
                                }
                            }
                        }
                    },
                )
            }
        }
    }
    OptionSheet(visible = picker, onDismissRequest = { picker = false }, title = stringResource(R.string.project_virtual_launch)) { dismissThen ->
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (apps.isEmpty()) Text(stringResource(R.string.project_virtual_no_apps))
            apps.forEach { app ->
                OptionRow(title = app.label, onClick = {
                    dismissThen {
                        if (Process.myUserHandle() == UserHandle.getUserHandleForUid(0)) {
                            send(R.string.project_virtual_launch_failed) { it.launch(app.component, 0) }
                        } else {
                            failure = R.string.project_virtual_profile_unsupported
                        }
                    }
                })
            }
        }
    }
}
