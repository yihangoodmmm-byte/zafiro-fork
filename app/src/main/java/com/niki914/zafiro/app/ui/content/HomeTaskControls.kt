package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.zafiro.api.model.ChatReasoningEffort
import com.niki914.zafiro.api.model.ChatTurnOptions
import com.niki914.zafiro.app.R

@Composable
internal fun HomeWorkspaceSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    path: String?,
    resolving: Boolean,
    onPickDirectory: () -> Unit,
    onEnterPath: () -> Unit,
    onClear: () -> Unit,
) {
    OptionSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_home_workspace_title),
        bodyModifier = Modifier.verticalScroll(rememberScrollState()),
        header = {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp)) {
                SelectionContainer {
                    Text(
                        text = path ?: stringResource(R.string.ui_home_workspace_none),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Text(
                    text = stringResource(if (resolving) R.string.ui_home_workspace_busy else R.string.ui_home_workspace_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
    ) { dismissThen ->
        if (!resolving) {
            OptionRow(
                title = stringResource(if (path == null) R.string.ui_home_workspace_pick else R.string.ui_home_workspace_change),
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                onClick = { dismissThen(onPickDirectory) },
            )
            OptionRow(
                title = stringResource(R.string.ui_home_workspace_enter_path),
                onClick = { dismissThen(onEnterPath) },
            )
            if (path != null) {
                OptionRow(
                    title = stringResource(R.string.ui_home_workspace_clear),
                    leadingContent = { Icon(Icons.Default.Close, contentDescription = null) },
                    onClick = { dismissThen(onClear) },
                )
            }
        }
    }
}

@Composable
internal fun HomeTaskOptionsSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    options: ChatTurnOptions,
    onToggleWorkflow: () -> Unit,
    onToggleReasoningGuide: () -> Unit,
    onSelectReasoningEffort: (ChatReasoningEffort?) -> Unit,
) {
    var showEffortDialog by rememberSaveable { mutableStateOf(false) }
    val optionsTitle = stringResource(R.string.ui_home_task_options)
    val effortTitle = stringResource(R.string.ui_home_reasoning_effort)
    val labels = mapOf(
        null to stringResource(R.string.ui_home_reasoning_effort_follow),
        ChatReasoningEffort.Low to stringResource(R.string.ui_home_reasoning_effort_low),
        ChatReasoningEffort.Medium to stringResource(R.string.ui_home_reasoning_effort_medium),
        ChatReasoningEffort.High to stringResource(R.string.ui_home_reasoning_effort_high),
    )
    OptionSheet(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = optionsTitle,
        bodyModifier = Modifier.verticalScroll(rememberScrollState()),
        header = {
            Text(
                text = stringResource(R.string.ui_home_task_options_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
        },
    ) { dismissThen ->
        OptionRow(
            title = stringResource(R.string.ui_home_workflow_toggle),
            checked = options.workflowEnabled,
            modifier = Modifier.semantics { selected = options.workflowEnabled },
            onClick = onToggleWorkflow,
        )
        OptionRow(
            title = stringResource(R.string.ui_home_reasoning_guide),
            checked = options.reasoningGuideEnabled,
            modifier = Modifier.semantics { selected = options.reasoningGuideEnabled },
            onClick = onToggleReasoningGuide,
        )
        OptionRow(
            title = stringResource(R.string.ui_home_reasoning_effort_label, labels.getValue(options.reasoningEffort)),
            onClick = { dismissThen { showEffortDialog = true } },
        )
    }
    SingleChoiceLiquidDialog(
        visible = showEffortDialog,
        onDismissRequest = { showEffortDialog = false },
        title = effortTitle,
        hint = stringResource(R.string.ui_home_reasoning_effort_hint),
        options = labels.keys.toList(),
        selectedId = options.reasoningEffort?.wireValue.orEmpty(),
        optionId = { it?.wireValue.orEmpty() },
        optionLabel = { labels.getValue(it) },
        onSelect = {
            showEffortDialog = false
            onSelectReasoningEffort(it)
        },
    )
}
