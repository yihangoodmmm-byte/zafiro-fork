package com.niki914.zafiro.app.workbench

import androidx.compose.foundation.layout.Row
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

@Composable
internal fun ProjectSchedulePanel(project: String) {
    val context = LocalContext.current.applicationContext
    val schedule = remember(context, project) { ProjectSchedule(context, project) }
    val power = remember(context, project) { ProjectPowerTrigger(context, project) }
    val powerJobs by power.work.collectAsState(initial = null)
    var powerRule by remember(project) { mutableStateOf<ProjectPowerRule?>(null) }
    var powerRuns by remember(project) { mutableStateOf(emptyList<ProjectRun>()) }
    var powerCommand by rememberSaveable(project) { mutableStateOf("") }
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
                OutlinedTextField(hours, { hours = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = {
                    Text(stringResource(R.string.project_schedule_hours))
                }, singleLine = true)
                TextButton(onClick = { confirming = true }, enabled = !pending && interval != null && command.isNotBlank()) {
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
                Row {
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
                TextButton(
                    onClick = { confirmPower = true },
                    enabled = !powerPending && powerEvent != null && powerCommand.isNotBlank(),
                ) { Text(stringResource(R.string.project_power_enable)) }
            } else if (rule != null) {
                Text(projectPowerEventLabel(rule.event))
                Text(rule.command)
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
            selectedEvent?.let { projectPowerEventLabel(it) }.orEmpty(), powerCommand),
        negativeButtonText = stringResource(android.R.string.cancel),
        positiveButtonText = stringResource(R.string.project_power_enable),
        onNegativeClick = { confirmPower = false },
        onPositiveClick = {
            confirmPower = false
            val selectedCommand = powerCommand
            if (selectedEvent != null && selectedCommand.isNotBlank()) {
                submitPower { power.enable(selectedCommand, selectedEvent) }
            }
        },
    )
    ConfirmationLiquidDialog(
        visible = confirming, onDismissRequest = { confirming = false },
        title = stringResource(R.string.project_schedule_enable),
        text = stringResource(R.string.project_schedule_confirm, command, interval ?: 0L),
        negativeButtonText = stringResource(android.R.string.cancel),
        positiveButtonText = stringResource(R.string.project_schedule_enable),
        onNegativeClick = { confirming = false },
        onPositiveClick = {
            confirming = false
            val selectedCommand = command
            if (interval != null && selectedCommand.isNotBlank()) submit { schedule.enable(selectedCommand, interval) }
        },
    )
}

@Composable
private fun projectPowerEventLabel(event: ProjectPowerEvent): String = stringResource(when (event) {
    ProjectPowerEvent.Connected -> R.string.project_power_connected
    ProjectPowerEvent.Disconnected -> R.string.project_power_disconnected
})

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
    if (run.output.isNotBlank()) Text(run.output)
}
