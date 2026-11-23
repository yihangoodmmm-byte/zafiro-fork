package com.niki914.zafiro.app.ui.content

import android.content.Context
import android.content.MutableContextWrapper
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.browser.AndroidBrowserService
import com.niki914.zafiro.app.ui.model.BrowserWorkbenchIntent
import com.niki914.zafiro.app.ui.model.BrowserWorkbenchViewModel
import com.niki914.zafiro.platform.browser.BrowserService
import com.niki914.zafiro.service.requireService

/** A viewer never changes the agent's execution tab. Touch is enabled only after explicit takeover. */
private class BrowserFrame(context: Context) : FrameLayout(context) {
    var manualControl = false
    var targetWidth = 390
    var targetHeight = 844
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        for (index in 0 until childCount) getChildAt(index).measure(
            MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY),
        )
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = minOf(width.toFloat() / targetWidth, height.toFloat() / targetHeight)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            child.layout(0, 0, targetWidth, targetHeight)
            child.pivotX = 0f; child.pivotY = 0f
            child.scaleX = scale; child.scaleY = scale
            child.translationX = (width - targetWidth * scale) / 2f
            child.translationY = (height - targetHeight * scale) / 2f
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = if (manualControl) super.dispatchTouchEvent(event) else true
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = if (manualControl) super.dispatchKeyEvent(event) else true
}

@Composable
fun BrowserWorkbenchContent(viewModel: BrowserWorkbenchViewModel = pageViewModel()) {
    val state by viewModel.uiStateFlow.collectAsState()
    val service = remember { requireService<BrowserService>() as AndroidBrowserService }
    val selected = state.tabs.firstOrNull { it.id == state.selectedId }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.address,
                onValueChange = { viewModel.sendIntent(BrowserWorkbenchIntent.Address(it)) },
                label = { Text(stringResource(R.string.browser_address)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { viewModel.sendIntent(BrowserWorkbenchIntent.Open) }) { Text(stringResource(R.string.browser_open)) }
        }
        Text(stringResource(R.string.browser_isolation_notice), style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            state.tabs.forEach { tab ->
                TextButton(onClick = { viewModel.sendIntent(BrowserWorkbenchIntent.Select(tab.id)) }) {
                    Text((if (tab.id == state.selectedId) "• " else "") + tab.title.ifBlank { tab.url }.take(36))
                }
            }
        }
        if (selected == null) {
            Text(stringResource(R.string.browser_empty))
        } else {
            Text(selected.url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            selected.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick = { viewModel.sendIntent(BrowserWorkbenchIntent.ToggleControl) }) {
                    Text(stringResource(if (selected.humanControl) R.string.browser_release_control else R.string.browser_take_control))
                }
                TextButton(onClick = { viewModel.sendIntent(BrowserWorkbenchIntent.Close) }) { Text(stringResource(R.string.browser_close)) }
            }
            if (selected.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            key(selected.id) {
                val context = LocalContext.current
                val frame = remember(selected.id) { BrowserFrame(context) }
                DisposableEffect(frame) {
                    onDispose {
                        for (index in 0 until frame.childCount) {
                            (frame.getChildAt(index).context as? MutableContextWrapper)?.baseContext = frame.context.applicationContext
                        }
                        frame.removeAllViews()
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { frame },
                        modifier = Modifier.fillMaxSize(),
                        update = { host ->
                            host.manualControl = selected.humanControl
                            val density = host.resources.displayMetrics.density
                            host.targetWidth = ((when (selected.viewport) { "desktop" -> 1280; "tablet" -> 768; else -> 390 }) * density).toInt()
                            host.targetHeight = ((if (selected.viewport == "phone") 844 else 900) * density).toInt()
                            host.requestLayout()
                            val view = service.viewForPresentation(selected.id)
                            (view?.context as? MutableContextWrapper)?.baseContext = host.context
                            if (view != null && view.parent !== host) {
                                (view.parent as? ViewGroup)?.removeView(view)
                                host.removeAllViews()
                                host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                            }
                        },
                    )
                }
            }
        }
    }
}
