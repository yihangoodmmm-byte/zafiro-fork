package com.niki914.zafiro.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.niki914.zafiro.platform.browser.BrowserCommand
import com.niki914.zafiro.platform.browser.BrowserNode
import com.niki914.zafiro.platform.browser.BrowserReply
import com.niki914.zafiro.platform.browser.BrowserRequest
import com.niki914.zafiro.platform.browser.BrowserService
import com.niki914.zafiro.platform.browser.BrowserTabState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

/** Main-thread WebView owner. All model operations enter through an owner-scoped serialized command path. */
class AndroidBrowserService(context: Context) : BrowserService {
    private val app = context.applicationContext
    private class CommandQueue(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val commands = mutableMapOf<String, CommandQueue>()

    private suspend fun <T> serialized(owner: String, block: suspend () -> T): T {
        val queue = commands.getOrPut(owner) { CommandQueue() }
        queue.users++
        try { return queue.mutex.withLock { block() } } finally {
            queue.users--
            if (queue.users == 0 && owner !in profiles && records.values.none { it.owner == owner }) commands.remove(owner)
        }
    }
    private val records = linkedMapOf<String, Tab>()
    private val activeTabs = mutableMapOf<String, String>()
    private val states = MutableStateFlow<List<BrowserTabState>>(emptyList())
    override val tabs = states.asStateFlow()
    private val profiles = mutableMapOf<String, String>()

    private class Tab(val id: String, val owner: String, val view: WebView) {
        var generation = 0L
        var loading = false
        var humanControl = false
        var viewport = "phone"
        var observation: String? = null
        var error: String? = null
        var title = ""
        var url = "about:blank"
    }

    override suspend fun execute(ownerId: String, request: BrowserRequest): BrowserReply {
        if (ownerId.isBlank()) return failure("OWNER_REQUIRED", "An agent owner is required.")
        return withContext(Dispatchers.Main.immediate) {
            serialized(ownerId) {
                try { executeLocked(ownerId, request) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure("BROWSER_ERROR", error.message ?: "Browser operation failed.") }
            }
        }
    }

    private suspend fun executeLocked(owner: String, request: BrowserRequest): BrowserReply {
        if (request.command == BrowserCommand.LIST_TABS) return BrowserReply(true, tabs = records.values.filter { it.owner == owner }.map(::snapshot))
        if (request.command == BrowserCommand.OPEN) {
            val url = BrowserAddressPolicy.normalize(request.url) ?: return failure("URL_REJECTED", "Use a public HTTP(S) URL without credentials.")
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return failure("PROFILE_UNSUPPORTED", "Update Android System WebView: isolated browser profiles are required.")
            if (records.size >= 12 || records.values.count { it.owner == owner } >= 8) return failure("TAB_LIMIT", "Close an existing tab before opening another.")
            val tab = create(owner)
            activeTabs[owner] = tab.id
            tab.loading = true; tab.url = url; invalidate(tab)
            tab.view.loadUrl(url)
            publish()
            return BrowserReply(true, message = "Navigation started; wait or snapshot after loading.", tab = snapshot(tab))
        }
        val id = request.tabId ?: activeTabs[owner] ?: return failure("TAB_REQUIRED", "Open a tab or provide tab_id.")
        val tab = records[id]?.takeIf { it.owner == owner } ?: return failure("TAB_NOT_FOUND", "Tab is missing or belongs to another agent.")
        if (request.command == BrowserCommand.CLOSE_TAB) {
            if (tab.humanControl) return failure("HUMAN_CONTROL", "Release manual control before closing this tab.")
            destroy(tab)
            return BrowserReply(true, message = "Tab closed.")
        }
        if (request.command == BrowserCommand.FOLLOW_TAB) {
            activeTabs[owner] = id
            return BrowserReply(true, tab = snapshot(tab))
        }
        if (tab.error?.startsWith("renderer:") == true) return failure("RENDERER_GONE", "Close and reopen this tab.")
        if (tab.humanControl && request.command !in setOf(BrowserCommand.SNAPSHOT, BrowserCommand.GET_TEXT, BrowserCommand.SCREENSHOT, BrowserCommand.WAIT)) {
            return failure("HUMAN_CONTROL", "The user has manual control of this tab.")
        }
        when (request.command) {
            BrowserCommand.NAVIGATE -> {
                val url = BrowserAddressPolicy.normalize(request.url) ?: return failure("URL_REJECTED", "Use a public HTTP(S) URL without credentials.")
                tab.loading = true; tab.url = url
                invalidate(tab); tab.view.loadUrl(url)
            }
            BrowserCommand.BACK -> { if (!tab.view.canGoBack()) return failure("NO_HISTORY", "No previous page."); tab.loading = true; invalidate(tab); tab.view.goBack() }
            BrowserCommand.FORWARD -> { if (!tab.view.canGoForward()) return failure("NO_HISTORY", "No next page."); tab.loading = true; invalidate(tab); tab.view.goForward() }
            BrowserCommand.RELOAD -> { tab.loading = true; invalidate(tab); tab.view.reload() }
            BrowserCommand.SNAPSHOT -> {
                if (tab.loading) return failure("PAGE_LOADING", "Wait for loading before taking a snapshot.")
                tab.observation = null
                val generation = tab.generation
                val token = UUID.randomUUID().toString()
                val result = evaluate(tab, BrowserScripts.snapshot(token)) ?: return failure("SCRIPT_TIMEOUT", "Snapshot timed out.")
                if (generation != tab.generation) return failure("STALE_PAGE", "Page changed during observation.")
                tab.observation = token
                val array = result.optJSONArray("nodes") ?: JSONArray()
                val nodes = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val bounds = item.optJSONArray("bounds") ?: return@mapNotNull null
                    BrowserNode(item.optString("ref"), item.optString("role"), item.optString("name"), (0..3).map { bounds.optDouble(it) }, item.optBoolean("enabled"))
                }
                return BrowserReply(true, tab = snapshot(tab), nodes = nodes, truncated = result.optBoolean("truncated"))
            }
            BrowserCommand.CLICK, BrowserCommand.TYPE, BrowserCommand.PRESS -> {
                if (tab.loading) return failure("PAGE_LOADING", "Wait for the current navigation.")
                if (request.generation != tab.generation || tab.observation == null) return failure("STALE_REF", "Take a fresh snapshot and use its generation.")
                if ((request.text?.length ?: 0) > 50_000) return failure("TEXT_TOO_LARGE", "Input text exceeds 50000 characters.")
                val ref = request.ref?.takeIf { it.startsWith(tab.observation + "-") } ?: return failure("STALE_REF", "Use a reference from the current snapshot.")
                if (request.command == BrowserCommand.TYPE && request.text == null) return failure("TEXT_REQUIRED", "text is required.")
                if (request.command == BrowserCommand.PRESS && request.key !in setOf("Enter", "Tab")) return failure("KEY_UNSUPPORTED", "Use Enter for form submission or Tab to move focus.")
                val observation = tab.observation!!
                tab.observation = null
                val result = evaluate(tab, BrowserScripts.action(observation, ref, request.command.name.lowercase(), request.text.orEmpty(), request.replace, request.key.orEmpty()))
                    ?: return BrowserReply(false, "EXECUTION_UNKNOWN", "Action was dispatched but its result timed out. Observe before deciding what to do next.", executionUnknown = true)
                if (!result.optBoolean("ok")) return failure(result.optString("code", "ACTION_FAILED"), result.optString("message"))
            }
            BrowserCommand.SCROLL -> {
                val amount = request.amount.coerceIn(1, 4000)
                val xy = when (request.direction ?: "down") {
                    "down" -> 0 to amount; "up" -> 0 to -amount; "left" -> -amount to 0; "right" -> amount to 0
                    else -> return failure("DIRECTION_INVALID", "Use up, down, left or right.")
                }
                tab.view.scrollBy(xy.first, xy.second)
            }
            BrowserCommand.GET_TEXT -> {
                val result = evaluate(tab, BrowserScripts.text()) ?: return failure("SCRIPT_TIMEOUT", "Text observation timed out.")
                return BrowserReply(true, tab = snapshot(tab), text = result.optString("text"), truncated = result.optBoolean("truncated"))
            }
            BrowserCommand.WAIT -> {
                val wantedText = request.text
                val deadline = SystemClock.elapsedRealtime() + request.timeoutMillis.coerceIn(100, 30_000)
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (tab.error != null) return failure("PAGE_ERROR", tab.error!!)
                    if (!tab.loading) {
                        if (wantedText.isNullOrEmpty()) return BrowserReply(true, tab = snapshot(tab))
                        val text = evaluate(tab, BrowserScripts.text())?.optString("text").orEmpty()
                        if (text.contains(wantedText)) return BrowserReply(true, tab = snapshot(tab))
                    }
                    delay(150)
                }
                return failure("WAIT_TIMEOUT", "The requested page condition did not become true.")
            }
            BrowserCommand.SET_VIEWPORT -> {
                val width = when (request.viewport) { "phone" -> 390; "tablet" -> 768; "desktop" -> 1280; else -> return failure("VIEWPORT_INVALID", "Use phone, tablet or desktop.") }
                tab.viewport = request.viewport!!
                tab.view.settings.useWideViewPort = true
                tab.view.settings.loadWithOverviewMode = true
                tab.view.settings.userAgentString = WebSettings.getDefaultUserAgent(app)
                resize(tab, width, if (width == 390) 844 else 900)
                tab.view.requestLayout()
                invalidate(tab)
            }
            BrowserCommand.SCREENSHOT -> return capture(tab)
            else -> Unit
        }
        publish()
        return BrowserReply(true, tab = snapshot(tab))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(owner: String): Tab {
        val view = WebView(MutableContextWrapper(app))
        try {
            val profileName = profiles.getOrPut(owner) { "zafiro-browser-" + UUID.randomUUID() }
            WebViewCompat.setProfile(view, profileName)
            val profile = WebViewCompat.getProfile(view)
            check(profile.name == profileName) { "Browser profile assignment failed." }
            profile.serviceWorkerController.serviceWorkerWebSettings.apply {
                blockNetworkLoads = true; allowFileAccess = false; allowContentAccess = false
            }
            profile.cookieManager.setAcceptCookie(true)
            profile.cookieManager.setAcceptThirdPartyCookies(view, false)
            view.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                useWideViewPort = true; loadWithOverviewMode = true
                allowFileAccess = false; allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
            }
            val tab = Tab(UUID.randomUUID().toString(), owner, view)
            view.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView?, title: String?) { tab.title = title.orEmpty(); publish() }
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val blocked = BrowserAddressPolicy.normalize(request.url.toString()) == null
                    if (blocked && request.isForMainFrame) { tab.error = "navigation-blocked"; publish() }
                    return blocked
                }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? =
                    if (BrowserAddressPolicy.normalize(request.url.toString()) == null) WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))) else null
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    invalidate(tab); tab.loading = true; tab.url = url.orEmpty(); tab.error = null; publish()
                }
                override fun onPageFinished(view: WebView?, url: String?) { tab.loading = false; tab.url = url.orEmpty(); publish() }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) { tab.error = error.description.toString(); tab.loading = false; publish() }
                }
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (tab.url != url) { invalidate(tab); tab.url = url.orEmpty(); publish() }
                }
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler, error: SslError?) {
                    handler.cancel(); tab.error = "TLS validation failed"; tab.loading = false; publish()
                }
                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    tab.error = "renderer:gone"; tab.loading = false; invalidate(tab)
                    (tab.view.parent as? ViewGroup)?.removeView(tab.view)
                    tab.view.destroy(); publish(); return true
                }
            }
            view.setDownloadListener { _, _, _, _, _ -> tab.error = "download-not-supported"; publish() }
            records[tab.id] = tab
            resize(tab, 390, 844)
            return tab
        } catch (error: Exception) { view.destroy(); throw error }
    }

    private fun resize(tab: Tab, cssWidth: Int, cssHeight: Int) {
        val width = (cssWidth * app.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val height = (cssHeight * app.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        if (tab.view.parent == null) {
            tab.view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            tab.view.layout(0, 0, width, height)
        }
    }

    private suspend fun evaluate(tab: Tab, script: String): JSONObject? = withTimeoutOrNull(5000) {
        suspendCancellableCoroutine { continuation ->
            tab.view.evaluateJavascript(script) { raw ->
                if (continuation.isActive) {
                    val result = try {
                        val decoded = JSONTokener(raw).nextValue()
                        if (decoded is String) JSONObject(decoded) else decoded as? JSONObject
                    } catch (_: JSONException) { null }
                    continuation.resume(result)
                }
            }
        }
    }

    private suspend fun capture(tab: Tab): BrowserReply {
        if (!tab.view.isAttachedToWindow || !tab.view.isShown) return failure("VIEW_NOT_VISIBLE", "Open AI browser from the navigation drawer before capturing a screenshot.")
        if (tab.view.width <= 0 || tab.view.height <= 0) return failure("VIEW_UNMEASURED", "Browser surface is not measured.")
        val width = tab.view.width.coerceAtMost(1600)
        val height = tab.view.height.coerceAtMost(2000)
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(image)
            canvas.scale(width.toFloat() / tab.view.width, height.toFloat() / tab.view.height)
            tab.view.draw(canvas)
            val file = withContext(Dispatchers.IO) {
                val directory = File(app.cacheDir, "browser-shots").apply { mkdirs() }
                directory.listFiles()?.filter { it.isFile }?.sortedByDescending(File::lastModified)?.drop(31)?.forEach { it.delete() }
                File(directory, UUID.randomUUID().toString() + ".png").also { output ->
                    output.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }
            return BrowserReply(true, tab = snapshot(tab), imagePath = file.absolutePath, width = width, height = height)
        } finally { image.recycle() }
    }

    override suspend fun setHumanControl(tabId: String, enabled: Boolean) = withContext(Dispatchers.Main.immediate) {
        records[tabId]?.let {
            it.humanControl = enabled
            if (!enabled) it.view.clearFocus()
            invalidate(it)
        }
        publish()
    }
    override suspend fun closeOwner(ownerId: String) = withContext(Dispatchers.Main.immediate) {
        serialized(ownerId) {
            records.values.filter { it.owner == ownerId }.toList().forEach(::destroy)
            activeTabs.remove(ownerId)
            profiles[ownerId]?.let { name ->
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    val deleted = ProfileStore.getInstance().deleteProfile(name)
                    if (deleted) profiles.remove(ownerId)
                }
            }
            publish()
        }
    }
    override suspend fun closeAll() = withContext(Dispatchers.Main.immediate) {
        (profiles.keys + records.values.map { it.owner }).toList().distinct().forEach { closeOwner(it) }
    }

    /** Native UI is trusted to select any owned tab; model-facing lookup always checks the owner. */
    fun viewForPresentation(tabId: String): WebView? = records[tabId]?.takeIf { it.error?.startsWith("renderer:") != true }?.view

    private fun destroy(tab: Tab) {
        records.remove(tab.id)
        (tab.view.parent as? ViewGroup)?.removeView(tab.view)
        if (tab.error?.startsWith("renderer:") != true) { tab.view.stopLoading(); tab.view.destroy() }
        if (activeTabs[tab.owner] == tab.id) {
            val next = records.values.firstOrNull { it.owner == tab.owner }
            if (next == null) activeTabs.remove(tab.owner) else activeTabs[tab.owner] = next.id
        }
        publish()
    }
    private fun invalidate(tab: Tab) { tab.generation++; tab.observation = null }
    private fun snapshot(tab: Tab) = BrowserTabState(tab.id, tab.owner, tab.title, tab.url, tab.generation, tab.loading, tab.humanControl, tab.viewport, tab.error)
    private fun publish() { states.value = records.values.map(::snapshot) }
    private fun failure(code: String, message: String) = BrowserReply(false, code, message)
}
