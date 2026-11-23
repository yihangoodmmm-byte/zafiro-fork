package com.niki914.zafiro.app.workbench

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.zafiro.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun DesktopCodexPanel(modifier: Modifier = Modifier) {
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var active by remember { mutableStateOf<CodexAction?>(null) }
    var stopping by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<CodexAction?>(null) }
    var reply by remember { mutableStateOf<CodexReply?>(null) }
    val scope = rememberCoroutineScope()
    val configured = DesktopCodexBridge.accepts(endpoint) && token.isNotBlank() && token.all { it in '!'..'~' }
    val editable = active == null && !stopping
    fun submit(action: CodexAction) {
        if (!configured || stopping || (active != null && action != CodexAction.Stop)) return
        if (action == CodexAction.Stop) stopping = true else active = action
        val address = endpoint
        val secret = token
        val directory = cwd.trim()
        val input = prompt
        scope.launch {
            try {
                reply = DesktopCodexBridge.request(address, secret, action, directory, input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                reply = CodexReply(false)
            } finally {
                if (action == CodexAction.Stop) stopping = false else active = null
            }
        }
    }
    LazyColumn(modifier.fillMaxSize()) {
        item {
            OutlinedTextField(endpoint, { endpoint = it.take(2048) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.codex_endpoint)) })
            OutlinedTextField(token, { token = it.take(2048) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.codex_token)) }, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(cwd, { cwd = it.take(2048) }, Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
                label = { Text(stringResource(R.string.codex_cwd)) })
            OutlinedTextField(prompt, { prompt = it.take(16000) }, Modifier.fillMaxWidth(), enabled = editable, maxLines = 6,
                label = { Text(stringResource(R.string.codex_prompt)) })
            Row {
                TextButton(onClick = { submit(CodexAction.Health) }, enabled = configured && editable) { Text(stringResource(R.string.codex_health)) }
                TextButton(onClick = { confirm = CodexAction.Run }, enabled = configured && editable && cwd.isNotBlank() && prompt.isNotBlank()) {
                    Text(stringResource(R.string.project_run))
                }
                TextButton(onClick = { confirm = CodexAction.Stop }, enabled = configured && !stopping && active != CodexAction.Health) {
                    Text(stringResource(R.string.project_run_stop))
                }
            }
            if (active != null || stopping) Text(stringResource(R.string.codex_wait))
            reply?.let { result ->
                val status = result.httpStatus
                Text(if (status != null) stringResource(R.string.codex_http, status)
                    else stringResource(if (result.ok) R.string.codex_ok else R.string.codex_failed))
                if (result.output.isNotBlank()) SelectionContainer { Text(result.output) }
            }
        }
    }
    val action = confirm
    if (action != null) {
        val run = action == CodexAction.Run
        val title = stringResource(if (run) R.string.project_run else R.string.project_run_stop)
        ConfirmationLiquidDialog(
            visible = true, onDismissRequest = { confirm = null }, title = title,
            text = stringResource(if (run) R.string.codex_confirm_run else R.string.codex_confirm_stop),
            negativeButtonText = stringResource(android.R.string.cancel), positiveButtonText = title,
            onNegativeClick = { confirm = null }, onPositiveClick = { confirm = null; submit(action) },
        )
    }
}
