package com.niki914.zafiro.app.ui.devtools

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.niki914.uikit.screen.zafiroScreenBottomPadding
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DeveloperWorkbench() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var workspace by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(R.string.workbench_logs, R.string.workbench_patch, R.string.workbench_artifact, R.string.workbench_notes)
    val notesStore = remember(workspace, context) {
        WorkbenchNotes(context.getSharedPreferences("developer_workbench_notes", Context.MODE_PRIVATE), workspace)
    }
    var notes by remember(workspace) { mutableStateOf(emptyList<WorkbenchNote>()) }
    var source by remember(workspace) { mutableStateOf<SourceExcerpt?>(null) }
    var changed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { workspace = workbenchIo { XRepo.llmConfigs.workspace() }.getOrDefault("") }
    LaunchedEffect(notesStore) {
        if (workspace.isNotBlank()) {
            workbenchIo { notesStore.load() }.onSuccess { notes = it }.onFailure { error = true }
        }
    }
    fun open(path: String, line: Int, column: Int, expected: String? = null) {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            try {
                workbenchIo { workbenchSource(workspace, SourceAnchor(path, line, column)) }
                    .onSuccess { changed = expected != null && expected != it.fingerprint; source = it }
                    .onFailure { error = true }
            } finally { busy = false }
        }
    }
    val holder = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize().padding(top = zafiroScreenTopPadding(), bottom = zafiroScreenBottomPadding())) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
            }
        }
        if (workspace.isBlank() && tab != 2) Text(stringResource(R.string.workbench_workspace), Modifier.padding(12.dp))
        if (error) Text(stringResource(R.string.workbench_failure), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> DiagnosticPanel(workspace) { path, line, col -> open(path, line, col) }
                    1 -> PatchPanel(workspace) { path, line, col -> open(path, line, col) }
                    2 -> ArtifactPanel()
                    3 -> WorkbenchNotesPanel(notes, workspace.isNotBlank() && !busy, { path, line -> open(path, line, 1) },
                        { open(it.anchor.path, it.anchor.line, it.anchor.column, it.fingerprint) }, { note ->
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    try { workbenchIo { notesStore.remove(note.id) }.onSuccess { notes = it }.onFailure { error = true } }
                                    finally { busy = false }
                                }
                            }
                        })
                }
            }
        }
    }
    source?.let { current ->
        SourcePreview(current, changed, busy, onClose = { source = null }, onSave = { symbol, text ->
            busy = true
            scope.launch {
                try {
                    workbenchIo { notesStore.add(current, symbol, text) }.onSuccess { notes = it; source = null; tab = 3 }
                        .onFailure { source = null; error = true }
                } finally { busy = false }
            }
        })
    }
}

@Composable
private fun WorkbenchNotesPanel(notes: List<WorkbenchNote>, enabled: Boolean, open: (String, Int) -> Unit,
                                locate: (WorkbenchNote) -> Unit, remove: (WorkbenchNote) -> Unit) {
    var path by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("1") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            OutlinedTextField(path, { path = it.take(1024) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.workbench_path)) })
            OutlinedTextField(line, { line = it.filter(Char::isDigit).take(9) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.workbench_line)) })
            TextButton({ line.toIntOrNull()?.let { open(path.trim(), it) } }, enabled = enabled && path.isNotBlank() && (line.toIntOrNull() ?: 0) > 0) {
                Text(stringResource(R.string.workbench_open))
            }
            if (notes.isEmpty()) Text(stringResource(R.string.workbench_empty))
        }
        items(notes, key = { it.id }) { note ->
            HorizontalDivider()
            Text("${note.anchor.path}:${note.anchor.line}", style = MaterialTheme.typography.titleSmall)
            if (note.symbol.isNotBlank()) Text(note.symbol)
            Text(note.text)
            TextButton({ locate(note) }, enabled = enabled) { Text(stringResource(R.string.workbench_open)) }
            TextButton({ remove(note) }, enabled = enabled) { Text(stringResource(R.string.workbench_delete)) }
        }
    }
}

@Composable
private fun SourcePreview(source: SourceExcerpt, changed: Boolean, busy: Boolean, onClose: () -> Unit,
                          onSave: (String, String) -> Unit) {
    var symbol by remember(source) { mutableStateOf("") }
    var text by remember(source) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text(stringResource(R.string.workbench_preview)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${source.anchor.path}:${source.anchor.line}:${source.anchor.column}")
                if (changed) Text(stringResource(R.string.workbench_changed), color = MaterialTheme.colorScheme.error)
                SelectionContainer {
                    Text(source.lines.mapIndexed { index, value ->
                        val number = source.first + index
                        val marker = if (number == source.anchor.line) ">" else " "
                        "$marker$number  $value"
                    }.joinToString("\n"), Modifier.heightIn(min = 64.dp), fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(symbol, { symbol = it.take(200) }, singleLine = true, label = { Text(stringResource(R.string.workbench_symbol)) })
                OutlinedTextField(text, { text = it.take(4000) }, maxLines = 4, label = { Text(stringResource(R.string.workbench_note)) })
            }
        }, confirmButton = {
            TextButton({ onSave(symbol, text) }, enabled = !busy && text.isNotBlank()) { Text(stringResource(R.string.workbench_save)) }
        }, dismissButton = {
            TextButton(onClose, enabled = !busy) { Text(stringResource(R.string.workbench_close)) }
        })
}

private suspend fun <T> workbenchIo(action: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try { Result.success(action()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}
