package com.niki914.zafiro.app.workbench

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R

internal data class OfflineChatState(
    val installed: Boolean = false,
    val busy: Boolean = false,
    val generating: Boolean = false,
    val prompt: String = "",
    val reply: String = "",
    val error: Int? = null,
)

internal sealed interface OfflineChatAction {
    data class Refresh(val app: Application) : OfflineChatAction
    data class Import(val app: Application, val uri: Uri) : OfflineChatAction
    data class Remove(val app: Application) : OfflineChatAction
    data class Generate(val app: Application) : OfflineChatAction
    data class Prompt(val text: String) : OfflineChatAction
    data object Stop : OfflineChatAction
}

@Composable
internal fun ProjectOfflineChatPanel(
    app: Application,
    state: OfflineChatState,
    send: (OfflineChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { send(OfflineChatAction.Import(app, it)) }
    }
    LaunchedEffect(app) { send(OfflineChatAction.Refresh(app)) }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(stringResource(if (state.installed) R.string.project_offline_installed else R.string.project_offline_missing))
            Row {
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !state.busy) {
                    Text(stringResource(R.string.project_offline_import))
                }
                if (state.installed) TextButton(onClick = { send(OfflineChatAction.Remove(app)) }, enabled = !state.busy) {
                    Text(stringResource(R.string.project_offline_remove))
                }
            }
        }
        item {
            OutlinedTextField(state.prompt, { send(OfflineChatAction.Prompt(it)) }, Modifier.fillMaxWidth(),
                enabled = !state.busy, label = { Text(stringResource(R.string.project_offline_prompt)) }, maxLines = 5)
            Row {
                TextButton(onClick = { send(OfflineChatAction.Generate(app)) },
                    enabled = state.installed && !state.busy && state.prompt.isNotBlank()) {
                    Text(stringResource(R.string.project_offline_generate))
                }
                if (state.busy) TextButton(onClick = { send(OfflineChatAction.Stop) }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
            if (state.busy) Text(stringResource(if (state.generating) R.string.project_offline_generating else R.string.project_offline_working))
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        if (state.reply.isNotEmpty()) item { SelectionContainer { Text(state.reply) } }
    }
}
