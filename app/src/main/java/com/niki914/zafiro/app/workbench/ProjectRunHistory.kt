package com.niki914.zafiro.app.workbench

import android.content.Context
import java.io.IOException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal class ProjectRunHistory(context: Context) {
    private val prefs = context.getSharedPreferences("project_run_history", Context.MODE_PRIVATE)

    fun load(path: String, interrupted: Boolean = true): List<ProjectRun> {
        val text = try { prefs.getString(path, null) } catch (_: ClassCastException) { null }
            ?: return emptyList()
        val rows = try { JSONArray(text) } catch (_: JSONException) { return emptyList() }
        val seen = mutableSetOf<Long>()
        return buildList {
            for (index in 0 until minOf(rows.length(), LIMIT)) {
                val row = rows.optJSONObject(index) ?: continue
                val status = ProjectRunStatus.entries.firstOrNull { it.name == row.optString("status") } ?: continue
                val id = row.optLong("id", -1).takeIf { it > 0 } ?: continue
                val command = (row.opt("command") as? String)?.takeIf { it.isNotBlank() } ?: continue
                val exitCode = if (row.isNull("exitCode")) null else
                    row.optInt("exitCode", Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }
                if (!seen.add(id)) continue
                add(ProjectRun(
                    id = id,
                    command = command,
                    status = if (interrupted && status == ProjectRunStatus.Running) ProjectRunStatus.Interrupted else status,
                    output = (row.opt("output") as? String).orEmpty(),
                    exitCode = exitCode,
                    checkCommand = row.opt("checkCommand") as? String,
                    checkExitCode = (row.opt("checkExitCode") as? Number)?.toInt(),
                    checkOutput = (row.opt("checkOutput") as? String).orEmpty(),
                    checking = row.optBoolean("checking"),
                ))
            }
        }
    }

    fun save(path: String, runs: List<ProjectRun>) {
        val rows = JSONArray()
        runs.take(LIMIT).forEach { run ->
            rows.put(JSONObject().apply {
                put("id", run.id)
                put("command", run.command)
                put("status", run.status.name)
                put("output", run.output)
                put("exitCode", run.exitCode ?: JSONObject.NULL)
                put("checkCommand", run.checkCommand ?: JSONObject.NULL)
                put("checkExitCode", run.checkExitCode ?: JSONObject.NULL)
                put("checkOutput", run.checkOutput)
                put("checking", run.checking)
            })
        }
        if (!prefs.edit().putString(path, rows.toString()).commit()) throw IOException("Project run history write failed")
    }

    companion object { const val LIMIT = 20 }
}
