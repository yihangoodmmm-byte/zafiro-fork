package com.niki914.zafiro.app.workbench

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.work.Operation
import androidx.work.WorkInfo
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.zafiro.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectSchedulePanel(project: String) {
    val context = LocalContext.current.applicationContext
    val schedule = remember(context, project) { ProjectSchedule(context, project) }
    val power = remember(context, project) { ProjectPowerTrigger(context, project) }
    val powerJobs by power.work.collectAsState(initial = null)
    var powerRule by remember(project) { mutableStateOf<ProjectPowerRule?>(null) }
    var powerRuns by remember(project) { mutableStateOf(emptyList<ProjectRun>()) }
    var powerCommand by rememberSaveable(project) { mutableStateOf("") }
    var powerCheck by rememberSaveable(project) { mutableStateOf("") }
    var powerArtifact by rememberSaveable(project) { mutableStateOf("") }
    var powerEvent by rememberSaveable(project) { mutableStateOf<ProjectPowerEvent?>(null) }
    var confirmPower by rememberSaveable(project) { mutableStateOf(false) }
    var powerPending by remember(project) { mutableStateOf(false) }
    var powerFailed by remember(project) { mutableStateOf(false) }
    val powerRunning = powerJobs?.any { it.state == WorkInfo.State.RUNNING } == true
    LaunchedEffect(powerJobs, power, powerPending) {
        if (!powerPending) {
            val snapshot = withContext(Dispatchers.IO) { power.rule() to power.history(powerRunning) }
            powerRule = snapshot.first
            powerRuns = snapshot.second
        }
    }
    val jobs by schedule.work.collectAsState(initial = null)
    val active = jobs?.firstOrNull { !it.state.isFinished }
    var command by rememberSaveable(project) { mutableStateOf("") }
    var periodicCheck by rememberSaveable(project) { mutableStateOf("") }
    var periodicArtifact by rememberSaveable(project) { mutableStateOf("") }
    var hours by rememberSaveable(project) { mutableStateOf("24") }
    var confirming by rememberSaveable(project) { mutableStateOf(false) }
    var runs by remember(project) { mutableStateOf(emptyList<ProjectRun>()) }
    LaunchedEffect(jobs, schedule) {
        val running = active?.state == WorkInfo.State.RUNNING
        runs = withContext(Dispatchers.IO) { schedule.history(running) }
    }
    val interval = hours.toLongOrNull()?.takeIf { it in 1..168 }
    val scope = rememberCoroutineScope()
    var pending by remember(project) { mutableStateOf(false) }
    var failed by remember(project) { mutableStateOf(false) }
    fun submit(operation: () -> Operation) {
        if (pending) return
        pending = true
        failed = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { operation().result.get(10, TimeUnit.SECONDS) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                pending = false
            }
        }
    }
    fun submitPower(operation: () -> Unit) {
        if (powerPending) return
        powerPending = true
        powerFailed = false
        scope.launch {
            try {
                powerRule = withContext(Dispatchers.IO) { operation(); power.rule() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                powerFailed = true
            } finally {
                powerPending = false
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            if (jobs == null) return@item
            if (failed) Text(stringResource(R.string.project_schedule_request_failed))
            if (active == null) {
                OutlinedTextField(command, { command = it.take(1024) }, Modifier.fillMaxWidth(), label = {
                    Text(stringResource(R.string.project_command))
                }, singleLine = true)
                ScheduleCheckField(periodicCheck) { periodicCheck = it }
                ScheduleArtifactField(periodicArtifact) { periodicArtifact = it }
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = {
                    Text(stringResource(R.string.project_schedule_hours))
                }, singleLine = true)
                TextButton(onClick = { confirming = true }, enabled = !pending && interval != null && command.isNotBlank() && artifactInputValid(periodicArtifact)) {
                    Text(stringResource(R.string.project_schedule_enable))
                }
            } else {
                Text(stringResource(if (active.state == WorkInfo.State.RUNNING) R.string.project_run_running else R.string.project_schedule_enabled))
                if (active.state == WorkInfo.State.ENQUEUED) {
                    val earliest = active.nextScheduleTimeMillis
                    if (earliest != Long.MAX_VALUE) {
                        Text(stringResource(R.string.project_schedule_earliest,
                            DateFormat.getDateTimeInstance().format(Date(earliest))))
                    }
                }
                TextButton(onClick = { submit(schedule::disable) }, enabled = !pending) { Text(stringResource(R.string.project_schedule_disable)) }
            }
        }
        item {
            Text(stringResource(R.string.project_power_title))
            if (powerFailed) Text(stringResource(R.string.project_schedule_request_failed))
            val rule = powerRule
            if (powerJobs != null && rule == null) {
                FlowRow {
                    ProjectPowerEvent.entries.forEach { event ->
                        FilterChip(
                            selected = powerEvent == event,
                            onClick = { powerEvent = event },
                            label = { Text(projectPowerEventLabel(event)) },
                        )
                    }
                }
                OutlinedTextField(
                    powerCommand, { powerCommand = it.take(1024) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.project_command)) }, singleLine = true,
                )
                ScheduleCheckField(powerCheck) { powerCheck = it }
                ScheduleArtifactField(powerArtifact) { powerArtifact = it }
                TextButton(
                    onClick = { confirmPower = true },
                    enabled = !powerPending && powerEvent != null && powerCommand.isNotBlank() && artifactInputValid(powerArtifact),
                ) { Text(stringResource(R.string.project_power_enable)) }
            } else if (rule != null) {
                Text(projectPowerEventLabel(rule.event))
                Text(rule.command)
                rule.checkCommand?.let { Text(stringResource(R.string.project_schedule_check_summary, it)) }
                rule.artifactPath?.let { Text(stringResource(R.string.project_schedule_artifact_summary, it)) }
                if (powerRunning) Text(stringResource(R.string.project_run_running))
                TextButton(
                    onClick = { submitPower { power.disable().result.get(10, TimeUnit.SECONDS) } },
                    enabled = !powerPending,
                ) { Text(stringResource(R.string.project_schedule_disable)) }
            }
            if (powerRuns.isNotEmpty()) Text(stringResource(R.string.project_power_history))
        }
        items(powerRuns, key = { "power-" + it.id }) { ProjectScheduleRunRow(it) }
        if (runs.isNotEmpty()) item { Text(stringResource(R.string.project_periodic_history)) }
        items(runs, key = { "periodic-" + it.id }) { ProjectScheduleRunRow(it) }
    }
    val selectedEvent = powerEvent
    ConfirmationLiquidDialog(
        visible = confirmPower && selectedEvent != null,
        onDismissRequest = { confirmPower = false },
        title = stringResource(R.string.project_power_enable),
        text = stringResource(R.string.project_power_confirm,
            selectedEvent?.let { projectPowerEventLabel(it) }.orEmpty(), powerCommand) + scheduleCheckSummary(powerCheck) + scheduleArtifactSummary(powerArtifact),
        negativeButtonText = stringResource(android.R.string.cancel),
        positiveButtonText = stringResource(R.string.project_power_enable),
        onNegativeClick = { confirmPower = false },
        onPositiveClick = {
            confirmPower = false
            val selectedCommand = powerCommand
            if (selectedEvent != null && selectedCommand.isNotBlank()) {
                val check = powerCheck.takeIf { it.isNotBlank() }
                val artifact = powerArtifact.takeUnless { it.isBlank() }
                submitPower { power.enable(selectedCommand, selectedEvent, check, artifact) }
            }
        },
    )
    ConfirmationLiquidDialog(
        visible = confirming, onDismissRequest = { confirming = false },
        title = stringResource(R.string.project_schedule_enable),
        text = stringResource(R.string.project_schedule_confirm, command, interval ?: 0L) + scheduleCheckSummary(periodicCheck) + scheduleArtifactSummary(periodicArtifact),
        negativeButtonText = stringResource(android.R.string.cancel),
        positiveButtonText = stringResource(R.string.project_schedule_enable),
        onNegativeClick = { confirming = false },
        onPositiveClick = {
            confirming = false
            val selectedCommand = command
            val check = periodicCheck.takeIf { it.isNotBlank() }
            val artifact = periodicArtifact.takeUnless { it.isBlank() }
            if (interval != null && selectedCommand.isNotBlank()) submit { schedule.enable(selectedCommand, interval, check, artifact) }
        },
    )
}

@Composable
private fun projectPowerEventLabel(event: ProjectPowerEvent): String = stringResource(when (event) {
    ProjectPowerEvent.Connected -> R.string.project_power_connected
    ProjectPowerEvent.Disconnected -> R.string.project_power_disconnected
    ProjectPowerEvent.ScreenOn -> R.string.project_event_screen_on
    ProjectPowerEvent.ScreenOff -> R.string.project_event_screen_off
    ProjectPowerEvent.UserPresent -> R.string.project_event_user_present
    ProjectPowerEvent.BatteryLow -> R.string.project_event_battery_low
    ProjectPowerEvent.BatteryOkay -> R.string.project_event_battery_okay
})

@Composable
private fun ScheduleCheckField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(1024)) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.project_run_check_command)) }, singleLine = true)
}

@Composable
private fun ScheduleArtifactField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(PROJECT_ARTIFACT_PATH_LIMIT)) }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.project_run_artifact_path)) },
        isError = !artifactInputValid(value), singleLine = true)
}

private fun artifactInputValid(value: String): Boolean = value.isBlank() || validProjectArtifactPath(value)

@Composable
private fun scheduleArtifactSummary(value: String): String =
    if (value.isBlank()) "" else "\n" + stringResource(R.string.project_schedule_artifact_summary, value)

@Composable
private fun scheduleCheckSummary(value: String): String =
    if (value.isBlank()) "" else "\n" + stringResource(R.string.project_schedule_check_summary, value)

@Composable
private fun ProjectScheduleRunRow(run: ProjectRun) {
    Text(DateFormat.getDateTimeInstance().format(Date(run.id)))
    Text(run.command)
    Text(run.exitCode?.let { stringResource(R.string.project_schedule_exit, it) } ?: stringResource(when (run.status) {
        ProjectRunStatus.Running -> R.string.project_run_running
        ProjectRunStatus.Interrupted -> R.string.project_run_interrupted
        ProjectRunStatus.Stopped -> R.string.project_run_stopped
        ProjectRunStatus.TimedOut -> R.string.project_run_timeout
        ProjectRunStatus.Failed -> R.string.project_run_failed
        ProjectRunStatus.Finished -> R.string.project_run_finished
    }))
    if (run.status == ProjectRunStatus.Failed && run.exitCode == 0) Text(stringResource(R.string.project_run_failed))
    if (run.output.isNotBlank()) Text(run.output)
    run.checkCommand?.let { check ->
        Text(stringResource(R.string.project_schedule_check_summary, check))
        val code = run.checkExitCode
        Text(when {
            code != null -> stringResource(R.string.project_schedule_exit, code)
            run.checking && run.status == ProjectRunStatus.Running -> stringResource(R.string.project_run_running)
            run.checking -> stringResource(R.string.project_schedule_check_incomplete)
            run.status == ProjectRunStatus.Running -> stringResource(R.string.project_run_check_waiting)
            else -> stringResource(R.string.project_run_check_skipped)
        })
        if (run.checkOutput.isNotBlank()) Text(run.checkOutput)
    }
    ProjectRunArtifactDetails(run)
}
