package com.niki914.zafiro.app.ui.devtools

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException

internal data class DiagnosticSource(val path: String, val line: Int, val column: Int = 1)

internal data class DiagnosticEntry(
    val summary: String,
    val source: DiagnosticSource? = null,
    val trace: List<DiagnosticFrame> = emptyList(),
    val target: DiagnosticTestTarget? = null,
)

internal data class DiagnosticFrame(val text: String, val source: DiagnosticSource? = null)
internal data class DiagnosticReport(val entries: List<DiagnosticEntry>, val buildIds: List<String>, val limited: Boolean)
internal data class DiagnosticTestTarget(val task: String, val className: String, val method: String)

private const val LOG_BYTES = 2 * 1024 * 1024
private const val LOG_LINES = 24_000
private const val LINE_LENGTH = 4096
private const val ENTRY_LIMIT = 250
private const val TRACE_LIMIT = 64
private const val BUILD_ID_LIMIT = 32

internal fun readDiagnosticReport(stream: InputStream): DiagnosticReport {
    val bytes = ByteArray(LOG_BYTES + 1)
    var count = 0
    while (count < bytes.size) {
        val read = stream.read(bytes, count, bytes.size - count)
        if (read < 0) break
        if (read == 0) {
            val byte = stream.read()
            if (byte < 0) break
            bytes[count++] = byte.toByte()
        } else count += read
    }
    // A partial line must not become a valid diagnostic or test target.
    val length = if (count > LOG_BYTES) {
        (LOG_BYTES - 1 downTo 0).firstOrNull { bytes[it] == 10.toByte() }?.plus(1) ?: 0
    } else count
    return parseDiagnosticReport(String(bytes, 0, length, Charsets.UTF_8).removePrefix("\uFEFF"), count > LOG_BYTES)
}

private val diagnosticAnsi = Regex("""\x1b\[[0-?]*[ -/]*[@-~]""")
private val diagnosticLogcat = Regex("""^(?:\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+\s+\d+\s+\d+\s+[A-Z]\s+[^:]+:\s*|[A-Z]/[^:(]+\(\s*\d+\):\s*)""")
private val diagnosticKotlin = Regex("""^(?:[ewi]:\s+)?(.+?\.(?:kt|kts|java)):(\d+)(?::(\d+))?:?\s+(.+)$""")
private val diagnosticKotlinOld = Regex("""^[ewi]:\s+(.+?\.(?:kt|kts|java)):\s*\((\d+),\s*(\d+)\):\s*(.+)$""")
private val diagnosticGradle = Regex("""^(?:Build file|Settings file|Script) ['"](.+?\.(?:gradle|gradle\.kts))['"] line: (\d+)$""")
private val diagnosticStack = Regex("""^at (?:[^ /]+/)?([\w.$]+)\.([^.(]+)\(([^():]+\.(?:kt|kts|java)):(\d+)\)$""")
private val diagnosticException = Regex("""^(?:(?:Caused by: |Suppressed: |Exception in thread "[^"]+" )?)(?:[\w$]+\.)*[\w$]*(?:Exception|Error|Throwable)(?::.*)?$""")
private val diagnosticBuildId = Regex("""\(BuildId:\s*([0-9a-fA-F]{8,128})\)""")
private val diagnosticNativeFrame = Regex("""^#\d{2,3}\s+pc\s+[0-9a-fA-F]+\s+.+$""")
private val diagnosticTestTask = Regex(""":(?:[A-Za-z0-9][A-Za-z0-9_-]*:)*(?:test|test[A-Z][A-Za-z0-9]*UnitTest)""")
private val diagnosticTestClass = Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")
private val diagnosticTestMethod = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
private val diagnosticJUnitHeader = Regex("""There (?:was 1 failure|were \d+ failures):""")
private val diagnosticTestFailed = Regex("""(\S+) > (\S+) FAILED""")
private val diagnosticJUnitFailed = Regex("""(?:\d+\) )?([^\s(]+)\(([^()]+)\)""")
private val diagnosticTaskLine = Regex("""> Task (\S+)(?: FAILED)?""")

internal fun parseDiagnosticReport(text: String, inputLimited: Boolean = false): DiagnosticReport {
    val entries = linkedSetOf<DiagnosticEntry>()
    val buildIds = linkedSetOf<String>()
    var limited = inputLimited
    var task: String? = null
    var junitFailures = false
    var pending: DiagnosticEntry? = null
    val trace = mutableListOf<DiagnosticFrame>()

    fun add(entry: DiagnosticEntry) {
        if (entries.size < ENTRY_LIMIT) entries.add(entry)
        else if (entry !in entries) limited = true
    }
    fun flush() {
        pending?.let { entry ->
            val target = entry.target
            val matches = if (target != null && '.' !in target.className) trace.mapNotNull { frame ->
                diagnosticStack.matchEntire(frame.text)?.destructured?.let { (qualified, method) ->
                    qualified.takeIf { it.substringAfterLast('.') == target.className && method == target.method }
                }
            }.distinct() else emptyList()
            val resolved = if (target != null && matches.size == 1) target.copy(className = matches.single()) else target
            add(entry.copy(trace = trace.toList(), target = resolved?.takeIf(DiagnosticTestTarget::isValid)))
        }
        pending = null
        trace.clear()
    }
    fun append(frame: DiagnosticFrame) {
        if (trace.size >= TRACE_LIMIT) limited = true
        else if (trace.lastOrNull() != frame) trace.add(frame)
    }

    for ((index, raw) in text.lineSequence().withIndex()) {
        if (index >= LOG_LINES) {
            limited = true
            break
        }
        if (raw.length > LINE_LENGTH) {
            limited = true
            flush()
            task = null
            junitFailures = false
            continue
        }
        val line = diagnosticLogcat.replaceFirst(diagnosticAnsi.replace(raw, ""), "").trim()
        if (line.isEmpty()) continue
        val taskMatch = diagnosticTaskLine.matchEntire(line)
        if (line.startsWith("> Task ") || line.startsWith("FAILURE:") || line.startsWith("BUILD ")) {
            flush()
            task = taskMatch?.groupValues?.get(1)?.takeIf(diagnosticTestTask::matches)
            junitFailures = false
            continue
        }
        if (diagnosticJUnitHeader.matches(line)) {
            junitFailures = true
            continue
        }
        val gradleFailure = diagnosticTestFailed.matchEntire(line)
        val junitFailure = if (junitFailures) diagnosticJUnitFailed.matchEntire(line) else null
        if (gradleFailure != null || junitFailure != null) {
            flush()
            val target = gradleFailure?.destructured?.let { (name, method) ->
                task?.let { DiagnosticTestTarget(it, name, method.removeSuffix("()")) }
                    ?.takeIf(DiagnosticTestTarget::isValid)
            }
            pending = DiagnosticEntry(line, target = target)
            continue
        }
        val location = compilerEntry(line)
        if (location != null) {
            flush()
            add(location)
            continue
        }
        if (diagnosticException.matches(line)) {
            val head = pending
            // 条件拆开写：原先一行里两种括号混在一起，编译器读不出分组边界。
            val continuesPreviousTrace =
                head != null &&
                    !line.startsWith("Caused by:") &&
                    !line.startsWith("Suppressed:") &&
                    (trace.isNotEmpty() || diagnosticException.matches(head.summary))
            if (head == null || continuesPreviousTrace) {
                flush()
                pending = DiagnosticEntry(line)
            } else append(DiagnosticFrame(line))
            continue
        }
        if (line.startsWith("at ")) {
            if (pending == null) pending = DiagnosticEntry(line, stackSource(line))
            else append(DiagnosticFrame(line, stackSource(line)))
            continue
        }
        if (line.startsWith("... ") && line.endsWith(" more")) {
            if (pending != null) append(DiagnosticFrame(line))
            continue
        }
        if (diagnosticNativeFrame.matches(line)) {
            diagnosticBuildId.find(line)?.groupValues?.get(1)?.lowercase()?.let { id ->
                if (buildIds.size < BUILD_ID_LIMIT) buildIds.add(id)
                else if (id !in buildIds) limited = true
            }
            add(DiagnosticEntry(line))
            continue
        }
        flush()
        if (line.startsWith("Tests run:") || line.startsWith("FAILURES!!!")) junitFailures = false
    }
    flush()
    return DiagnosticReport(entries.toList(), buildIds.toList(), limited)
}

private fun compilerEntry(line: String): DiagnosticEntry? {
    val match = diagnosticKotlinOld.matchEntire(line) ?: diagnosticKotlin.matchEntire(line)
    if (match != null) {
        val (path, lineNumber, column, detail) = match.destructured
        val source = diagnosticSource(path, lineNumber, column) ?: return null
        return DiagnosticEntry(detail, source)
    }
    val (path, number) = diagnosticGradle.matchEntire(line)?.destructured ?: return null
    return DiagnosticEntry(line, diagnosticSource(path, number))
}

private fun diagnosticSource(path: String, line: String, column: String = ""): DiagnosticSource? {
    val localPath = try {
        if (path.startsWith("file:")) File(URI(path)).path else path
    } catch (_: IllegalArgumentException) {
        return null
    } catch (_: URISyntaxException) {
        return null
    }
    val lineNumber = line.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val colNumber = if (column.isEmpty()) 1 else column.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return DiagnosticSource(localPath, lineNumber, colNumber)
}

private fun stackSource(line: String): DiagnosticSource? {
    val (qualifiedClass, _, file, number) = diagnosticStack.matchEntire(line)?.destructured ?: return null
    val packagePath = qualifiedClass.substringBeforeLast('.', "").replace('.', '/')
    return diagnosticSource(if (packagePath.isEmpty()) file else "$packagePath/$file", number)
}

internal fun DiagnosticTestTarget.isValid(): Boolean =
    task.length <= 160 && className.length <= 200 && method.length <= 80 &&
        diagnosticTestTask.matches(task) && diagnosticTestClass.matches(className) && diagnosticTestMethod.matches(method)

internal fun diagnosticWorkspace(workspace: String): File? = diagnosticFileAccess {
    val file = File(workspace)
    if (file.isAbsolute && file.isDirectory) file.canonicalFile else null
}

private fun diagnosticContainedFile(root: File, candidate: File): File? = diagnosticFileAccess {
    val canonical = candidate.canonicalFile
    canonical.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile && it.canRead() }
}

internal fun recheckDiagnosticSource(workspace: String, file: File): File? =
    diagnosticWorkspace(workspace)?.let { diagnosticContainedFile(it, file) }?.takeIf { it == file }

internal fun resolveDiagnosticSources(root: File?, sources: Set<DiagnosticSource>): Map<DiagnosticSource, File> {
    root ?: return emptyMap()
    val result = mutableMapOf<DiagnosticSource, File>()
    val unresolved = sources.groupBy { it.path }.filter { (path, coordinates) ->
        val candidate = File(path)
        val safe = path.none { it.isISOControl() } && path.split('/', '\\').none { it == ".." }
        val exact = if (!safe) null else diagnosticContainedFile(root, if (candidate.isAbsolute) candidate else File(root, path))
        if (exact != null) coordinates.forEach { result[it] = exact }
        safe && exact == null && !candidate.isAbsolute && '\\' !in path && ':' !in path
    }
    if (unresolved.isEmpty()) return result
    val byName = unresolved.keys.groupBy { it.substringAfterLast('/') }
    val matches = mutableMapOf<String, File?>()
    val tree = root.walkTopDown().maxDepth(16).onEnter { directory ->
        directory == root || (directory.name !in diagnosticIgnoredDirs &&
            diagnosticFileAccess { directory.canonicalFile == directory.absoluteFile } == true)
    }.iterator()
    repeat(20_000) {
        if (!tree.hasNext()) {
            for ((path, file) in matches) if (file != null) unresolved.getValue(path).forEach { result[it] = file }
            return result
        }
        val file = tree.next()
        val relative = file.relativeTo(root).invariantSeparatorsPath
        for (path in byName[file.name].orEmpty()) {
            if (relative.endsWith("/$path") && file.isFile) {
                val local = diagnosticContainedFile(root, file) ?: continue
                matches[path] = if (path in matches) null else local
            }
        }
    }
    // An incomplete search cannot establish uniqueness.
    return result
}

private val diagnosticIgnoredDirs = setOf(".git", ".gradle", ".idea", "build", "node_modules", ".kotlin")

internal data class DiagnosticRunPlan(val root: File, val argv: List<String>)

internal fun prepareDiagnosticRun(root: File?, target: DiagnosticTestTarget, java: File?): DiagnosticRunPlan? {
    if (root == null || java == null || !java.isAbsolute || !java.isFile || !java.canExecute() || !target.isValid()) return null
    val settings = listOf("settings.gradle.kts", "settings.gradle").any { diagnosticContainedFile(root, File(root, it)) != null }
    if (!settings) return null
    val wrapper = diagnosticContainedFile(root, File(root, "gradle/wrapper/gradle-wrapper.jar")) ?: return null
    diagnosticContainedFile(root, File(root, "gradle/wrapper/gradle-wrapper.properties")) ?: return null
    val module = target.task.substringBeforeLast(':').removePrefix(":").replace(':', '/')
    val project = if (module.isEmpty()) root else File(root, module)
    if (listOf("build.gradle.kts", "build.gradle").none { diagnosticContainedFile(root, File(project, it)) != null }) return null
    val classPath = target.className.replace('.', '/')
    val taskName = target.task.substringAfterLast(':')
    val variant = taskName.removePrefix("test").removeSuffix("UnitTest")
    val sourceSets = if (variant.isEmpty()) listOf("test") else listOf("test", "test$variant")
    if (sourceSets.none { sourceSet -> listOf("kotlin/$classPath.kt", "java/$classPath.java", "java/$classPath.kt").any {
        diagnosticContainedFile(root, File(project, "src/$sourceSet/$it")) != null
    } }) return null
    return DiagnosticRunPlan(root, listOf(java.path, "-classpath", wrapper.path, "org.gradle.wrapper.GradleWrapperMain",
        "--no-daemon", "--console=plain", target.task, "--rerun", "--tests", "${target.className}.${target.method}"))
}

private inline fun <T> diagnosticFileAccess(block: () -> T?): T? = try {
    block()
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
