package com.niki914.zafiro.app.ui.devtools

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.niki914.uikit.button.ZafiroButton
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticPanel(workspace: String, onSource: (String, Int, Int) -> Unit) {
    key(workspace) { DiagnosticWorkspaceContent(workspace, onSource) }
}

private data class DiagnosticImport(
    val report: DiagnosticReport,
    val links: Map<DiagnosticSource, File>,
    val plans: Map<DiagnosticTestTarget, DiagnosticRunPlan>,
)

@Composable
private fun DiagnosticWorkspaceContent(workspace: String, onSource: (String, Int, Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zh = LocalConfiguration.current.locales[0]?.language == "zh"
    fun label(chinese: String, english: String) = if (zh) chinese else english

    var imported by remember { mutableStateOf<DiagnosticImport?>(null) }
    var pending by remember { mutableStateOf<DiagnosticTestTarget?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableStateOf<DiagnosticRunResult?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            pending = null
            message = null
            run = null
            scope.launch {
                try {
                    imported = withContext(Dispatchers.IO) {
                        val report = context.contentResolver.openInputStream(uri)?.use(::readDiagnosticReport)
                            ?: throw IOException("Document stream unavailable")
                        val root = diagnosticWorkspace(workspace)
                        val locations = report.entries.flatMap { entry ->
                            listOfNotNull(entry.source) + entry.trace.mapNotNull(DiagnosticFrame::source)
                        }.toSet()
                        val java = diagnosticJava()
                        val plans = report.entries.mapNotNull(DiagnosticEntry::target).distinct().mapNotNull { target ->
                            prepareDiagnosticRun(root, target, java)?.let { target to it }
                        }.toMap()
                        DiagnosticImport(report, resolveDiagnosticSources(root, locations), plans)
                    }
                } catch (_: IOException) {
                    message = label("无法读取日志。", "Could not read the log.")
                } catch (_: SecurityException) {
                    message = label("无权读取日志。", "No permission to read the log.")
                } finally {
                    busy = false
                }
            }
        }
    }

    val openSource: (File, DiagnosticSource) -> Unit = { file, point ->
        scope.launch {
            val current = withContext(Dispatchers.IO) { recheckDiagnosticSource(workspace, file) }
            if (current != null) onSource(current.path, point.line, point.column)
            else message = label("源码已移动或不可读。", "Source moved or is no longer readable.")
        }
    }
    val snapshot = imported
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ZafiroButton(
                text = label("导入日志", "Import log"),
                enabled = !busy,
                onClick = {
                    try {
                        picker.launch(arrayOf("text/*", "application/octet-stream"))
                    } catch (_: ActivityNotFoundException) {
                        message = label("系统文件选择器不可用。", "System document picker unavailable.")
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (busy) item { DiagnosticNote(label("处理中…", "Working…")) }
        message?.let { item { DiagnosticNote(it) } }
        run?.let { result ->
            item {
                val status = when (result.code) {
                    null -> label("Gradle 重跑超时。", "Gradle rerun timed out.")
                    0 -> label("Gradle 重跑成功。", "Gradle rerun succeeded.")
                    else -> label("Gradle 退出码：", "Gradle exit code: ") + result.code
                }
                DiagnosticNote(status)
                if (result.output.isNotBlank()) SelectionContainer { DiagnosticNote(result.output) }
                if (result.outputLimited) DiagnosticNote(label("仅显示部分输出。", "Output preview truncated."))
            }
        }
        if (snapshot != null) {
            val report = snapshot.report
            if (report.limited) item { DiagnosticNote(label("日志超出处理上限，结果不完整。", "Log exceeded a limit; results are partial.")) }
            if (report.entries.isEmpty()) item { DiagnosticNote(label("未找到可识别的诊断。", "No recognized diagnostics.")) }
            if (report.buildIds.isNotEmpty()) item {
                SelectionContainer { DiagnosticNote("Build ID: " + report.buildIds.joinToString("\n")) }
            }
            item {
                DiagnosticNote(label(
                    "不自动执行日志命令。不做 R8 反混淆或原生符号化；Build ID 仅供匹配符号文件。",
                    "Log commands are never executed. No R8 deobfuscation or native symbolication; use Build IDs to match symbol files.",
                ))
            }
            if (report.entries.any { it.target != null && it.target !in snapshot.plans }) item {
                DiagnosticNote(label(
                    "无法重跑部分目标：需可由此应用执行的 JDK、工作区 Gradle Wrapper 和匹配的测试源码。",
                    "Some targets cannot be rerun: an app-accessible JDK, workspace Gradle Wrapper and matching test sources are required.",
                ))
            }
            items(report.entries) { entry ->
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.summary)
                    entry.source?.let { point ->
                        DiagnosticLine("${point.path}:${point.line}:${point.column}", point, snapshot.links[point], openSource)
                    }
                    for (frame in entry.trace) DiagnosticLine(frame.text, frame.source, frame.source?.let { snapshot.links[it] }, openSource)
                    val target = entry.target
                    if (target != null && target in snapshot.plans) {
                        TextButton(onClick = { pending = target }, enabled = !busy) {
                            Text(label("重跑此单测", "Rerun this test"))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    val target = pending
    val plan = target?.let { snapshot?.plans?.get(it) }
    if (target != null && plan != null) {
        ZafiroConfirmationDialog(
            visible = true,
            onDismissRequest = { pending = null },
            title = label("重跑 Gradle 单测？", "Rerun Gradle unit test?"),
            text = label("仅运行可信工作区的构建脚本。", "Only run build scripts from a workspace you trust.") +
                "\n${plan.root.path}\n${target.task} --rerun --tests ${target.className}.${target.method}",
            negativeButtonText = label("取消", "Cancel"),
            positiveButtonText = label("运行", "Run"),
            onNegativeClick = { pending = null },
            onPositiveClick = {
                if (!busy && pending == target) {
                    pending = null
                    busy = true
                    message = null
                    run = null
                    scope.launch {
                        try {
                            run = withContext(Dispatchers.IO) {
                                val current = prepareDiagnosticRun(diagnosticWorkspace(workspace), target, diagnosticJava())
                                if (current != null && current == plan) executeDiagnosticRun(current) else null
                            }
                            if (run == null) message = label("执行条件已变化，请重新导入日志。", "Run prerequisites changed; import the log again.")
                        } catch (_: IOException) {
                            message = label("无法启动 Gradle 或读取输出。", "Could not launch Gradle or read its output.")
                        } catch (_: SecurityException) {
                            message = label("应用无权执行该 JDK。", "The app cannot execute this JDK.")
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DiagnosticNote(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DiagnosticLine(text: String, source: DiagnosticSource?, file: File?, onSource: (File, DiagnosticSource) -> Unit) {
    if (source != null && file != null) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().clickable { onSource(file, source) }.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        )
    } else Text(text)
}

private fun diagnosticJava(): File? = sequenceOf(System.getenv("JAVA_HOME"), System.getProperty("java.home"))
    .filterNotNull().filter(String::isNotBlank).map { File(it, "bin/java") }
    .firstOrNull { it.isAbsolute && it.isFile && it.canExecute() }

private data class DiagnosticRunResult(val code: Int?, val output: String, val outputLimited: Boolean)

private suspend fun executeDiagnosticRun(plan: DiagnosticRunPlan): DiagnosticRunResult {
    currentCoroutineContext().ensureActive()
    val process = ProcessBuilder(plan.argv).directory(plan.root).redirectErrorStream(true).start()
    val output = ByteArrayOutputStream()
    val limit = 12 * 1024
    var limited = false
    val buffer = ByteArray(4096)
    val deadline = System.nanoTime() + 180_000_000_000L
    try {
        process.outputStream.close()
        process.inputStream.use { input ->
            while (true) {
                val size = minOf(input.available(), buffer.size)
                if (size > 0) {
                    val read = input.read(buffer, 0, size)
                    if (read > 0) {
                        val keep = minOf(read, limit - output.size())
                        output.write(buffer, 0, keep)
                        limited = limited || keep < read
                    }
                }
                if (!process.isAlive && input.available() == 0) break
                if (System.nanoTime() >= deadline) return DiagnosticRunResult(null, output.toString("UTF-8"), limited)
                // Also yield while Gradle continuously produces output so navigation cancels the job.
                delay(if (size > 0) 1L else 30L)
            }
        }
        return DiagnosticRunResult(process.exitValue(), output.toString("UTF-8"), limited)
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
