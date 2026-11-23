package com.niki914.zafiro.platform.browser

import kotlinx.coroutines.flow.StateFlow

enum class BrowserCommand {
    OPEN, LIST_TABS, FOLLOW_TAB, CLOSE_TAB, NAVIGATE, BACK, FORWARD, RELOAD,
    SNAPSHOT, CLICK, TYPE, PRESS, SCROLL, GET_TEXT, WAIT, SCREENSHOT, SET_VIEWPORT,
}

data class BrowserRequest(
    val command: BrowserCommand,
    val tabId: String? = null,
    val url: String? = null,
    val ref: String? = null,
    val generation: Long? = null,
    val text: String? = null,
    val key: String? = null,
    val direction: String? = null,
    val amount: Int = 600,
    val replace: Boolean = true,
    val timeoutMillis: Long = 10_000,
    val viewport: String? = null,
)

data class BrowserTabState(
    val id: String,
    val ownerId: String,
    val title: String,
    val url: String,
    val generation: Long,
    val loading: Boolean,
    val humanControl: Boolean,
    val viewport: String,
    val error: String? = null,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
)

data class BrowserNode(
    val ref: String,
    val role: String,
    val name: String,
    val bounds: List<Double>,
    val enabled: Boolean,
)

data class BrowserReply(
    val ok: Boolean,
    val code: String = if (ok) "OK" else "BROWSER_ERROR",
    val message: String = "",
    val tab: BrowserTabState? = null,
    val tabs: List<BrowserTabState> = emptyList(),
    val nodes: List<BrowserNode> = emptyList(),
    val text: String? = null,
    val imagePath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val truncated: Boolean = false,
    val executionUnknown: Boolean = false,
)

/** Tools and the native workbench share this owner-scoped service. Web pages receive no bridge. */
interface BrowserService {
    val tabs: StateFlow<List<BrowserTabState>>
    suspend fun execute(ownerId: String, request: BrowserRequest): BrowserReply
    suspend fun setHumanControl(tabId: String, enabled: Boolean)
    suspend fun closeOwner(ownerId: String)
    suspend fun closeAll()
}
