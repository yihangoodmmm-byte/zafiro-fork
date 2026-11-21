package com.niki914.zafiro.app.ui.devtools

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import java.io.IOException
import java.nio.file.InvalidPathException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PatchPanel(workspace: String, onSource: (String, Int, Int) -> Unit) {
    key(workspace) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val panel = remember { PatchPanelController(workspace, scope) }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) panel.load(context.contentResolver, uri)
        }
        PatchPanelContent(panel, onImport = { pick.launch(arrayOf("*/*")) }, onSource = onSource)
    }
}

private data class PatchPanelSelection(val file: Int, val hunk: Int)

private data class PatchPanelState(
    val review: PatchPanelReview? = null,
    val choices: List<PatchPanelSelection> = emptyList(),
    val selected: Int = 0,
    val busy: Boolean = false,
    val confirm: Boolean = false,
    val confirmImport: Boolean = false,
    val error: PatchPanelProblem? = null,
) {
    val choice get() = choices.getOrNull(selected)
    val hasApplied get() = choices.any { review?.isApplied(it.file, it.hunk) == true }
}

private class PatchPanelController(private val workspace: String, private val scope: CoroutineScope) {
    var state by mutableStateOf(PatchPanelState())
        private set

    fun load(resolver: ContentResolver, uri: Uri) = runOperation {
        val review = resolver.openInputStream(uri)?.use { input ->
            PatchPanelReview.open(workspace, patchPanelDecode(patchPanelRead(input, PATCH_PANEL_MAX_DIFF)))
        } ?: throw PatchPanelFailure(PatchPanelProblem.IO)
        val order = review.files.flatMapIndexed { file, item ->
            item.hunks.indices.map { PatchPanelSelection(file, it) }
        }
        PatchPanelState(review = review, choices = order)
    }

    fun select(index: Int) {
        if (!state.busy && !state.confirm && !state.confirmImport && index in state.choices.indices) {
            state = state.copy(selected = index, error = null)
        }
    }

    fun showConfirm(show: Boolean) {
        if (!state.busy && state.choice != null) state = state.copy(confirm = show)
    }

    fun showImportConfirm(show: Boolean) {
        if (!state.busy) state = state.copy(confirmImport = show)
    }

    fun openSource(onSource: (String, Int, Int) -> Unit) {
        val choice = state.choice ?: return
        val review = state.review ?: return
        runOperation({ previous ->
            previous to review.source(choice.file, choice.hunk)
        }) { (path, line) -> onSource(path, line, 1) }
    }

    fun commit() {
        val choice = state.choice ?: return
        val review = state.review ?: return
        if (!state.confirm) return
        runOperation { previous ->
            val accept = !review.isApplied(choice.file, choice.hunk)
            previous.copy(review = review.write(choice.file, choice.hunk, accept))
        }
    }

    private fun runOperation(work: (PatchPanelState) -> PatchPanelState) =
        runOperation({ previous -> work(previous) to Unit }) {}

    private fun <T> runOperation(
        work: (PatchPanelState) -> Pair<PatchPanelState, T>,
        onSuccess: (T) -> Unit,
    ) {
        if (state.busy) return
        val previous = state.copy(confirm = false, confirmImport = false, error = null)
        state = previous.copy(busy = true)
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { work(previous) }
            } catch (error: PatchPanelFailure) {
                state = previous.copy(error = error.problem)
                return@launch
            } catch (_: InvalidPathException) {
                state = previous.copy(error = PatchPanelProblem.PATH)
                return@launch
            } catch (_: IOException) {
                state = previous.copy(error = PatchPanelProblem.IO)
                return@launch
            } catch (_: SecurityException) {
                state = previous.copy(error = PatchPanelProblem.IO)
                return@launch
            } catch (_: UnsupportedOperationException) {
                state = previous.copy(error = PatchPanelProblem.UNSUPPORTED)
                return@launch
            }
            state = result.first
            onSuccess(result.second)
        }
    }
}

@Composable
private fun PatchPanelContent(
    panel: PatchPanelController,
    onImport: () -> Unit,
    onSource: (String, Int, Int) -> Unit,
) {
    val state = panel.state
    val selection = state.choice
    val review = state.review
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                enabled = !state.busy,
                onClick = { if (state.hasApplied) panel.showImportConfirm(true) else onImport() },
            ) { Text("Import diff") }
            if (selection != null && review != null) {
                TextButton(enabled = !state.busy, onClick = { panel.openSource(onSource) }) {
                    Text("Open source")
                }
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let {
            Text(patchPanelError(it), color = MaterialTheme.colorScheme.error)
        }
        if (selection == null || review == null) {
            Text("Unified diff (.diff or .patch) · Existing UTF-8 files only", style = MaterialTheme.typography.bodyMedium)
        } else {
            val file = review.files[selection.file]
            val hunk = file.hunks[selection.hunk]
            val applied = review.isApplied(selection.file, selection.hunk)
            val action = if (applied) "Undo hunk" else "Accept hunk"
            SelectionContainer { Text(file.relativePath, style = MaterialTheme.typography.titleSmall) }
            Text(hunk.header, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = !state.busy && state.selected > 0, onClick = {
                    panel.select(state.selected - 1)
                }) { Text("Previous") }
                Text("${state.selected + 1} / ${state.choices.size}")
                TextButton(enabled = !state.busy && state.selected < state.choices.lastIndex, onClick = {
                    panel.select(state.selected + 1)
                }) { Text("Next") }
            }
            key(state.selected) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(hunk.lines) { line -> PatchPanelPreviewLine(line) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (applied) "Applied" else "Pending")
                Button(enabled = !state.busy, onClick = { panel.showConfirm(true) }) { Text(action) }
            }
            ZafiroConfirmationDialog(
                visible = state.confirm,
                onDismissRequest = { panel.showConfirm(false) },
                title = action,
                text = "Write one hunk to ${file.relativePath}?\n${hunk.header}",
                negativeButtonText = "Cancel",
                positiveButtonText = "Write",
                onNegativeClick = { panel.showConfirm(false) },
                onPositiveClick = panel::commit,
            )
        }
        ZafiroConfirmationDialog(
            visible = state.confirmImport,
            onDismissRequest = { panel.showImportConfirm(false) },
            title = "Replace review?",
            text = "Accepted hunks stay on disk. Loading a different diff ends undo for this review.",
            negativeButtonText = "Cancel",
            positiveButtonText = "Choose diff",
            onNegativeClick = { panel.showImportConfirm(false) },
            onPositiveClick = {
                panel.showImportConfirm(false)
                onImport()
            },
        )
    }
}

@Composable
private fun PatchPanelPreviewLine(line: PatchPanelLine) {
    val color = when (line.kind) {
        '+' -> MaterialTheme.colorScheme.primary
        '-' -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val visible = remember(line.text) {
        buildString {
            for (char in line.text) {
                if (char != '\t' && (char.isISOControl() || Character.getType(char) == Character.FORMAT.toInt())) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
    }
    Text(
        text = "${line.kind}$visible" + if (line.terminated) "" else "\n\\ No newline at end of file",
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun patchPanelError(problem: PatchPanelProblem): String = when (problem) {
    PatchPanelProblem.FORMAT -> "Invalid or overlapping unified diff hunks."
    PatchPanelProblem.LIMIT -> "The diff or workspace files exceed the review limit."
    PatchPanelProblem.TEXT -> "Only UTF-8 text with consistent line endings is supported."
    PatchPanelProblem.PATH -> "Unsafe workspace or file path. Symbolic links are not allowed."
    PatchPanelProblem.CONTEXT -> "The diff does not match the current file at its stated lines."
    PatchPanelProblem.STALE -> "The file changed after review. Import a fresh diff before writing."
    PatchPanelProblem.UNSUPPORTED -> "This diff format or filesystem does not support safe patch writes."
    PatchPanelProblem.IO -> "The diff or workspace file could not be accessed."
}
