package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.AgentTenantContext
import com.niki914.zafiro.chat.agentic.ToolExecutionPreflight
import com.niki914.zafiro.chat.agentic.shell.SessionRegistry
import com.niki914.zafiro.chat.agentic.shell.SessionMeta
import com.niki914.zafiro.chat.agentic.shell.TerminalOpenOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.coroutineContext

/**
 * 本地持久化会话终端工具 (shell)。
 * 默认自动开辟并持久复用 alias="default" 的本地交互式会话，支持工作目录和环境变量的连续保持。
 * 同时支持通过 alias 开辟独立隔离会话，并通过 ToolExecutionPreflight 进行权限前检。
 */
open class ShellBuiltin(
    preflight: ToolExecutionPreflight = ToolExecutionPreflight(),
) : BaseSessionBuiltin(preflight) {

    override val name: String = "shell"
    private val sessionResolutionMutex = Mutex()

    // The registry is process-wide. Bind aliases AND handles to both the agent and its project.
    override suspend fun currentSessionScope(): String {
        val context = coroutineContext[AgentTenantContext] ?: return sessionScope
        return if (context.tenantId.isBlank() && context.workspacePath == null) sessionScope
        else "$sessionScope:${context.tenantId}:${context.workspacePath.orEmpty()}"
    }

    override val description: String =
        "Execute commands in a local Android shell environment with persistent session state. " +
                "Working directory, exported environment variables, and shell history persist between calls.\n\n" +
                "Default session: If 'session' and 'alias' are omitted, commands reuse the default session for this conversation and bound workspace.\n" +
                "Custom session: Provide 'alias' to create or target an isolated session (e.g. alias='build_worker').\n" +
                "Execution mode: By default, commands run synchronously and return upon completion. If execution exceeds 'timeout' seconds, it degrades to background execution (status='running', timed_out=true); use action='read' to poll output later.\n" +
                "Identity: Choose 'user' (default local shell), 'root' (via su), or 'shizuku'. The identity is fixed when a session is created; passing it for an existing session reuses that session only if the identity matches, otherwise the call fails.\n\n" +
                "IMPORTANT RULES:\n" +
                "1. Never run blocking interactive commands without non-interactive flags (e.g. do not run top, vim, nano, or unmetered ping).\n" +
                "2. For long-running tasks, prefer setting a larger 'timeout' upfront (e.g. timeout=60). A command that exceeds 'timeout' keeps running in the background; collect its output later with action='read' instead of polling in a tight loop.\n" +
                "3. A session with a command still in flight rejects new commands. The local shell has no PTY, so Ctrl-C (\\u0003) is NOT a real interrupt here; if a command is stuck, close the session with action='close' and start a new one with a different alias.\n" +
                "4. To send interactive input to a running session, use action='write' or action='submit'."

    override val inputSchemaJson: String get() = SHELL_SCHEMA

    override fun parseArguments(argumentsJson: String): ShellArgs {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val action = obj["action"]?.jsonPrimitive?.content?.let { SessionAction.fromWireName(it) }
        val session = obj["session"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val alias = obj["alias"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val command = obj["command"]?.jsonPrimitive?.content
        val identity =
            obj["identity"]?.jsonPrimitive?.content?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val workdir = obj["workdir"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)
        val text = obj["text"]?.jsonPrimitive?.content
        val background = obj["background"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeout = obj["timeout"]?.jsonPrimitive?.longOrNull
        return ShellArgs(
            command = command,
            session = session,
            alias = alias,
            identity = identity,
            workdir = workdir,
            action = action,
            text = text,
            background = background,
            timeout = timeout,
        )
    }

    override suspend fun preflightPathAccess(command: String, args: BaseSessionArgs) {
        val workdir = (args as? ShellArgs)?.workdir ?: coroutineContext[AgentTenantContext]?.workspacePath
        preflight.ensurePathAccess(command + " " + workdir.orEmpty())
    }

    override suspend fun resolveSessionTarget(args: BaseSessionArgs): SessionTarget =
        sessionResolutionMutex.withLock { resolveLocalTarget(args) }

    private suspend fun resolveLocalTarget(args: BaseSessionArgs): SessionTarget {
        val shellArgs = args as? ShellArgs
        val requestedWorkdir = shellArgs?.workdir
        if (requestedWorkdir != null && !requestedWorkdir.startsWith('/')) {
            return SessionTarget.Error(TerminalToolResponse.invalidRequest("workdir must be an absolute path."))
        }
        val scope = currentSessionScope()
        val workspace = coroutineContext[AgentTenantContext]?.workspacePath
        val workdir = requestedWorkdir ?: workspace
        val explicitSession = args.session
        if (explicitSession != null) {
            // 只认本域句柄：池是进程共享的，回查池会把 ssh 会话交给本地 shell 工具执行
            val resolved = sessionRegistry.resolveSessionId(explicitSession, scope)
                ?: return SessionTarget.Error(
                    TerminalToolResponse.invalidRequest("Session '$explicitSession' not found.")
                )
            if (!isSessionAlive(resolved)) {
                sessionRegistry.unregister(resolved)
                TerminalSessionPool.close(resolved)
                return SessionTarget.Error(TerminalToolResponse.sessionGone(explicitSession))
            }
            val meta = sessionRegistry.findById(resolved)
            identityMismatchOrNull(meta, explicitSession, shellArgs?.identity)?.let { return it }
            workdirMismatchOrNull(meta, requestedWorkdir)?.let { return it }
            return SessionTarget.Resolved(resolved)
        }

        // null 表示未指定：既有会话按自身身份复用，新会话按 user 建
        val identity = shellArgs?.identity ?: DEFAULT_IDENTITY
        val explicitAlias = args.alias
        if (explicitAlias != null) {
            val existing = sessionRegistry.findByAlias(explicitAlias, scope)
            if (existing != null) {
                if (isSessionAlive(existing.sessionId)) {
                    identityMismatchOrNull(existing, explicitAlias, shellArgs?.identity)?.let { return it }
                    workdirMismatchOrNull(existing, requestedWorkdir)?.let { return it }
                    return SessionTarget.Resolved(existing.sessionId)
                }
                // 登记项是僵尸（会话被回收/池被清）：释放别名后走下方重新开辟，本地会话重建零成本
                sessionRegistry.unregister(existing.sessionId)
                TerminalSessionPool.close(existing.sessionId)
            }

            return when (val outcome = openLocalSession(identity = identity, workdir = workdir)) {
                is TerminalOpenOutcome.Success -> {
                    sessionRegistry.register(
                        sessionId = outcome.session,
                        alias = explicitAlias,
                        scope = scope,
                        metadata = sessionMetadata(identity, workdir),
                    )
                    SessionTarget.Resolved(outcome.session)
                }

                is TerminalOpenOutcome.Failure -> {
                    SessionTarget.Error(
                        TerminalToolResponse.invalidRequest(
                            outcome.failure.message
                                ?: "Failed to open shell session for alias '$explicitAlias'."
                        )
                    )
                }

                is TerminalOpenOutcome.InvalidRequest -> {
                    SessionTarget.Error(TerminalToolResponse.invalidRequest(outcome.message))
                }
            }
        }

        val defaultAlias = defaultAliasFor(identity)
        // An explicit alternative directory must not take over the implicit workspace session.
        val alias = if (requestedWorkdir == null || requestedWorkdir == workspace) defaultAlias
        else "$defaultAlias:$requestedWorkdir"
        return openDefaultSession(identity, workdir, alias, scope)
    }

    private fun sessionMetadata(identity: String, workdir: String?): Map<String, String> = buildMap {
        put(SessionRegistry.META_IDENTITY, identity)
        if (workdir != null) put(META_WORKDIR, workdir)
    }

    private fun workdirMismatchOrNull(meta: SessionMeta?, requested: String?): SessionTarget.Error? {
        if (requested == null || meta?.metadata?.get(META_WORKDIR) == requested) return null
        return SessionTarget.Error(TerminalToolResponse.invalidRequest(
            "workdir is fixed when opening a shell session. Omit it to reuse the session, or use a new alias.",
        ))
    }

    /**
     * 会话身份在开辟时固化，后续传 identity 不会提权。显式声明了冲突身份时报错，
     * 而不是静默复用一个身份不符的会话（用户以为提权了，命令其实仍以旧身份执行）。
     */
    private fun identityMismatchOrNull(
        meta: SessionMeta?,
        label: String,
        declared: String?,
    ): SessionTarget.Error? {
        val actual = meta?.metadata?.get(SessionRegistry.META_IDENTITY)
        return if (actual != null && declared != null && declared != actual) {
            SessionTarget.Error(TerminalToolResponse.identityMismatch(label, actual, declared))
        } else {
            null
        }
    }

    /**
     * 未指定 session / alias 时的会话：按身份分池，user 沿用 `default`，其余身份各用一个别名。
     * 这样 `identity=root` 真的会拿到 root shell，而不是静默复用 user 会话。
     */
    private suspend fun openDefaultSession(
        identity: String,
        workdir: String?,
        alias: String,
        scope: String,
    ): SessionTarget {
        val existing = sessionRegistry.findByAlias(alias, scope)
        if (existing != null) {
            if (isSessionAlive(existing.sessionId)) {
                identityMismatchOrNull(existing, alias, identity)?.let { return it }
                workdirMismatchOrNull(existing, workdir)?.let { return it }
                return SessionTarget.Resolved(existing.sessionId)
            }
            sessionRegistry.unregister(existing.sessionId)
            TerminalSessionPool.close(existing.sessionId)
        }

        return when (val outcome = openLocalSession(identity = identity, workdir = workdir)) {
            is TerminalOpenOutcome.Success -> {
                sessionRegistry.register(
                    sessionId = outcome.session,
                    alias = alias,
                    scope = scope,
                    metadata = sessionMetadata(identity, workdir),
                )
                SessionTarget.Resolved(outcome.session)
            }

            is TerminalOpenOutcome.Failure -> SessionTarget.Error(
                TerminalToolResponse.invalidRequest(
                    outcome.failure.message ?: "Failed to open $identity shell session."
                )
            )

            is TerminalOpenOutcome.InvalidRequest ->
                SessionTarget.Error(TerminalToolResponse.invalidRequest(outcome.message))
        }
    }

    protected open suspend fun openLocalSession(
        identity: String,
        workdir: String?
    ): TerminalOpenOutcome {
        return TerminalSessionPool.open(
            identity = identity,
            cwd = workdir,
            collectOutput = true,
        )
    }

    companion object {
        private const val LOG_TAG = "niki914_zafiro_ShellBuiltin"
        private const val META_WORKDIR = "initial_workdir"
        const val DEFAULT_ALIAS = "default"
        const val DEFAULT_IDENTITY = "user"
        private const val DEFAULT_TIMEOUT_SEC = 30L

        /** 未指定 alias 时的会话别名：user 保持 `default`，其余身份各占一个，互不串味。 */
        private fun defaultAliasFor(identity: String): String =
            if (identity == DEFAULT_IDENTITY) DEFAULT_ALIAS else identity

        private val SHELL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Shell command to execute. Output and environment persist in the session."
                },
                "session": {
                  "type": "string",
                  "description": "Session ID or alias to target. If omitted along with alias, reuses the persistent default session (alias='default')."
                },
                "alias": {
                  "type": "string",
                  "description": "Human-friendly alias for session naming and isolation (e.g. 'dev', 'compiler'). Creates a new session if it does not exist."
                },
                "identity": {
                  "type": "string",
                  "enum": ["user", "root", "shizuku"],
                  "description": "Identity for a session this call creates (default 'user'). It is fixed at session creation: passing it for an existing session only reuses that session if the identity matches, otherwise the call fails. When 'session'/'alias' are omitted, the default session is chosen by identity (user, root and shizuku each keep their own)."
                },
                "workdir": {
                  "type": "string",
                  "description": "Initial working directory (absolute path). If omitted, a new session starts in the bound workspace when set. A running session keeps its own cwd."
                },
                "timeout": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Max seconds to wait synchronously before degrading to background (default 30). Set larger (e.g. 60 or 120) for known longer operations."
                },
                "background": {
                  "type": "boolean",
                  "description": "Run the command in background immediately and return status='running'. Avoid tight polling; use action='read' to collect output when the task should be done."
                },
                "action": {
                  "type": "string",
                  "enum": ["list", "read", "submit", "write", "close"],
                  "description": "Session action: 'read', 'submit', 'write', 'close', 'list'."
                },
                "text": {
                  "type": "string",
                  "description": "Text to send for 'submit' or 'write' actions."
                }
              },
              "required": []
            }
        """.trimIndent()
    }
}

class ShellArgs(
    command: String? = null,
    session: String? = null,
    alias: String? = null,
    override val identity: String? = null,
    val workdir: String? = null,
    action: SessionAction? = null,
    text: String? = null,
    timeout: Long? = null,
    background: Boolean = false,
) : BaseSessionArgs(
    command = command,
    session = session,
    alias = alias,
    action = action,
    text = text,
    timeout = timeout,
    background = background,
)
