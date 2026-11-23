package com.niki914.zafiro.app.ui.content.composer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage

private val ControlSize = 48.dp

@Composable
fun LiquidChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    contextProgress: Float? = null,
    pendingImages: List<HomeChatImage> = emptyList(),
    pendingFiles: List<HomeChatFile> = emptyList(),
    onAttachImageClick: () -> Unit = {},
    backdrop: Backdrop? = null,
    workspacePath: String? = null,
    isResolvingWorkspace: Boolean = false,
    onWorkspaceClick: () -> Unit = {},
    taskOptionsActive: Boolean = false,
    onTaskOptionsClick: () -> Unit = {},
    sendAvailable: Boolean = true,
    reasoningEffortLabel: String? = null,
    reasoningEffortShortLabel: String? = null,
    onReasoningEffortClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val workspaceTitle = stringResource(R.string.ui_home_workspace_title)
    val workspaceState = when {
        isResolvingWorkspace -> stringResource(R.string.ui_home_workspace_busy)
        workspacePath != null -> workspacePath
        else -> stringResource(R.string.ui_home_workspace_none)
    }
    val workspaceLabel = when {
        isResolvingWorkspace -> stringResource(R.string.ui_home_workspace_binding_short)
        workspacePath != null -> workspacePath.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        else -> workspaceTitle
    }
    val taskOptionsTitle = stringResource(
        if (taskOptionsActive) R.string.ui_home_task_options_active else R.string.ui_home_task_options,
    )
    val canSend = sendAvailable && !isResolvingWorkspace && (value.isNotBlank() || pendingImages.isNotEmpty() || pendingFiles.isNotEmpty())
    val ringColor = when {
        isGenerating -> colors.onError
        canSend -> colors.onPrimary
        else -> colors.onSurfaceVariant
    }

    // Fixed action row: typing and IME changes do not move the send/stop target.
    LiquidTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(R.string.ui_home_input_placeholder),
        singleLine = false,
        maxLines = 5,
        contentVerticalAlignment = Alignment.Top,
        minHeight = 100.dp,
        expandedLayout = true,
        expandedActionsRow = {
            IconButton(onClick = onAttachImageClick, modifier = Modifier.size(ControlSize)) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.ui_home_attach_sheet_title))
            }
            TextButton(
                onClick = onWorkspaceClick,
                modifier = Modifier
                    .weight(1f)
                    .semantics { stateDescription = workspaceState },
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) {
                Icon(Icons.Default.Folder, contentDescription = workspaceTitle, modifier = Modifier.size(18.dp))
                Text(
                    text = workspaceLabel,
                    modifier = Modifier.padding(start = 4.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onTaskOptionsClick, modifier = Modifier.size(ControlSize)) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = taskOptionsTitle,
                    tint = if (taskOptionsActive) colors.primary else colors.onSurfaceVariant,
                )
            }
            if (reasoningEffortLabel != null) {
                val title = stringResource(R.string.ui_home_reasoning_effort)
                IconButton(
                    onClick = onReasoningEffortClick,
                    modifier = Modifier.size(ControlSize).semantics {
                        contentDescription = title
                        stateDescription = reasoningEffortLabel
                    },
                ) {
                    if (reasoningEffortShortLabel == null) Icon(Icons.Default.Psychology, contentDescription = null)
                    else Text(reasoningEffortShortLabel, color = colors.primary, maxLines = 1,
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            FilledIconButton(
                onClick = if (isGenerating) onStopClick else onSendClick,
                enabled = isGenerating || canSend,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isGenerating) colors.error else colors.primary,
                    contentColor = if (isGenerating) colors.onError else colors.onPrimary,
                ),
                modifier = Modifier.size(ControlSize).drawWithContent {
                    drawContent()
                    if (contextProgress != null && contextProgress.isFinite()) {
                        drawContextRing(contextProgress, ringColor)
                    }
                },
            ) {
                if (isGenerating) {
                    Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.ui_home_stop_action))
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = stringResource(R.string.ui_home_send_action),
                    )
                }
            }
        },
        backdrop = backdrop,
        glassStyle = LiquidGlassPresets.ComposerBar,
        modifier = modifier.fillMaxWidth(),
    )
}

private fun DrawScope.drawContextRing(progress: Float, color: Color) {
    val stroke = 2.dp.toPx()
    val diameter = (size.minDimension - stroke * 2).coerceAtLeast(0f)
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val ringSize = Size(diameter, diameter)
    drawArc(color.copy(alpha = 0.24f), -90f, 360f, false, topLeft, ringSize, style = Stroke(stroke))
    if (progress > 0f) {
        drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}
