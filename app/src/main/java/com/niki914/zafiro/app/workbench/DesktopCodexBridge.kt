package com.niki914.zafiro.app.workbench

import com.niki914.zafiro.repo.SharedHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal enum class CodexAction(val route: String) { Health("health"), Run("run"), Stop("stop") }
internal data class CodexReply(val ok: Boolean, val output: String = "", val httpStatus: Int? = null)

internal object DesktopCodexBridge {
    private val client by lazy {
        SharedHttp.client.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(135, TimeUnit.SECONDS).callTimeout(140, TimeUnit.SECONDS).build()
    }

    fun accepts(endpoint: String): Boolean = base(endpoint) != null

    suspend fun request(endpoint: String, token: String, action: CodexAction, cwd: String, prompt: String): CodexReply =
        withContext(Dispatchers.IO) {
            val url = requireNotNull(base(endpoint)).newBuilder().addPathSegment(action.route).build()
            require(token.isNotBlank() && token.length <= 2048 && token.all { it in '!'..'~' })
            val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
            if (action != CodexAction.Health) {
                val data = JSONObject()
                if (action == CodexAction.Run) {
                    require(prompt.isNotBlank() && prompt.length <= 16000 && cwd.isNotBlank() && cwd.length <= 2048)
                    data.put("prompt", prompt).put("cwd", cwd).put("timeout_seconds", 120)
                        .put("config", JSONObject().put("cwd", cwd))
                }
                request.post(data.toString().toRequestBody("application/json".toMediaType()))
            }
            client.newCall(request.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext CodexReply(false, httpStatus = response.code)
                val raw = response.peekBody(65536).string()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (json == null) return@withContext CodexReply(false)
                val output = json.optString("output").ifBlank { json.optString("message") }
                CodexReply(json.optBoolean("ok", true), output.replace(token, "[redacted]").take(12000))
            }
        }

    private fun base(endpoint: String) = endpoint.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }
}
