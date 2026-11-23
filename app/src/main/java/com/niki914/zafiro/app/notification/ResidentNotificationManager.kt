package com.niki914.zafiro.app.notification

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.runtime.service.AgentRuntimeService
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 常驻通知生命周期与授权决议管理器。
 *
 * 核心设计：
 * - 对应悬浮球 [FloatingBallOverlayManager]，作为常驻通知状态载体的控制中枢；
 * - 实现 [Approver] 并向 [AgentControl] 注册，与悬浮球、Compose 授权对话框并发协作；
 * - 驱动 [AgentRuntimeService] 的前台服务启停与实时状态/操作同步。
 */
object ResidentNotificationManager {

    private const val TAG = "niki914_zafiro_ResidentNotificationManager"

    private data class Pending(
        val token: String,
        val request: ApprovalRequest,
        val continuation: CancellableContinuation<ApprovalDecision>,
    )

    private val pending = AtomicReference<Pending?>(null)
    val activeApprovalRequest: ApprovalRequest? get() = pending.get()?.request
    private var approverRegistered = false

    internal val residentApprover = object : Approver {
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision = suspendCancellableCoroutine { cont ->
            val entry = Pending(UUID.randomUUID().toString(), request, cont)
            if (!pending.compareAndSet(null, entry)) {
                cont.resume(ApprovalDecision.Abstain)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                if (pending.compareAndSet(entry, null)) AgentRuntimeService.notifyUpdate()
            }
            AgentRuntimeService.notifyUpdate()
        }
    }

    internal fun tokenFor(request: ApprovalRequest): String? = pending.get()?.takeIf { it.request === request }?.token

    fun resolveApproval(decision: ApprovalDecision, token: String? = null) {
        val entry = pending.get()?.takeIf { it.token == token } ?: return
        if (!pending.compareAndSet(entry, null)) return
        entry.continuation.resume(decision)
        AgentRuntimeService.notifyUpdate()
    }

    fun start(context: Context) {
        Logger.i(TAG, "Starting resident notification")
        if (!approverRegistered) {
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.addApprover(residentApprover)
            approverRegistered = true
        }

        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = AgentRuntimeService.ACTION_START_RESIDENT
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to startForegroundService for AgentRuntimeService", e)
        }
    }

    fun stop(context: Context) {
        Logger.i(TAG, "Stopping resident notification")
        if (approverRegistered) {
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.removeApprover(residentApprover)
            approverRegistered = false
        }
        pending.getAndSet(null)?.continuation?.cancel()

        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = AgentRuntimeService.ACTION_STOP_RESIDENT
        }
        try {
            context.startService(intent)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to send stop action to AgentRuntimeService", e)
        }
    }

    internal fun resetForTest() {
        val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
        if (approverRegistered) {
            agentControl?.removeApprover(residentApprover)
            approverRegistered = false
        }
        pending.getAndSet(null)?.continuation?.cancel()
    }
}
