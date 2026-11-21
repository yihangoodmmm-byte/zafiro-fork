package com.niki914.zafiro.app.ui.devtools

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

internal data class SourceAnchor(val path: String, val line: Int, val column: Int)
internal data class SourceExcerpt(val anchor: SourceAnchor, val fingerprint: String, val first: Int, val lines: List<String>)
internal data class WorkbenchNote(val id: String, val anchor: SourceAnchor, val fingerprint: String, val symbol: String, val text: String)

internal fun workbenchSource(workspace: String, requested: SourceAnchor): SourceExcerpt {
    require(workspace.isNotBlank() && File(workspace).isAbsolute)
    require(requested.path.isNotBlank() && requested.line > 0 && requested.column > 0)
    val root = File(workspace).canonicalFile.toPath()
    val candidate = File(requested.path).let { if (it.isAbsolute) it else root.resolve(it.path).toFile() }
    val file = candidate.canonicalFile
    require(file.toPath().startsWith(root) && file.isFile)
    val limit = 1024 * 1024
    val bytes = file.inputStream().use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    require('\u0000' !in text)
    val wanted = requested.line
    val first = (wanted - 12).coerceAtLeast(1)
    val lines = text.lineSequence().drop(first - 1).take(25).map { it.take(800) }.toList()
    require(wanted - first in lines.indices)
    return SourceExcerpt(requested.copy(path = root.relativize(file.toPath()).toString()), fingerprint(bytes), first, lines)
}

private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

internal class WorkbenchNotes(private val prefs: SharedPreferences, workspace: String) {
    private val key = fingerprint(workspace.toByteArray(Charsets.UTF_8))

    fun load(): List<WorkbenchNote> {
        val stored = JSONArray(prefs.getString(key, "[]") ?: "[]")
        return (0 until minOf(stored.length(), 100)).map { index ->
            val row = stored.getJSONObject(index)
            WorkbenchNote(row.getString("id"), SourceAnchor(row.getString("path"), row.getInt("line"), row.getInt("column")),
                row.getString("fingerprint"), row.getString("symbol"), row.getString("text"))
        }
    }

    fun add(source: SourceExcerpt, symbol: String, text: String): List<WorkbenchNote> {
        val notes = load()
        require(notes.size < 100 && text.isNotBlank() && text.length <= 4000 && symbol.length <= 200)
        return save(listOf(WorkbenchNote(UUID.randomUUID().toString(), source.anchor, source.fingerprint, symbol.trim(), text.trim())) + notes)
    }

    fun remove(id: String): List<WorkbenchNote> = save(load().filterNot { it.id == id })

    private fun save(notes: List<WorkbenchNote>): List<WorkbenchNote> {
        val rows = JSONArray()
        notes.forEach { note ->
            rows.put(JSONObject().put("id", note.id).put("path", note.anchor.path).put("line", note.anchor.line)
                .put("column", note.anchor.column).put("fingerprint", note.fingerprint)
                .put("symbol", note.symbol).put("text", note.text))
        }
        check(prefs.edit().putString(key, rows.toString()).commit())
        return notes
    }
}
