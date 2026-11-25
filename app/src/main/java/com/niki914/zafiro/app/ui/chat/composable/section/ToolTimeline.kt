package com.niki914.zafiro.app.ui.chat.composable.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.chat.composable.body.ActionBodyDispatcher
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel

@Composable
internal fun ToolTimeline(actions: List<ActionUiModel>, active: Boolean) {
    if (actions.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val current = actions.lastOrNull { active && it.isRunning }
        ?: actions.lastOrNull { it.timelineStatus(active) != R.string.tool_timeline_returned }
        ?: actions.last()
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text = stringResource(R.string.tool_timeline_highlight, stringResource(current.timelineStatus(active)), current.displayTitle()),
            style = MaterialTheme.typography.bodyMedium,
            color = if (current.isTimelineFailure()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(
                R.string.tool_timeline_summary,
                actions.size,
                if (active) actions.count { it.isRunning } else 0,
                actions.count { it.isTimelineFailure() },
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.tool_timeline_hide else R.string.tool_timeline_show))
            }
        }
        if (expanded) {
            val first = if (showAll) 0 else (actions.size - 20).coerceAtLeast(0)
            if (first > 0) {
                TextButton(onClick = { showAll = true }) {
                    Text(stringResource(R.string.tool_timeline_show_earlier, first))
                }
            }
            for (index in first until actions.size) {
                val action = actions[index]
                key(action.key) {
                    val name = action.displayTitle()
                    val status = stringResource(action.timelineStatus(active))
                    TextButton(onClick = { selected = if (selected == action.key) null else action.key }) {
                        Text(
                            stringResource(R.string.tool_timeline_entry, index + 1, status, name),
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    action.stepCheckRes?.let { checkRes ->
                        Text(
                            text = stringResource(checkRes),
                            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (action.isTimelineFailure()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected == action.key) {
                        ActionBodyDispatcher(action.body, Modifier.fillMaxWidth().padding(start = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionUiModel.displayTitle(): String = titleRes?.let { stringResource(it) } ?: title.orEmpty()

private fun ActionUiModel.isTimelineFailure(): Boolean = !isInterrupted && (isFailed || stepCheckRes in setOf(
    R.string.tool_step_mismatch, R.string.tool_step_command_nonzero,
    R.string.tool_step_python_failed, R.string.tool_step_python_timeout,
))

private fun ActionUiModel.timelineStatus(active: Boolean): Int = when {
    isInterrupted -> R.string.tool_timeline_stopped
    isTimelineFailure() -> R.string.ui_home_workflow_failed
    isRunning && active -> R.string.ui_home_workflow_executing
    stepCheckRes == R.string.tool_step_command_pending && active -> R.string.tool_timeline_waiting_result
    isRunning || stepCheckRes == R.string.tool_step_command_pending || stepCheckRes == R.string.tool_step_unavailable ->
        R.string.tool_timeline_unconfirmed
    else -> R.string.tool_timeline_returned
}
