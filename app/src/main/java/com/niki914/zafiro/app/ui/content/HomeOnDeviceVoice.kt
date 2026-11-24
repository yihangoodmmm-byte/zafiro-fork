package com.niki914.zafiro.app.ui.content

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.zafiro.app.R
import kotlinx.coroutines.delay

@Composable
internal fun homeOnDeviceVoiceAction(
    conversationId: String?,
    canStart: Boolean,
    onPhrase: (String) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val owner = LocalView.current.findViewTreeLifecycleOwner()
    val latestPhrase by rememberUpdatedState(onPhrase)
    var permissionTarget by remember { mutableStateOf<Any?>(null) }
    val target = remember(conversationId, canStart) { Any() }
    var recording by remember(target) { mutableStateOf(false) }
    var finishing by remember(target) { mutableStateOf(false) }
    var engine by remember(target) { mutableStateOf<SpeechRecognizer?>(null) }
    fun notice(id: Int) = Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    fun start() {
        if (canStart && owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            finishing = false
            recording = true
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = permissionTarget
        permissionTarget = null
        if (requested === target && canStart) {
            if (granted) start() else notice(R.string.ui_voice_permission_denied)
        }
    }

    DisposableEffect(owner, target) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) recording = false
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }
    DisposableEffect(recording, target) {
        var recognizer: SpeechRecognizer? = null
        if (recording && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val current = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = current
                engine = current
                current.setRecognitionListener(object : RecognitionListener {
                    private fun finish(action: () -> Unit) {
                        if (engine !== current || !recording ||
                            owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true
                        ) return
                        recording = false
                        action()
                    }
                    override fun onResults(results: Bundle?) = finish {
                        val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim()?.take(4000).orEmpty()
                        if (phrase.isBlank()) notice(R.string.ui_voice_no_match) else latestPhrase(phrase)
                    }
                    override fun onError(error: Int) = finish {
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.ui_voice_no_match
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> R.string.ui_voice_language_unavailable
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.ui_voice_permission_denied
                            else -> R.string.ui_voice_local_failed
                        }
                        notice(message)
                    }
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() { if (engine === current && recording) finishing = true }
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
                current.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
                )
            } catch (_: RuntimeException) {
                recording = false
                notice(R.string.ui_voice_local_failed)
            }
        }
        onDispose {
            if (engine === recognizer) engine = null
            recognizer?.destroy()
        }
    }
    LaunchedEffect(recording, target, finishing) {
        if (recording) {
            delay(if (finishing) 10_000L else 30_000L)
            recording = false
            notice(R.string.ui_voice_local_timeout)
        }
    }
    ConfirmationLiquidDialog(
        visible = recording,
        onDismissRequest = { recording = false },
        title = stringResource(R.string.ui_voice_local_input),
        text = stringResource(if (finishing) R.string.ui_voice_processing else R.string.ui_voice_listening),
        negativeButtonText = stringResource(R.string.dialog_cancel),
        positiveButtonText = stringResource(R.string.ui_voice_finish),
        onNegativeClick = { recording = false },
        onPositiveClick = {
            if (!finishing) {
                finishing = true
                engine?.stopListening()
            }
        },
    )
    return {
        if (canStart && !recording && permissionTarget == null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            ) {
                notice(R.string.ui_voice_local_unavailable)
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                start()
            } else {
                permissionTarget = target
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}
