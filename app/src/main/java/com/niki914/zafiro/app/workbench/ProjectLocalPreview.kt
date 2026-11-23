package com.niki914.zafiro.app.workbench

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.SharedHttp
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit

@Composable
internal fun ProjectLocalPreview(modifier: Modifier) {
    var portInput by remember { mutableStateOf("") }
    var activePort by remember { mutableStateOf<Int?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var scriptsEnabled by remember { mutableStateOf(false) }
    val port = portInput.toIntOrNull()?.takeIf { it in 1024..65535 }
    Column(modifier.fillMaxSize()) {
        Row {
            OutlinedTextField(portInput, { portInput = it.filter { char -> char in '0'..'9' }.take(5) },
                Modifier.weight(1f), singleLine = true,
                label = { Text(stringResource(R.string.project_local_preview_port)) })
            TextButton(onClick = { activePort = port; attempt++ }, enabled = port != null) {
                Text(stringResource(R.string.project_local_preview_open))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = scriptsEnabled, onCheckedChange = { scriptsEnabled = it })
            Text(stringResource(R.string.project_local_preview_scripts))
        }
        activePort?.let { selected ->
            key(selected, attempt, scriptsEnabled) {
                val origin = remember { "preview-" + UUID.randomUUID() + ".invalid" }
                ProjectPreviewSurface("https://$origin/", { attempt++ }, scriptsEnabled) { request ->
                    LocalPreviewRequests.load(selected, origin, scriptsEnabled, request)
                }
            }
        }
    }
}

private object LocalPreviewRequests {
    private const val MAX_BYTES = 4 * 1024 * 1024L
    private val mimeTypes = setOf("text/html", "text/css", "text/plain", "image/png", "image/jpeg",
        "image/webp", "image/gif", "image/avif", "font/woff", "font/woff2")
    private val scriptMimeTypes = setOf("text/javascript", "application/javascript", "text/ecmascript", "application/ecmascript", "application/json")
    private val client by lazy {
        SharedHttp.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).callTimeout(5, TimeUnit.SECONDS).build()
    }

    private fun previewPolicy(scriptsEnabled: Boolean): String {
        val base = "default-src 'none'; img-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; form-action 'none'; base-uri 'none'; sandbox allow-same-origin"
        return if (scriptsEnabled) "$base allow-scripts; script-src 'self' 'unsafe-inline'; connect-src 'self'" else base
    }

    fun load(port: Int, origin: String, scriptsEnabled: Boolean, request: WebResourceRequest): WebResourceResponse {
        val sourceUrl = request.url
        if (request.method != "GET" || sourceUrl.scheme != "https" ||
            sourceUrl.encodedAuthority != origin) return blockedPreview()
        val url = HttpUrl.Builder().scheme("http").host("127.0.0.1").port(port)
            .encodedPath(sourceUrl.encodedPath.orEmpty().ifEmpty { "/" }).encodedQuery(sourceUrl.encodedQuery).build()
        return try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return blockedPreview()
                val body = response.body ?: return blockedPreview()
                val media = body.contentType() ?: return blockedPreview()
                val mime = media.type + "/" + media.subtype
                if ((mime !in mimeTypes && !(scriptsEnabled && !request.isForMainFrame && mime in scriptMimeTypes)) ||
                    body.contentLength() > MAX_BYTES) return blockedPreview()
                val source = body.source()
                if (source.request(MAX_BYTES + 1)) return blockedPreview()
                val bytes = source.readByteArray()
                WebResourceResponse(mime, if (media.type == "text") media.charset(Charsets.UTF_8)?.name() else null,
                    200, "OK", mapOf(
                        "Content-Security-Policy" to previewPolicy(scriptsEnabled),
                        "X-Content-Type-Options" to "nosniff",
                    ), ByteArrayInputStream(bytes))
            }
        } catch (_: IOException) {
            blockedPreview()
        }
    }
}
