package com.niki914.zafiro.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.TurnId
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class TurnReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY || intent.data?.scheme != "zafiro-turn-reply") return
        val (conversationId, turnId) = intent.data?.pathSegments?.takeIf { it.size == 2 } ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)
            ?.takeIf { it.length <= 8192 }?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                val accepted = withTimeoutOrNull(3_000L) {
                    XRepo.turnNotificationEnabled() &&
                        findService<AgentManager>()?.activeAgent(conversationId)
                            ?.replyTo(ConversationId(conversationId), TurnId(turnId), text) == true
                } == true
                if (!accepted) showUnavailable(context)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showUnavailable(context)
            } finally {
                pending.finish()
            }
        }
    }

    private fun showUnavailable(context: Context) {
        Toast.makeText(context, R.string.turn_reply_unavailable, Toast.LENGTH_SHORT).show()
    }

    internal companion object {
        const val ACTION_REPLY = "com.niki914.zafiro.action.TURN_REPLY"
        const val KEY_REPLY = "turn_reply"
    }
}
