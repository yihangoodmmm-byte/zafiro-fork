package com.niki914.zafiro.app.workbench

import android.app.Application
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object ProjectOfflineChat {
    const val PROMPT_LIMIT = 512
    private const val CONTEXT_TOKENS = 2048
    private const val REPLY_LIMIT = 4000
    private const val MODEL_LIMIT = 2L * 1024 * 1024 * 1024

    private fun model(app: Application) = File(app.noBackupFilesDir, "offline-chat.litertlm")

    suspend fun installed(app: Application): Boolean = withContext(Dispatchers.IO) { model(app).isFile }

    suspend fun remove(app: Application) = withContext(Dispatchers.IO) {
        Files.deleteIfExists(model(app).toPath())
    }

    suspend fun importModel(app: Application, uri: Uri) = withContext(Dispatchers.IO) {
        val target = model(app)
        val temporary = File.createTempFile("offline-chat-", ".partial", target.parentFile)
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Unreadable model")
            input.use { source ->
                temporary.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MODEL_LIMIT) throw IOException("Model exceeds size limit")
                        sink.write(buffer, 0, count)
                    }
                    if (total == 0L) throw IOException("Empty model")
                }
            }
            coroutineContext.ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    suspend fun generate(app: Application, prompt: String, onChunk: suspend (String) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val file = model(app)
            require(file.isFile && prompt.isNotBlank() && prompt.length <= PROMPT_LIMIT)
            val engine = Engine(EngineConfig(
                modelPath = file.absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = CONTEXT_TOKENS,
                cacheDir = app.cacheDir.absolutePath,
            ))
            try {
                engine.initialize()
                coroutineContext.ensureActive()
                val conversation = engine.createConversation(ConversationConfig())
                try {
                    var received = 0
                    conversation.sendMessageAsync(prompt).takeWhile { message ->
                        for (part in message.contents.contents.filterIsInstance<Content.Text>()) {
                            val text = part.text.take(REPLY_LIMIT - received)
                            if (text.isNotEmpty()) onChunk(text)
                            received += text.length
                            if (received == REPLY_LIMIT) break
                        }
                        received < REPLY_LIMIT
                    }.collect { }
                    if (received == 0) throw IOException("Model returned no text")
                } finally {
                    try { runCatching { conversation.cancelProcess() } } finally { conversation.close() }
                }
            } finally {
                engine.close()
            }
        }
}
