package com.niki914.zafiro.app.ui.chat.composable.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.agentic.WorkflowPlanReport
import com.niki914.zafiro.chat.agentic.WorkflowPlanReport.CheckStatus
import com.niki914.zafiro.chat.agentic.WorkflowPlanReport.Phase
import com.niki914.zafiro.chat.agentic.WorkflowPlanReport.StepStatus

@Composable
internal fun WorkflowReportCard(report: WorkflowPlanReport, active: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val phaseRes = when (report.phase) {
        Phase.Planning -> R.string.ui_home_workflow_planning
        Phase.Executing -> R.string.ui_home_workflow_executing
        Phase.Verifying -> R.string.ui_home_workflow_verifying
        Phase.Completed -> R.string.ui_home_workflow_completed
        Phase.Blocked -> R.string.ui_home_workflow_blocked
    }
    val phase = stringResource(phaseRes)
    val stage = if (!active && !report.phase.isFinished) {
        stringResource(R.string.ui_home_workflow_unfinished, phase)
    } else phase
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = G2FieldShape(20.dp),
        color = colors.surfaceContainerLow,
        contentColor = colors.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(report.goal, style = typography.titleSmall, maxLines = if (expanded) 5 else 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(R.string.ui_home_workflow_report_title, stage),
                style = typography.labelMedium,
                color = if (report.phase == Phase.Blocked) colors.error else colors.primary,
            )
            Text(
                text = stringResource(
                    R.string.ui_home_workflow_counts,
                    report.steps.count { it.status == StepStatus.Completed }, report.steps.size,
                    report.checks.count { it.status == CheckStatus.Passed }, report.checks.size,
                ),
                style = typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            if (report.summary.isNotBlank()) {
                Text(report.summary, style = typography.bodyMedium, maxLines = if (expanded) 10 else 3, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) {
                Text(stringResource(R.string.ui_home_workflow_steps), style = typography.labelLarge)
                report.steps.forEach { step ->
                    val res = when (step.status) {
                        StepStatus.Pending -> R.string.ui_home_workflow_pending
                        StepStatus.InProgress -> R.string.ui_home_workflow_executing
                        StepStatus.Completed -> R.string.ui_home_workflow_step_done
                        StepStatus.Blocked -> R.string.ui_home_workflow_blocked
                    }
                    WorkflowReportRow(step.title, stringResource(res), step.evidence)
                }
                Text(stringResource(R.string.ui_home_workflow_checks), style = typography.labelLarge)
                report.checks.forEach { check ->
                    val res = when (check.status) {
                        CheckStatus.Pending -> R.string.ui_home_workflow_pending
                        CheckStatus.Passed -> R.string.ui_home_workflow_passed
                        CheckStatus.Failed -> R.string.ui_home_workflow_failed
                        CheckStatus.Skipped -> R.string.ui_home_workflow_skipped
                    }
                    WorkflowReportRow(check.title, stringResource(res), check.evidence)
                }
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.ui_home_workflow_hide else R.string.ui_home_workflow_details))
            }
        }
    }
}

@Composable
private fun WorkflowReportRow(title: String, status: String, evidence: String) {
    Column(Modifier.padding(start = 8.dp)) {
        Text(stringResource(R.string.ui_home_workflow_row, status, title), style = MaterialTheme.typography.bodyMedium)
        if (evidence.isNotBlank()) {
            Text(evidence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
