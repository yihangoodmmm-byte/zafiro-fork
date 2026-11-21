package com.niki914.zafiro.app.ui.model.home

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 壳层 → 对话页 的一次性请求通道。
 *
 * 为什么需要它：抽屉长在壳层（ZafiroApp），而「开一段新对话」是 ViewModel 的状态操作。
 * 壳层拿不到页面级 ViewModel，只做导航也不够——同一个 ViewModel 会把原对话带回来。
 *
 * 双通道：事件流负责 VM 已在跑的情形；pending 标记负责 VM 尚未创建的情形。
 * 两条路径共用同一标记，谁先消费谁执行，不会重复开新对话。
 */
object HomeStartRequests {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val newConversationEvents = _events.asSharedFlow()

    @Volatile
    private var pendingNewConversation = false

    fun requestNewConversation() {
        pendingNewConversation = true
        _events.tryEmit(Unit)
    }

    fun consumeNewConversation(): Boolean {
        if (!pendingNewConversation) return false
        pendingNewConversation = false
        return true
    }
}
