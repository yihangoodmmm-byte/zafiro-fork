package com.niki914.zafiro.app.workbench

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.service.requireService
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ProjectVirtualDisplay private constructor(
    private val handle: VirtualDisplay,
) : AutoCloseable {
    val id = handle.display.displayId
    private val tenant = AgentTenantContext("workbench-display-" + UUID.randomUUID())
    @Volatile private var closed = false

    val valid: Boolean get() = !closed && id > Display.DEFAULT_DISPLAY && handle.display.isValid

    override fun close() {
        if (closed) return
        closed = true
        handle.release()
    }

    suspend fun launch(component: ComponentName, userId: Int): Boolean {
        val name = component.flattenToString()
        if (userId < 0 || !COMPONENT.matches(name)) return false
        return shell { session ->
            val before = placements(session, component.packageName) ?: return@shell false
            if (before.any { it != id }) return@shell false
            val output = execute(session, "am start -W --user $userId --display $id -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n '$name'")
                ?: return@shell false
            if (output.lineSequence().any { it.trimStart().startsWith("Error", ignoreCase = true) }) return@shell false
            placements(session, component.packageName) == setOf(id)
        }
    }

    suspend fun gesture(x: Int, y: Int, endX: Int, endY: Int, duration: Long): Boolean {
        if (x !in 0 until WIDTH || endX !in 0 until WIDTH || y !in 0 until HEIGHT || endY !in 0 until HEIGHT) return false
        val motion = if (x == endX && y == endY) "tap $x $y" else
            "swipe $x $y $endX $endY ${duration.coerceIn(80, 1000)}"
        return shell { execute(it, "input touchscreen -d $id $motion") != null }
    }

    suspend fun back(): Boolean = shell { execute(it, "input -d $id keyevent KEYCODE_BACK") != null }

    private suspend fun placements(session: String, packageName: String): Set<Int>? {
        val dump = execute(session, "dumpsys activity activities") ?: return null
        val result = mutableSetOf<Int>()
        val member = Regex("[\\s:]" + Regex.escape(packageName) + "/")
        var current: Int? = null
        var sawTarget = false
        for (line in dump.lineSequence()) {
            val display = HEADER.find(line)
            if (display != null) {
                current = display.groupValues[1].toIntOrNull()
                if (current == id) sawTarget = true
            } else if ("ActivityRecord{" in line && member.containsMatchIn(line)) {
                current?.let(result::add)
            }
        }
        return result.takeIf { sawTarget }
    }

    private suspend fun shell(action: suspend (String) -> Boolean): Boolean = withContext(tenant) {
        if (!valid || !hasPermission()) return@withContext false
        val opened = TerminalSessionPool.open(identity = "shizuku") as? TerminalOpenOutcome.Success
            ?: return@withContext false
        try {
            valid && hasPermission() && action(opened.session) && valid
        } finally {
            withContext(NonCancellable) { TerminalSessionPool.close(opened.session) }
        }
    }

    private suspend fun execute(session: String, command: String): String? {
        if (!valid || !hasPermission()) return null
        val result = TerminalSessionPool.executeBlocking(session, command, 5_000) as? TerminalCommandOutcome.Success
            ?: return null
        if (result.result.exitCode != 0 || !result.result.stderr.isEmpty) return null
        return result.result.stdout.toByteArray().decodeToString()
    }

    companion object {
        const val WIDTH = 540
        const val HEIGHT = 960
        private const val DENSITY = 240
        // Framework flag: remove tasks with their display rather than moving them to the default display.
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val SUPPORTS_TOUCH = 1 shl 6

        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        private val HEADER = Regex("""^\s*Display #([0-9]+)\b""")
        private val COMPONENT = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")

        fun hasPermission(): Boolean = requireService<PermissionManager>().status(Permission.SHIZUKU) == PermissionState.GRANTED

        fun create(context: Context, surface: Surface, onDisplayStopped: () -> Unit): ProjectVirtualDisplay? {
            if (!supported || !surface.isValid || !hasPermission()) return null
            val manager = context.getSystemService(DisplayManager::class.java) ?: return null
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DESTROY_CONTENT_ON_REMOVAL or SUPPORTS_TOUCH
            val display = manager.createVirtualDisplay(
                "Zafiro Virtual Display", WIDTH, HEIGHT, DENSITY, surface, flags,
                object : VirtualDisplay.Callback() { override fun onStopped() = onDisplayStopped() },
                Handler(Looper.getMainLooper()),
            ) ?: return null
            val session = ProjectVirtualDisplay(display)
            if (session.valid) return session
            session.close()
            return null
        }
    }
}
