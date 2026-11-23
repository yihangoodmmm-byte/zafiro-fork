package com.niki914.zafiro.app.workbench

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.content.InputLiquidDialog
import com.niki914.zafiro.chat.agentic.WorkspaceTurnReviews
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProjectWorkbenchContent(
    onOpenBrowser: () -> Unit,
    onBindChat: ((String) -> Unit)?,
    onAddReference: ((String) -> Unit)?,
    openRuns: Boolean,
    viewModel: ProjectWorkbenchModel,
) {
    val app = LocalContext.current.applicationContext as Application
    val state by viewModel.uiStateFlow.collectAsState()
    val send = viewModel::sendIntent
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val instructionStore = remember(app) { app.getSharedPreferences("project_instructions", 0) }
    var savedInstructions by remember(state.project) {
        mutableStateOf(state.project?.let { instructionStore.getString(it, null) }.orEmpty())
    }
    var editingInstructions by remember(state.project) { mutableStateOf(false) }
    var projectInstructions by remember(state.project) { mutableStateOf("") }
    var preview by remember(state.project, state.documentOpenId, state.document?.relativePath) { mutableStateOf(false) }
    var reviewing by remember(state.project) { mutableStateOf(false) }
    var scheduling by remember(state.project) { mutableStateOf(false) }
    var inspectingDevice by rememberSaveable { mutableStateOf(false) }
    var desktopCodex by remember { mutableStateOf(false) }
    var localPreview by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { send(ProjectIntent.OpenProject(it.toString(), app)) }
    }
    LaunchedEffect(app, openRuns) {
        send(ProjectIntent.Restore(app))
        send(if (openRuns) ProjectIntent.ShowRuns else ProjectIntent.ShowFiles)
    }
    BackHandler((state.document != null || state.gitVisible || state.runsVisible || state.terminalVisible || reviewing || inspectingDevice || scheduling || desktopCodex || localPreview) && !confirmDiscard) {
        when {
            localPreview -> localPreview = false
            desktopCodex -> desktopCodex = false
            inspectingDevice -> inspectingDevice = false
            scheduling -> scheduling = false
            reviewing -> reviewing = false
            preview -> preview = false
            state.gitDiff != null -> send(ProjectIntent.ShowGit)
            state.gitVisible || state.runsVisible || state.terminalVisible -> send(ProjectIntent.ShowFiles)
            state.dirty -> confirmDiscard = true
            else -> send(ProjectIntent.CancelEdit)
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = liquidScreenTopPadding(10.dp))) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.input, { send(ProjectIntent.Input(it)) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.project_path)) }, singleLine = true)
            Button(onClick = { send(ProjectIntent.OpenProject(state.input, app)) },
                enabled = state.input.isNotBlank() && !state.dirty) { Text(stringResource(R.string.project_open)) }
        }
        state.project?.let { Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { picker.launch(null) }, enabled = !state.dirty) { Text(stringResource(R.string.project_pick)) }
            TextButton(onClick = onOpenBrowser) { Text(stringResource(R.string.browser_title)) }
            TextButton(onClick = {
                inspectingDevice = false; desktopCodex = false; localPreview = !localPreview
            }) { Text(stringResource(R.string.project_local_preview)) }
            TextButton(onClick = { localPreview = false; desktopCodex = false; inspectingDevice = !inspectingDevice }) { Text(stringResource(R.string.project_device)) }
            TextButton(onClick = { localPreview = false; inspectingDevice = false; desktopCodex = !desktopCodex }) { Text(stringResource(R.string.codex_title)) }
            state.project?.let { path ->
                TextButton(onClick = {
                    projectInstructions = savedInstructions
                    editingInstructions = true
                }) { Text(stringResource(R.string.project_instructions)) }
                val saved = savedInstructions
                if (onAddReference != null && saved.isNotBlank()) {
                    val context = stringResource(R.string.project_instructions_chat, path, saved)
                    TextButton(onClick = { onAddReference(context) }, enabled = !state.dirty) {
                        Text(stringResource(R.string.project_instructions_insert))
                    }
                }
            }
            if (onBindChat != null) state.project?.let { path ->
                TextButton(onClick = { onBindChat(path) }, enabled = !state.dirty) { Text(stringResource(R.string.project_chat)) }
            }
        }
        state.problem?.let { problem ->
            val text = when (problem) {
                ProjectFileProblem.InvalidDirectory -> R.string.project_invalid
                ProjectFileProblem.Inaccessible -> R.string.project_inaccessible
                ProjectFileProblem.OutsideProject -> R.string.project_outside
                ProjectFileProblem.TooLarge -> R.string.project_large
                ProjectFileProblem.NotText -> R.string.project_not_text
                ProjectFileProblem.Changed -> R.string.project_changed
                ProjectFileProblem.GitIdentity -> R.string.project_git_identity
                ProjectFileProblem.GitEmptyIndex -> R.string.project_git_empty_index
                ProjectFileProblem.RunHistory -> R.string.project_run_history_error
                ProjectFileProblem.RestoreBusy -> R.string.project_restore_busy
                ProjectFileProblem.AlreadyExists -> R.string.project_entry_exists
            }
            Text(stringResource(text), color = MaterialTheme.colorScheme.error)
        }
        if (reviewing) state.restoredFile?.let { Text(stringResource(R.string.project_restored, it)) }
        if (!localPreview && !inspectingDevice && !desktopCodex && state.project != null) Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { scheduling = false; reviewing = false; send(ProjectIntent.ShowFiles) }) { Text(stringResource(R.string.project_files)) }
            TextButton(onClick = { scheduling = false; reviewing = false; send(ProjectIntent.ShowGit) }) { Text(stringResource(R.string.project_git)) }
            TextButton(onClick = { scheduling = false; reviewing = false; send(ProjectIntent.ShowRuns) }) { Text(stringResource(R.string.project_runs)) }
            TextButton(onClick = { scheduling = false; reviewing = false; send(ProjectIntent.ShowTerminal) }) { Text(stringResource(R.string.project_terminal)) }
            TextButton(onClick = { scheduling = false; reviewing = true }) { Text(stringResource(R.string.project_reviews)) }
            TextButton(onClick = { reviewing = false; scheduling = true }) { Text(stringResource(R.string.project_schedule)) }
        }
        if (localPreview) ProjectLocalPreview(Modifier.weight(1f))
        else if (desktopCodex) DesktopCodexPanel(Modifier.weight(1f))
        else if (inspectingDevice) ProjectDevicePanel(Modifier.weight(1f))
        else if (state.project != null) BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 720.dp
            val project = state.project
            val document = state.document
            if (scheduling && project != null) ProjectSchedulePanel(project)
            else if (reviewing && project != null) ProjectReviewPanel(project, state.dirty, send)
            else if (state.terminalVisible) ProjectTerminalPane(state, send)
            else if (preview && !state.runsVisible && !state.gitVisible && project != null && document != null) {
                Column(Modifier.fillMaxSize()) {
                    TextButton(onClick = { preview = false }) { Text(stringResource(R.string.project_edit_file)) }
                    key(project, document.relativePath, document.revision) { ProjectPreview(project, document) }
                }
            } else if (state.runsVisible) ProjectRunPanel(state, send)
            else if (state.gitVisible) ProjectGitPanel(state, send)
            else if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProjectFileList(state, Modifier.weight(0.42f), send)
                ProjectCodeEditor(state, Modifier.weight(0.58f), send, { preview = true }, onAddReference) { confirmDiscard = true }
            } else if (state.document != null) {
                ProjectCodeEditor(state, Modifier.fillMaxSize(), send, { preview = true }, onAddReference) { confirmDiscard = true }
            } else {
                ProjectFileList(state, Modifier.fillMaxSize(), send)
            }
        }
    }
    val instructionsPath = state.project
    if (instructionsPath != null) InputLiquidDialog(
        visible = editingInstructions, onDismissRequest = { editingInstructions = false },
        title = stringResource(R.string.project_instructions),
        value = projectInstructions, onValueChange = { projectInstructions = it.take(8000) },
        placeholder = stringResource(R.string.project_instructions), singleLine = false, maxLines = 8,
        positiveButtonText = stringResource(R.string.project_save),
        onPositiveClick = {
            val value = projectInstructions.trim()
            instructionStore.edit().putString(instructionsPath, value).apply()
            savedInstructions = value
            editingInstructions = false
        },
    )
    ConfirmationLiquidDialog(
        visible = confirmDiscard, onDismissRequest = { confirmDiscard = false },
        title = stringResource(R.string.project_discard), text = stringResource(R.string.project_discard_prompt),
        negativeButtonText = stringResource(android.R.string.cancel), positiveButtonText = stringResource(R.string.project_discard),
        onNegativeClick = { confirmDiscard = false },
        onPositiveClick = { confirmDiscard = false; send(ProjectIntent.CancelEdit) },
    )
}

@Composable
private fun ProjectReviewPanel(project: String, dirty: Boolean, send: (ProjectIntent) -> Unit) {
    val records by WorkspaceTurnReviews.reviews.collectAsState()
    val enabledPaths by WorkspaceTurnReviews.enabled.collectAsState()
    val enabled = project in enabledPaths
    val scope = rememberCoroutineScope()
    var saving by remember(project) { mutableStateOf(false) }
    var saveFailed by remember(project) { mutableStateOf(false) }
    LaunchedEffect(project) { WorkspaceTurnReviews.load() }
    var selected by remember(project) { mutableStateOf<Pair<String, String>?>(null) }
    val reviews = records.filter { it.workspace == project }
    val review = reviews.firstOrNull { it.id == selected?.first }
    val change = review?.changes?.firstOrNull { it.path == selected?.second }
    var confirm by remember(change, review?.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            TextButton(enabled = !saving, onClick = {
                selected = null
                saving = true
                saveFailed = false
                scope.launch {
                    try {
                        WorkspaceTurnReviews.setEnabled(project, !enabled)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        saveFailed = true
                    } finally {
                        saving = false
                    }
                }
            }) { Text(stringResource(if (enabled) R.string.project_reviews_disable else R.string.project_reviews_enable)) }
            if (saveFailed) Text(stringResource(R.string.project_reviews_save_failed))
            Text(stringResource(R.string.project_reviews_scope), style = MaterialTheme.typography.bodySmall)
        }
        if (change != null) {
            item {
                TextButton(onClick = { selected = null }) { Text(change.path) }
                TextButton(onClick = { confirm = true }, enabled = !dirty) { Text(stringResource(R.string.project_restore)) }
            }
            item { Text(stringResource(R.string.project_reviews_before)) }
            items(change.before.orEmpty().lineSequence().chunked(32).toList()) { lines ->
                Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace)
            }
            item { Text(stringResource(R.string.project_reviews_after)) }
            items(change.after.orEmpty().lineSequence().chunked(32).toList()) { lines ->
                Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace)
            }
        } else reviews.forEach { review ->
            item(key = review.id) {
                Text(DateFormat.getDateTimeInstance().format(Date(review.finishedAt)), style = MaterialTheme.typography.titleSmall)
                if (review.interrupted) Text(stringResource(R.string.project_run_interrupted))
                if (review.changes == null) Text(stringResource(R.string.project_reviews_unavailable))
                else if (review.changes.orEmpty().isEmpty()) Text(stringResource(R.string.project_reviews_empty))
            }
            items(review.changes.orEmpty(), key = { review.id + ":" + it.path }) { file ->
                ProjectRow(file.path, null, true) { selected = review.id to file.path }
            }
        }
    }
    if (change != null && review != null) ConfirmationLiquidDialog(
        visible = confirm, onDismissRequest = { confirm = false },
        title = stringResource(R.string.project_restore),
        text = stringResource(R.string.project_restore_confirm, change.path),
        negativeButtonText = stringResource(android.R.string.cancel),
        positiveButtonText = stringResource(R.string.project_restore),
        onNegativeClick = { confirm = false },
        onPositiveClick = { confirm = false; send(ProjectIntent.RestoreReview(review.id, change.path)) },
    )
}

@Composable
private fun ProjectTerminalPane(state: ProjectWorkbenchState, send: (ProjectIntent) -> Unit) {
    val scroll = rememberScrollState()
    LaunchedEffect(state.terminalOutput) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = {
            send(if (state.terminalRunning) ProjectIntent.StopTerminal else ProjectIntent.StartTerminal)
        }) { Text(stringResource(if (state.terminalRunning) R.string.project_terminal_close else R.string.project_terminal_start)) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { send(ProjectIntent.TerminalHistory(true)) },
                enabled = state.terminalReady && state.terminalHistoryOffset < state.terminalHistory.size) {
                Text(stringResource(R.string.project_terminal_older))
            }
            TextButton(onClick = { send(ProjectIntent.TerminalHistory(false)) },
                enabled = state.terminalReady && state.terminalHistoryOffset > 0) {
                Text(stringResource(R.string.project_terminal_newer))
            }
        }
        if (state.terminalFailed) Text(stringResource(R.string.project_terminal_error), color = MaterialTheme.colorScheme.error)
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            Text(state.terminalOutput, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(state.terminalInput, { send(ProjectIntent.TerminalInput(it)) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.project_terminal_input)) }, singleLine = true, enabled = state.terminalReady,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(ProjectIntent.SendTerminal) }))
            TextButton(onClick = { send(ProjectIntent.SendTerminal) }, enabled = state.terminalReady) {
                Text(stringResource(R.string.project_terminal_send))
            }
        }
    }
}

@Composable
private fun ProjectRunPanel(state: ProjectWorkbenchState, send: (ProjectIntent) -> Unit) {
    val running = state.runs.any { it.status == ProjectRunStatus.Running }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(state.command, { send(ProjectIntent.Command(it)) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.project_command)) }, singleLine = true)
            TextButton(onClick = { send(if (running) ProjectIntent.Stop else ProjectIntent.Run(state.command)) },
                enabled = running || state.command.isNotBlank()) {
                Text(stringResource(if (running) R.string.project_run_stop else R.string.project_run))
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            state.runs.forEach { run ->
                item(key = run.id) {
                    Text(run.command, fontFamily = FontFamily.Monospace)
                    val status = when (run.status) {
                        ProjectRunStatus.Running -> R.string.project_run_running
                        ProjectRunStatus.Finished -> R.string.project_run_finished
                        ProjectRunStatus.TimedOut -> R.string.project_run_timeout
                        ProjectRunStatus.Failed -> R.string.project_run_failed
                        ProjectRunStatus.Stopped -> R.string.project_run_stopped
                        ProjectRunStatus.Interrupted -> R.string.project_run_interrupted
                    }
                    Text(stringResource(status) + (run.exitCode?.let { " ($it)" } ?: ""))
                    if (run.status != ProjectRunStatus.Running) {
                        TextButton(onClick = { send(ProjectIntent.Run(run.command)) }, enabled = !running) {
                            Text(stringResource(R.string.project_run_again))
                        }
                    }
                }
                val logLines = run.output.lineSequence().toList()
                items(logLines) { line ->
                    val location = projectLogLocation(line)
                    if (location == null) {
                        Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    } else {
                        ProjectRow(line, stringResource(R.string.project_run_open_source), !state.dirty) {
                            send(ProjectIntent.OpenFile(location.path, location.line, fromLog = true))
                        }
                    }
                }
                item { HorizontalDivider() }
            }
        }
    }
}

@Composable
private fun ProjectGitPanel(state: ProjectWorkbenchState, send: (ProjectIntent) -> Unit) {
    val status = state.gitStatus
    val diff = state.gitDiff
    var pendingCommit by remember(state.project) { mutableStateOf<String?>(null) }
    if (status == null) {
        Text(stringResource(R.string.project_no_git))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(status.branch, style = MaterialTheme.typography.titleSmall) }
        if (diff != null) {
            item {
                TextButton(onClick = { send(ProjectIntent.ShowGit) }) { Text(stringResource(R.string.project_git)) }
                if (diff.truncated) Text(stringResource(R.string.project_diff_limited))
            }
            items(diff.text.lineSequence().chunked(40).toList()) { lines ->
                Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        } else {
            if (status.changes.any { it.staged } && status.changes.none { it.kind == GitChangeKind.Conflict }) item {
                var message by rememberSaveable { mutableStateOf("") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(message, { message = it }, Modifier.weight(1f), singleLine = true,
                        label = { Text(stringResource(R.string.project_git_message)) })
                    TextButton(onClick = { pendingCommit = message.trim() }, enabled = message.isNotBlank()) {
                        Text(stringResource(R.string.project_git_commit))
                    }
                }
            }
            if (status.changes.isEmpty()) item { Text(stringResource(R.string.project_git_clean)) }
            items(status.changes) { change ->
                val label = when (change.kind) {
                    GitChangeKind.Added -> R.string.project_git_added
                    GitChangeKind.Modified -> R.string.project_git_modified
                    GitChangeKind.Deleted -> R.string.project_git_deleted
                    GitChangeKind.Untracked -> R.string.project_git_untracked
                    GitChangeKind.Conflict -> R.string.project_git_conflict
                }
                val kind = stringResource(label)
                val detail = if (change.staged) stringResource(R.string.project_git_staged, kind) else kind
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        ProjectRow(change.path, detail, true) { send(ProjectIntent.GitDiff(change)) }
                    }
                    if (change.kind != GitChangeKind.Conflict) {
                        TextButton(onClick = { send(ProjectIntent.SetStaged(change, !change.staged)) }) {
                            Text(stringResource(if (change.staged) R.string.project_git_unstage else R.string.project_git_stage))
                        }
                    }
                }
            }
        }
    }
    val message = pendingCommit
    if (message != null) ConfirmationLiquidDialog(
        visible = true, onDismissRequest = { pendingCommit = null },
        title = stringResource(R.string.project_git_commit),
        text = stringResource(R.string.project_git_commit_confirm),
        negativeButtonText = stringResource(android.R.string.cancel),
        positiveButtonText = stringResource(R.string.project_git_commit),
        onNegativeClick = { pendingCommit = null },
        onPositiveClick = { pendingCommit = null; send(ProjectIntent.Commit(message)) },
    )
}

@Composable
private fun ProjectFileList(state: ProjectWorkbenchState, modifier: Modifier, send: (ProjectIntent) -> Unit) {
    var creatingFolder by remember(state.project, state.directory) { mutableStateOf<Boolean?>(null) }
    var name by remember(state.project, state.directory) { mutableStateOf("") }
    Column(modifier) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { name = ""; creatingFolder = false }, enabled = !state.dirty) {
                Text(stringResource(R.string.project_new_file))
            }
            TextButton(onClick = { name = ""; creatingFolder = true }, enabled = !state.dirty) {
                Text(stringResource(R.string.project_new_folder))
            }
        }
        Text(if (state.directory.isEmpty()) "/" else state.directory, style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(state.query, { send(ProjectIntent.Query(it)) }, Modifier.weight(1f),
                label = { Text(stringResource(R.string.project_search)) }, singleLine = true)
            TextButton(onClick = { send(ProjectIntent.Search) }) { Text(stringResource(R.string.project_search)) }
        }
        if (state.directory.isNotEmpty()) TextButton(onClick = {
            send(ProjectIntent.Directory(state.directory.substringBeforeLast('/', "")))
        }) { Text(stringResource(R.string.project_up)) }
        if (state.searching) Text(stringResource(R.string.project_searching))
        val result = state.search
        if (result != null && state.query.isNotBlank()) {
            Text(stringResource(if (result.limited) R.string.project_search_limited else R.string.project_search_results, result.matches.size),
                style = MaterialTheme.typography.labelMedium)
            LazyColumn(Modifier.weight(1f)) {
                items(result.matches, key = { it.relativePath + ":" + it.line }) { hit ->
                    ProjectRow(hit.relativePath + ":" + hit.line, hit.excerpt, !state.dirty) { send(ProjectIntent.OpenFile(hit.relativePath, hit.line)) }
                }
            }
        } else LazyColumn(Modifier.weight(1f)) {
            items(state.entries, key = { it.relativePath }) { entry ->
                ProjectRow(if (entry.directory) entry.name + "/" else entry.name, null, entry.directory || !state.dirty) {
                    send(if (entry.directory) ProjectIntent.Directory(entry.relativePath) else ProjectIntent.OpenFile(entry.relativePath))
                }
            }
        }
    }
    val folder = creatingFolder
    if (folder != null) {
        val title = stringResource(if (folder) R.string.project_new_folder else R.string.project_new_file)
        InputLiquidDialog(
            visible = true, onDismissRequest = { creatingFolder = null },
            title = title, value = name, onValueChange = { name = it.take(255) },
            placeholder = stringResource(R.string.project_entry_name),
            positiveButtonText = title, positiveEnabled = name.isNotBlank() && !state.dirty,
            onPositiveClick = {
                creatingFolder = null
                send(ProjectIntent.CreateEntry(name, folder))
            },
        )
    }
}

@Composable
private fun ProjectRow(title: String, detail: String?, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
    HorizontalDivider()
}

@Composable
private fun ProjectCodeEditor(state: ProjectWorkbenchState, modifier: Modifier, send: (ProjectIntent) -> Unit, onPreview: () -> Unit, onAddReference: ((String) -> Unit)?, onDiscard: () -> Unit) {
    val document = state.document ?: return
    val colors = MaterialTheme.colorScheme
    val highlight = remember(document.relativePath, colors) {
        ProjectCodeHighlight(document.relativePath.substringAfterLast('.', ""), colors.primary, colors.tertiary, colors.onSurfaceVariant)
    }
    var editor by remember(state.project, document.relativePath, state.documentOpenId) {
        val offset = sourceLineOffset(state.draft, state.jumpToLine)
        mutableStateOf(TextFieldValue(state.draft, TextRange(offset)))
    }
    var goToLine by remember(document.relativePath) { mutableStateOf(false) }
    var requestedLine by remember(document.relativePath) { mutableStateOf("") }
    val firstLine = editor.text.take(editor.selection.min).count { it == '\n' } + 1
    val lastOffset = if (editor.selection.collapsed) editor.selection.max else editor.selection.max - 1
    val lastLine = editor.text.take(lastOffset).count { it == '\n' } + 1
    val line = if (firstLine == lastLine) "$firstLine" else "$firstLine-$lastLine"
    Column(modifier) {
        Text("${document.relativePath}:$line" + if (state.dirty) " *" else "", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            val project = state.project
            if (project != null && onAddReference != null) {
                TextButton(
                    onClick = { onAddReference("$project/${document.relativePath}:$line") }, enabled = !state.dirty,
                ) { Text(stringResource(R.string.project_reference)) }
                if (document.relativePath.substringAfterLast('/') == "SKILL.md") {
                    val content = document.text
                    val canInsert = !state.dirty && content.isNotBlank() && content.length <= 16_000
                    val reference = if (canInsert) stringResource(R.string.project_skill_context, document.relativePath, content) else null
                    TextButton(onClick = { reference?.let(onAddReference) }, enabled = canInsert) {
                        Text(stringResource(R.string.project_skill_insert))
                    }
                }
            }
            TextButton(onClick = { requestedLine = firstLine.toString(); goToLine = true }) {
                Text(stringResource(R.string.project_go_to_line))
            }
            TextButton(onClick = { send(ProjectIntent.Save(state.documentOpenId)) }, enabled = state.dirty) { Text(stringResource(R.string.project_save)) }
            if (document.relativePath.substringAfterLast('.').lowercase() in setOf("html", "htm")) {
                TextButton(onClick = onPreview, enabled = !state.dirty) { Text(stringResource(R.string.project_preview)) }
            }
            TextButton(onClick = { if (state.dirty) onDiscard() else send(ProjectIntent.CancelEdit) }) { Text(stringResource(R.string.project_close_file)) }
        }
        OutlinedTextField(
            value = editor, onValueChange = {
                if (it.text != editor.text) send(ProjectIntent.Draft(state.documentOpenId, it.text))
                editor = it
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = highlight,
        )
    }
    if (goToLine) {
        val target = requestedLine.toIntOrNull()?.takeIf { it > 0 }
        val title = stringResource(R.string.project_go_to_line)
        InputLiquidDialog(
            visible = true, onDismissRequest = { goToLine = false }, title = title,
            value = requestedLine, onValueChange = { requestedLine = it.filter(Char::isDigit).take(9) },
            placeholder = title, positiveButtonText = title, positiveEnabled = target != null,
            onPositiveClick = {
                if (target != null) editor = editor.copy(selection = TextRange(sourceLineOffset(editor.text, target)), composition = null)
                goToLine = false
            },
        )
    }
}

private fun sourceLineOffset(text: String, line: Int): Int {
    if (line <= 1) return 0
    var offset = 0
    repeat(line - 1) {
        val newline = text.indexOf('\n', offset)
        if (newline == -1) return text.length
        offset = newline + 1
    }
    return offset
}
