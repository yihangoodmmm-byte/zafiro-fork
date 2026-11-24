package com.niki914.zafiro.app.workbench

import android.content.ComponentName
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Build
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
import androidx.compose.material3.OutlinedTextField
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private fun TextureView.previewPixels(): IntArray? {
    if (!isAvailable) return null
    val frame = try { getBitmap(32, 56) } catch (_: IllegalStateException) { null } ?: return null
    return try {
        val values = IntArray(frame.width * frame.height)
        frame.getPixels(values, 0, frame.width, 0, 0, frame.width, frame.height)
        values
    } finally {
        frame.recycle()
    }
}

private data class DisplayApp(val component: ComponentName, val label: String)

@Composable
internal fun ProjectVirtualDisplayPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var receipt by remember { mutableStateOf<Int?>(null) }
    var firstFrame by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<ProjectVirtualDisplay?>(null) }
    var preview by remember { mutableStateOf<TextureView?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var apps by remember { mutableStateOf<List<DisplayApp>>(emptyList()) }

    var agentPrompt by remember { mutableStateOf("") }
    var agentResult by remember { mutableStateOf("") }
    var proposal by remember { mutableStateOf<Pair<DisplayAgentAction, CompletableDeferred<Boolean>>?>(null) }
    val approvalLock = remember { Mutex() }

    fun release() {
        proposal?.second?.complete(false)
        proposal = null
        agentResult = ""
        operation?.cancel()
        operation = null
        session?.close()
        session = null
        picker = false
        preview = null
        firstFrame = false
        receipt = null
    }
    fun saveScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val target = session ?: return
        val view = preview ?: return
        if (!target.valid || !firstFrame || !view.isAvailable || operation?.isActive == true) return
        failure = null
        receipt = null
        operation = scope.launch {
            val saved = try {
                val frame = view.getBitmap(ProjectVirtualDisplay.WIDTH, ProjectVirtualDisplay.HEIGHT)
                if (frame == null) false else try {
                    saveVirtualDisplayPng(context.contentResolver, frame)
                } finally {
                    frame.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (session === target) {
                if (saved) receipt = R.string.project_virtual_screenshot_saved
                else failure = R.string.project_virtual_screenshot_failed
                operation = null
            }
        }
    }

    fun send(error: Int, confirmed: Int, observePixels: Boolean = false, action: suspend (ProjectVirtualDisplay) -> Boolean) {
        val target = session ?: return
        if (!target.valid || operation?.isActive == true) return
        failure = null
        receipt = null
        operation = scope.launch {
            val view = preview
            val before = if (observePixels && firstFrame) view?.previewPixels() else null
            val success = try { action(target) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
            val changed = if (success && before != null && view != null) withTimeoutOrNull(600L) {
                var changed = false
                while (!changed && session === target && preview === view && target.valid) {
                    delay(100L)
                    val after = view.previewPixels()
                    changed = after != null && !before.contentEquals(after)
                }
                changed
            } == true else false
            if (session === target) {
                if (success) receipt = if (changed) R.string.project_virtual_pixels_changed else confirmed
                else failure = error
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

    proposal?.let { (action, approval) ->
        val detail = when (action.kind) {
            "tap" -> stringResource(R.string.project_virtual_agent_tap, action.x, action.y)
            "swipe" -> stringResource(R.string.project_virtual_agent_swipe, action.x, action.y, action.endX, action.endY)
            else -> stringResource(R.string.project_device_back)
        }
        ConfirmationLiquidDialog(
            visible = true, onDismissRequest = { approval.complete(false) },
            title = stringResource(R.string.project_virtual_agent_approve), text = detail,
            negativeButtonText = stringResource(android.R.string.cancel), positiveButtonText = stringResource(android.R.string.ok),
            onNegativeClick = { approval.complete(false) }, onPositiveClick = { approval.complete(true) },
        )
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
                TextButton(onClick = { send(R.string.project_virtual_input_failed, R.string.project_virtual_input_sent, observePixels = true) { it.back() } }, enabled = operation?.isActive != true) {
                    Text(stringResource(R.string.project_device_back))
                }
            }
        }
        failure?.let { Text(stringResource(it)) }
        receipt?.let { Text(stringResource(it)) }
        session?.let {
            TextButton(onClick = ::saveScreenshot, enabled = firstFrame && it.valid && operation?.isActive != true) {
                Text(stringResource(R.string.project_virtual_screenshot_save))
            }
            Text(stringResource(R.string.project_virtual_id, it.id))
            Text(stringResource(if (firstFrame) R.string.project_virtual_first_frame else R.string.project_virtual_waiting_frame))
        }
        if (session?.valid == true && firstFrame) {
            OutlinedTextField(agentPrompt, { agentPrompt = it.take(1000) }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.project_virtual_agent_prompt)) }, singleLine = true,
                enabled = operation?.isActive != true)
            TextButton(onClick = {
                val target = session ?: return@TextButton
                if (!target.valid || operation?.isActive == true) return@TextButton
                failure = null
                agentResult = ""
                val prompt = agentPrompt.trim()
                operation = scope.launch {
                    try {
                        agentResult = runDisplayAgent(prompt) { action ->
                            approvalLock.withLock {
                                if (session !== target || !target.valid) return@withLock false
                                val approval = CompletableDeferred<Boolean>()
                                proposal = action to approval
                                val accepted = try { approval.await() } finally { proposal = null }
                                if (!accepted || session !== target || !target.valid) false
                                else if (action.kind == "back") target.back()
                                else target.gesture(action.x, action.y, action.endX, action.endY, 300)
                            }
                        }
                    } catch (e: CancellationException) {
                        if (e is TimeoutCancellationException && session === target) {
                            failure = R.string.project_virtual_agent_failed
                        }
                        throw e
                    } catch (_: Exception) {
                        if (session === target) failure = R.string.project_virtual_agent_failed
                    } finally {
                        if (session === target) operation = null
                    }
                }
            }, enabled = agentPrompt.isNotBlank() && operation?.isActive != true) {
                Text(stringResource(R.string.project_virtual_agent_run))
            }
            if (agentResult.isNotBlank()) Text(agentResult, maxLines = 3)
        }
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
                                    if (created != null) preview = this@apply
                                    if (created == null) {
                                        enabled = false
                                        failure = R.string.project_virtual_create_failed
                                    }
                                }
                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                                    if (session?.valid == true && !firstFrame) firstFrame = true
                                }
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
                                            send(R.string.project_virtual_input_failed, R.string.project_virtual_input_sent, observePixels = true) { it.gesture(x, y, endX, endY, duration) }
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
                            send(R.string.project_virtual_launch_failed, R.string.project_virtual_launch_confirmed) { it.launch(app.component, 0) }
                        } else {
                            failure = R.string.project_virtual_profile_unsupported
                        }
                    }
                })
            }
        }
    }
}
