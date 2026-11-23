package com.niki914.zafiro.app.workbench

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.File
import java.net.URLConnection

@Composable
internal fun ProjectPreview(project: String, document: ProjectDocument) {
    var attempt by remember(project, document.relativePath, document.revision) { mutableIntStateOf(0) }
    key(project, document.relativePath, document.revision, attempt) {
        ProjectPreviewAttempt(project, document) { attempt++ }
    }
}

private enum class PreviewStatus { Loading, Ready, Failed, RendererGone }

@Composable
private fun ProjectPreviewAttempt(project: String, document: ProjectDocument, retry: () -> Unit) {
    val host = "appassets.androidplatform.net"
    val root = File(project).canonicalFile
    val loader = WebViewAssetLoader.Builder().setDomain(host).addPathHandler("/project/") { path ->
        if (path.split('/').any { it.isEmpty() || it.startsWith('.') }) return@addPathHandler blockedPreview()
        val target = File(root, path)
        try {
            if (target.canonicalFile != target.absoluteFile || !target.toPath().startsWith(root.toPath()) ||
                !target.isFile || target.length() > 8 * 1024 * 1024) return@addPathHandler blockedPreview()
            val extension = target.extension.lowercase()
            val mime = when (extension) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js", "mjs" -> "text/javascript"
                "png", "jpg", "jpeg", "webp", "gif", "svg", "woff", "woff2" ->
                    URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                else -> return@addPathHandler blockedPreview()
            }
            WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, target.inputStream())
        } catch (_: IOException) {
            blockedPreview()
        }
    }.build()
    val url = Uri.Builder().scheme("https").authority(host).appendPath("project").apply {
        document.relativePath.split('/').forEach { appendPath(it) }
    }.build().toString()
    ProjectPreviewSurface(url, retry) { request ->
        if (request.url.scheme == "https" && request.url.host == host && request.url.port == -1) {
            loader.shouldInterceptRequest(request.url) ?: blockedPreview()
        } else blockedPreview()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun ProjectPreviewSurface(
    url: String,
    retry: () -> Unit,
    scriptsEnabled: Boolean = false,
    intercept: (WebResourceRequest) -> WebResourceResponse,
) {
    var status by remember { mutableStateOf(PreviewStatus.Loading) }
    Column(Modifier.fillMaxSize()) {
        when (status) {
            PreviewStatus.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            PreviewStatus.Failed, PreviewStatus.RendererGone -> {
                Text(stringResource(R.string.project_preview_failed))
                TextButton(onClick = retry) { Text(stringResource(R.string.project_preview_retry)) }
            }
            PreviewStatus.Ready -> Unit
        }
        if (status != PreviewStatus.RendererGone) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.javaScriptEnabled = scriptsEnabled
                        settings.domStorageEnabled = false
                        settings.blockNetworkLoads = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                if (status == PreviewStatus.Loading) status = PreviewStatus.Ready
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) status = PreviewStatus.Failed
                            }

                            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                                if (request.isForMainFrame) status = PreviewStatus.Failed
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                status = PreviewStatus.RendererGone
                                return true
                            }
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                                intercept(request)
                        }
                        loadUrl(url)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = {},
                onReset = null,
                onRelease = { it.destroy() },
            )
        }
    }
}

internal fun blockedPreview() = WebResourceResponse(
    "text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)),
)
