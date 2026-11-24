package com.niki914.zafiro.app.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
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
import com.niki914.zafiro.repo.SavedLlmConfig

internal data class DiscussionLine(val role: String?, val text: String)
internal data class DiscussionState(
    val roles: List<SavedLlmConfig> = emptyList(),
    val selected: List<String> = emptyList(),
    val lines: List<DiscussionLine> = emptyList(),
    val input: String = "",
    val busy: Boolean = false,
    val active: String? = null,
    val failed: Boolean = false,
)

internal sealed interface DiscussionAction {
    data object Refresh : DiscussionAction
    data object Send : DiscussionAction
    data object Stop : DiscussionAction
    data object Reset : DiscussionAction
    data class Select(val id: String) : DiscussionAction
    data class Input(val text: String) : DiscussionAction
}

@Composable
internal fun ProjectRoleDiscussion(state: DiscussionState, send: (DiscussionAction) -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { send(DiscussionAction.Refresh) }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.roles.size < 2) item { Text(stringResource(R.string.project_discussion_no_roles)) }
        items(state.roles, key = { it.id }) { role ->
            val order = state.selected.indexOf(role.id) + 1
            FilterChip(selected = order > 0, onClick = { send(DiscussionAction.Select(role.id)) },
                enabled = !state.busy && (order > 0 || state.selected.size < 4),
                label = { Text(if (order > 0) "$order. "+role.name else role.name) })
        }
        items(state.lines) { line ->
            Text(line.role ?: stringResource(R.string.project_discussion_you), style = MaterialTheme.typography.labelLarge)
            Text(line.text)
        }
        item {
            state.active?.let { Text(stringResource(R.string.project_discussion_active, it)) }
            if (state.failed) Text(stringResource(R.string.project_discussion_failed), color = MaterialTheme.colorScheme.error)
            OutlinedTextField(state.input, { send(DiscussionAction.Input(it)) }, Modifier.fillMaxWidth(),
                enabled = !state.busy, label = { Text(stringResource(R.string.project_discussion_prompt)) })
            Row {
                TextButton(onClick = { send(DiscussionAction.Send) }, enabled = !state.busy && state.selected.size >= 2 && state.input.isNotBlank()) {
                    Text(stringResource(R.string.project_discussion_send))
                }
                if (state.busy) TextButton(onClick = { send(DiscussionAction.Stop) }) { Text(stringResource(android.R.string.cancel)) }
                else TextButton(onClick = { send(DiscussionAction.Reset) }, enabled = state.lines.isNotEmpty()) {
                    Text(stringResource(R.string.project_discussion_reset))
                }
            }
        }
    }
}
