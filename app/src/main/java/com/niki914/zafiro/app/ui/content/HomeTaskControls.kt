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
    onOpenReasoningEffort: () -> Unit,
) {
    val optionsTitle = stringResource(R.string.ui_home_task_options)
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
            title = stringResource(R.string.ui_home_reasoning_effort_label, homeReasoningEffortLabel(options.reasoningEffort)),
            onClick = { dismissThen(onOpenReasoningEffort) },
        )
    }
}

@Composable
internal fun homeReasoningEffortLabel(effort: ChatReasoningEffort?): String = stringResource(when (effort) {
    null -> R.string.ui_home_reasoning_effort_follow
    ChatReasoningEffort.Low -> R.string.ui_home_reasoning_effort_low
    ChatReasoningEffort.Medium -> R.string.ui_home_reasoning_effort_medium
    ChatReasoningEffort.High -> R.string.ui_home_reasoning_effort_high
})

@Composable
internal fun HomeReasoningEffortDialog(
    visible: Boolean,
    effort: ChatReasoningEffort?,
    onDismiss: () -> Unit,
    onSelect: (ChatReasoningEffort?) -> Unit,
) {
    val choices = listOf(null, ChatReasoningEffort.Low, ChatReasoningEffort.Medium, ChatReasoningEffort.High)
    val labels = choices.map { homeReasoningEffortLabel(it) }
    SingleChoiceLiquidDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_home_reasoning_effort),
        hint = stringResource(R.string.ui_home_reasoning_effort_hint),
        options = choices,
        selectedId = effort?.wireValue.orEmpty(),
        optionId = { it?.wireValue.orEmpty() },
        optionLabel = { labels[choices.indexOf(it)] },
        onSelect = onSelect,
    )
}
