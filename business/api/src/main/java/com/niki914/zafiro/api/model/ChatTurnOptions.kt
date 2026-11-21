package com.niki914.zafiro.api.model

import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Snapshot taken by the caller for an individual chat turn. A workspace is a default, not a sandbox. */
data class ChatTurnOptions(
    val workspacePath: String? = null,
    val workflowEnabled: Boolean = false,
    val reasoningGuideEnabled: Boolean = false,
    val reasoningEffort: ChatReasoningEffort? = null,
)

/** Null keeps the active model profile's thinking configuration. */
enum class ChatReasoningEffort(val wireValue: String) {
    Low("low"), Medium("medium"), High("high");

    companion object {
        fun fromWire(value: String): ChatReasoningEffort? = entries.firstOrNull { it.wireValue == value }
    }
}

/** A real local directory usable by path-based tools, not just a document URI. Call resolve off main. */
object WorkspaceDirectory {
    fun absolutePath(input: String): String? = input.trim().takeIf { path ->
        path.length in 1..4096 && path.startsWith('/') &&
            input.none { Character.isISOControl(it) }
    }

    fun resolve(input: String): String? {
        val path = absolutePath(input) ?: return null
        return try {
            val file = File(path).canonicalFile
            // Reject aliases with control characters or a path the terminal would trim differently.
            if (absolutePath(file.path) != file.path || !file.isDirectory) return null
            Files.newDirectoryStream(file.toPath()).use { it.iterator().hasNext() }
            file.path
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: java.nio.file.DirectoryIteratorException) {
            null
        }
    }
}
