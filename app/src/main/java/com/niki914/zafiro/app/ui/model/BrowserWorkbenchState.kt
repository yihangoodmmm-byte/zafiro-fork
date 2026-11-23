package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.zafiro.app.browser.AndroidBrowserService
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.platform.browser.BrowserCommand
import com.niki914.zafiro.platform.browser.BrowserRequest
import com.niki914.zafiro.platform.browser.BrowserService
import com.niki914.zafiro.platform.browser.BrowserTabState
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.launch

data class BrowserWorkbenchState(
    val tabs: List<BrowserTabState> = emptyList(),
    val selectedId: String? = null,
    val address: String = "https://example.com",
    val error: String? = null,
)
sealed interface BrowserWorkbenchIntent {
    data class Select(val id: String) : BrowserWorkbenchIntent
    data class Address(val value: String) : BrowserWorkbenchIntent
    data object Open : BrowserWorkbenchIntent
    data class Navigate(val command: BrowserCommand) : BrowserWorkbenchIntent
    data object Close : BrowserWorkbenchIntent
    data object ToggleControl : BrowserWorkbenchIntent
}
class BrowserWorkbenchViewModel : ComposeMVIViewModel<BrowserWorkbenchIntent, BrowserWorkbenchState, Nothing>() {
    private val browser = requireService<BrowserService>()
    override fun initUiState() = BrowserWorkbenchState()
    init {
        viewModelScope.launch {
            browser.tabs.collect { tabs ->
                updateState {
                    val selected = selectedId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id
                    val tab = tabs.firstOrNull { it.id == selected }
                    val oldUrl = this.tabs.firstOrNull { it.id == selectedId }?.url
                    val nextAddress = if (tab != null && (selected != selectedId || address == oldUrl)) tab.url else address
                    copy(tabs = tabs, selectedId = selected, address = nextAddress)
                }
            }
        }
    }
    override suspend fun handleIntent(intent: BrowserWorkbenchIntent) {
        when (intent) {
            is BrowserWorkbenchIntent.Select -> updateState {
                val tab = tabs.firstOrNull { it.id == intent.id } ?: return@updateState this
                copy(selectedId = tab.id, address = tab.url, error = null)
            }
            is BrowserWorkbenchIntent.Address -> updateState { copy(address = intent.value) }
            BrowserWorkbenchIntent.Open -> {
                val result = browser.execute("manual-workbench", BrowserRequest(BrowserCommand.OPEN, url = currentState.address))
                if (result.ok) {
                    result.tab?.let { tab -> browser.setHumanControl(tab.id, true) }
                    updateState { copy(selectedId = result.tab?.id ?: selectedId, error = null) }
                } else updateState { copy(error = result.message) }
            }
            is BrowserWorkbenchIntent.Navigate -> currentState.tabs.firstOrNull { it.id == currentState.selectedId }?.let { tab ->
                val result = (browser as AndroidBrowserService).navigateManually(
                    BrowserRequest(intent.command, tabId = tab.id, url = currentState.address),
                )
                updateState { copy(error = result.message.takeUnless { result.ok }) }
            }
            BrowserWorkbenchIntent.ToggleControl -> currentState.tabs.firstOrNull { it.id == currentState.selectedId }?.let { tab ->
                browser.setHumanControl(tab.id, !tab.humanControl)
            }
            BrowserWorkbenchIntent.Close -> currentState.tabs.firstOrNull { it.id == currentState.selectedId }?.let { tab ->
                browser.setHumanControl(tab.id, false)
                val result = browser.execute(tab.ownerId, BrowserRequest(BrowserCommand.CLOSE_TAB, tabId = tab.id))
                if (!result.ok) updateState { copy(error = result.message) }
            }
        }
    }
    override fun onError(error: Throwable) { updateState { copy(error = error.message ?: error.javaClass.simpleName) } }
}
