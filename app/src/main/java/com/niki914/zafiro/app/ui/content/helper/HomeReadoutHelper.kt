package com.niki914.zafiro.app.ui.content.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID

internal class HomeReadoutController(context: Context, private val onFailure: () -> Unit) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var generation = 0
    private var pending: Pair<String, String>? = null
    private var lastUtterance: String? = null

    fun speak(text: String, onDone: () -> Unit) {
        stop()
        val id = UUID.randomUUID().toString()
        pending = id to text
        if (ready) {
            play(onDone)
        } else {
            val token = ++generation
            engine?.shutdown()
            engine = TextToSpeech(appContext) { status ->
                main.post {
                    if (token == generation) {
                        if (status == TextToSpeech.SUCCESS) {
                            ready = true
                            play(onDone)
                        } else {
                            close()
                            onFailure()
                        }
                    }
                }
            }
        }
    }

    private fun play(onDone: () -> Unit) {
        val tts = engine ?: return
        val (id, text) = pending ?: return
        pending = null
        val limit = TextToSpeech.getMaxSpeechInputLength()
        val chunks = buildList {
            var start = 0
            while (start < text.length) {
                var end = minOf(start + limit, text.length)
                if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
                add(text.substring(start, end))
                start = end
            }
        }
        lastUtterance = "$id-${chunks.lastIndex}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                main.post {
                    if (utteranceId != null && utteranceId == lastUtterance) {
                        lastUtterance = null
                        onDone()
                    }
                }
            }
            @Deprecated("Framework callback")
            override fun onError(utteranceId: String?) {
                main.post {
                    if (utteranceId?.startsWith("$id-") == true && lastUtterance?.startsWith("$id-") == true) {
                        stop()
                        onFailure()
                    }
                }
            }
        })
        chunks.forEachIndexed { index, chunk ->
            val queue = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(chunk, queue, null, "$id-$index") != TextToSpeech.SUCCESS) {
                stop()
                onFailure()
                return
            }
        }
    }

    fun stop() {
        pending = null
        lastUtterance = null
        if (ready) engine?.stop() else close()
    }

    fun close() {
        generation++
        pending = null
        lastUtterance = null
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
