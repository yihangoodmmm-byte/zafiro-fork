package com.niki914.zafiro.app.workbench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal enum class ProjectPowerEvent(val action: String) {
    Connected(Intent.ACTION_POWER_CONNECTED),
    Disconnected(Intent.ACTION_POWER_DISCONNECTED),
}

internal data class ProjectPowerRule(
    val path: String,
    val command: String,
    val event: ProjectPowerEvent,
    val token: String,
)

internal class ProjectPowerTrigger(private val context: Context, private val path: String) {
    private val prefs = preferences(context)
    private val manager = WorkManager.getInstance(context)
    private val name = workName(path)
    private val history = ProjectRunHistory(context)
    val work = manager.getWorkInfosForUniqueWorkFlow(name)

    fun rule(): ProjectPowerRule? = decode(prefs.all[name] as? String)?.takeIf { it.path == path }

    fun enable(command: String, event: ProjectPowerEvent) {
        require(command.isNotBlank() && command.length <= 1024)
        val rule = JSONObject().put("path", path).put("command", command)
            .put("event", event.name).put("token", UUID.randomUUID().toString())
        if (!prefs.edit().putString(name, rule.toString()).commit()) throw IOException("Trigger save failed")
    }

    fun disable(): Operation {
        if (!prefs.edit().remove(name).commit()) throw IOException("Trigger removal failed")
        return manager.cancelUniqueWork(name)
    }

    fun history(running: Boolean = false): List<ProjectRun> = history.load(name, interrupted = !running)

    fun record(run: ProjectRun) {
        history.save(name, (listOf(run) + history().filterNot { it.id == run.id }).take(ProjectRunHistory.LIMIT))
    }

    companion object {
        internal const val TOKEN = "power_trigger_token"
        internal const val EVENT_AT = "power_trigger_at"
        internal const val EVENT_TTL_MS = 10 * 60 * 1000L

        private fun preferences(context: Context) =
            context.getSharedPreferences("project_power_triggers", Context.MODE_PRIVATE)

        private fun workName(path: String): String = "project-power-" + MessageDigest.getInstance("SHA-256")
            .digest(path.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

        private fun decode(value: String?): ProjectPowerRule? {
            if (value == null) return null
            return runCatching {
                val json = JSONObject(value)
                ProjectPowerRule(json.getString("path"), json.getString("command"),
                    ProjectPowerEvent.valueOf(json.getString("event")), json.getString("token"))
            }.getOrNull()
        }

        fun register(context: Context) {
            val app = context.applicationContext
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (isInitialStickyBroadcast) return
                    val event = ProjectPowerEvent.entries.firstOrNull { it.action == intent.action } ?: return
                    val now = System.currentTimeMillis()
                    val manager = WorkManager.getInstance(app)
                    preferences(app).all.values.forEach { value ->
                        val rule = decode(value as? String)?.takeIf { it.event == event } ?: return@forEach
                        val request = OneTimeWorkRequestBuilder<ProjectScheduleWorker>()
                            .setInputData(workDataOf("path" to rule.path, TOKEN to rule.token, EVENT_AT to now))
                            .build()
                        manager.enqueueUniqueWork(workName(rule.path), ExistingWorkPolicy.KEEP, request)
                    }
                }
            }
            val filter = IntentFilter().apply { ProjectPowerEvent.entries.forEach { addAction(it.action) } }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }
}
