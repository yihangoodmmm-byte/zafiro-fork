package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.error.RetryPolicy
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.hooks.SerializationHolder
import com.niki914.okia.message.ThinkingLevel
import com.niki914.okia.message.contextTokens
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.model.ContextCompaction
import com.niki914.zafiro.api.model.ChatTurnOptions
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.api.model.WorkspaceDirectory
import com.niki914.zafiro.platform.browser.BrowserService
import com.niki914.zafiro.service.findService
import com.niki914.zafiro.chat.compaction.Compactor
import com.niki914.zafiro.chat.compaction.ContextPruner
import com.niki914.zafiro.chat.compaction.RequestContextBudget
import com.niki914.zafiro.chat.agentic.ChatTurnPrompt
import com.niki914.zafiro.chat.agentic.IngestedImage
import com.niki914.zafiro.chat.prompt.PromptComposeContext
import com.niki914.zafiro.chat.prompt.PromptComposer
import com.niki914.zafiro.chat.prompt.PromptSkill
import com.niki914.zafiro.chat.agentic.ToolManager
import com.niki914.zafiro.chat.agentic.WorkflowPlanTool
import com.niki914.zafiro.chat.agentic.WorkspaceAcceptanceTool
import com.niki914.zafiro.chat.agentic.WorkspaceTurnReviews
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.model.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.api.util.runCatchingCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 独立的 Agent 底层会话引擎。
 *
 * 每个 Agent 实例独立持有一份：
 * - 独立的底层 Okia 会话树与 Hooks（[OkiaSessionAssembler]）；
 * - 独立的工具注册表（[ToolRegistrySynchronizer]）；
 * - 独立的流执行管道与回合状态（[LlmStreamPipeline]）。
 */
class AgentSessionEngine {
    companion object {
        const val LOG_TAG = "niki914_zafiro_AgentSessionEngine"
        const val NO_IDLE_TIMEOUT_SECONDS = Long.MAX_VALUE / 1000
    }

    fun interface OkiaFactory {
        suspend fun create(
            protocol: LlmProtocol,
            restore: SessionSnapshot?,
            config: ResolvedLlmConfig,
        ): Okia
    }

    val tenantId: String = UUID.randomUUID().toString()

    var agentControlProvider: (() -> AgentControl?)? = null

    private var runtimeState: RuntimeState? = null
    private val executionMutex = Mutex()

    // Okia runs tools in its own coroutine scope. Pass the accepted turn's path through the
    // per-engine executor, rather than relying on coroutine context from the caller of send().
    @Volatile
    private var activeWorkspacePath: String? = null

    private val imageManager = ImageIngestionManager()
    private val toolSynchronizer = ToolRegistrySynchronizer(
        tenantId = tenantId,
        agentControlProvider = { agentControlProvider?.invoke() },
        workspacePathProvider = { activeWorkspacePath },
    ) { runtimeState?.snapshot?.tools }
    private val prefixComposer = TurnPrefixComposer()
    internal val sessionAssembler = OkiaSessionAssembler(
        toolRegistry = toolSynchronizer.toolRegistry,
        imageLoader = imageManager.imageLoader,
        ensureImageSaver = imageManager::ensureImageSaver,
        prepareContext = ::prepareRequestContext,
        contextCommitted = ::contextCommitted,
    )
    internal val streamPipeline = LlmStreamPipeline()

    // PromptComposer 现在是 object（分层组装器），不再持有实例
    private val toolManager = ToolManager()
    private val mcpRefreshScheduler =
        McpRefreshScheduler(CoroutineScope(SupervisorJob() + Dispatchers.IO))

    internal val toolRegistry: ToolRegistry get() = toolSynchronizer.toolRegistry

    var compactor: Compactor = Compactor(
        summaryGenerator = { systemPrompt, userPrompt ->
            generateSummary(systemPrompt, userPrompt)
        }
    )

    internal var okia: Okia?
        get() = sessionAssembler.activeSession
        set(value) {
            sessionAssembler.activeSession = value
        }

    internal var okiaFactory: OkiaFactory
        get() = sessionAssembler.okiaFactory
        set(value) {
            sessionAssembler.okiaFactory = value
        }

    val currentConversation: StateFlow<Conversation?> get() = sessionAssembler.currentConversation
    val keepScreenOn: StateFlow<Boolean> get() = streamPipeline.turnActive

    /** 当前生效配置的上下文窗口；[refresh] 时随配置一起落。读者：`AgentImpl.contextUsage` 的分母。 */
    private val contextWindowFlow = MutableStateFlow(DEFAULT_CONTEXT_WINDOW_TOKENS)
    val contextWindowTokens: StateFlow<Long> = contextWindowFlow.asStateFlow()

    @Volatile
    var boundConfigId: String = ""
        private set

    fun bindConfig(configId: String) {
        boundConfigId = configId
        contextRevision.value += 1
    }

    internal fun resetForTest() {
        sessionAssembler.resetForTest()
        runtimeState = null
        toolSynchronizer.reset()
        mcpRefreshScheduler.reset()
        prefixComposer.reset()
        contextWindowFlow.value = DEFAULT_CONTEXT_WINDOW_TOKENS
        boundConfigId = ""
        contextRevision.value += 1
        compactor = Compactor(
            summaryGenerator = { systemPrompt, userPrompt ->
                generateSummary(systemPrompt, userPrompt)
            }
        )
    }


    private val contextCompactionFlow = MutableStateFlow(ContextCompaction())
    val contextCompaction: StateFlow<ContextCompaction> = contextCompactionFlow.asStateFlow()

    private fun publishCompaction(
        phase: ContextCompaction.Phase,
        before: Long? = null,
        after: Long? = null,
        manual: Boolean = false,
    ) {
        contextCompactionFlow.value = ContextCompaction(
            sequence = contextCompactionFlow.value.sequence + 1,
            phase = phase,
            beforeTokens = before,
            afterTokens = after,
            manual = manual,
        )
    }

    private fun contextCommitted(request: SerializationHolder) {
        val payload = request.contextCheckpoint?.let(ContextPruner::payloadOf) ?: return
        publishCompaction(ContextCompaction.Phase.Compacted, payload.tokensBefore, payload.tokensAfter)
        contextRevision.value += 1
    }

    private suspend fun generateCompactionSummary(system: String, prompt: String): String = try {
        generateSummary(system, prompt).also {
            if (it.isBlank()) publishCompaction(ContextCompaction.Phase.Failed, manual = contextCompaction.value.manual)
        }
    } catch (cancelled: CancellationException) {
        publishCompaction(ContextCompaction.Phase.Cancelled, manual = contextCompaction.value.manual)
        throw cancelled
    } catch (failure: Exception) {
        publishCompaction(ContextCompaction.Phase.Failed, manual = contextCompaction.value.manual)
        throw failure
    }

    private suspend fun prepareRequestContext(request: SerializationHolder) {
        val raw = request.originalHistory
        val snapshot = request.snapshot
        val window = contextWindowFlow.value
        fun measure(messages: List<Message>) = RequestContextBudget.count(snapshot.systemPrompt, snapshot.tools, messages)
        val ratio = compactor.thresholdRatio
        val threshold = minOf((window * ratio).toLong(), window - snapshot.maxTokens - 512L)
        var messages = request.history
        var cost = measure(messages)
        val before = cost
        var checkpoint: Message.User? = null
        if (request.recoveringContextOverflow || ratio > 0f && threshold > 0 && cost >= threshold) {
            val limit = if (request.recoveringContextOverflow) 400 else 2400
            messages = RequestContextBudget.pruneTools(messages, limit, includeLatest = request.recoveringContextOverflow)
            cost = measure(messages)
            if (request.recoveringContextOverflow || cost >= threshold) {
                publishCompaction(ContextCompaction.Phase.Running, before = before)
                val candidate = compactor.prepare(raw, window, snapshot.maxTokens, generate = ::generateCompactionSummary)
                if (candidate != null) {
                    val projected = RequestContextBudget.pruneTools(
                        ContextPruner.prune(raw + candidate).messages,
                        limit,
                        includeLatest = request.recoveringContextOverflow,
                    )
                    val candidateCost = measure(projected)
                    if (candidateCost < cost) {
                        checkpoint = candidate
                        messages = projected
                        cost = candidateCost
                    }
                }
            }
        }
        if (checkpoint == null && cost < before) {
            publishCompaction(ContextCompaction.Phase.Pruned, before = before, after = cost)
        } else if (checkpoint == null && contextCompaction.value.phase == ContextCompaction.Phase.Running) {
            publishCompaction(ContextCompaction.Phase.Unchanged, before = before, after = cost)
        }
        request.write(snapshot, messages, "context_budget")
        request.recordContext(cost, checkpoint)
    }

    private val contextRevision = MutableStateFlow(0L)
    val contextChanges = contextRevision.asStateFlow()
    val contextReplyTokens: Int? get() = runtimeState?.snapshot?.config?.maxTokens
    val contextConfigId: String? get() = runtimeState?.configId
    val contextWindowSource: String? get() = runtimeState?.contextWindowSource

    fun estimateContext(draft: String, attachments: Int): Long? {
        val state = runtimeState?.takeIf { it.configId == boundConfigId } ?: return null
        val config = state.snapshot.config
        val raw = okia?.conversation?.value?.history?.map { it.message }.orEmpty()
        val messages = ContextPruner.prune(raw).messages
        val tools = toolRegistry.snapshot().map { it.descriptor }
        return RequestContextBudget.count(config.finalSystemPrompt, tools, messages) +
            RequestContextBudget.text(draft) + 1024L * attachments
    }

    private val refreshMutex = Mutex()

    /**
     * 预热底层 Okia 会话与 MCP 工具发现。
     * 幂等调用：无可用会话时触发首轮 refresh；已有会话时不做多余重装配。
     */
    suspend fun warmup() {
        if (runtimeState == null) {
            runCatchingCancellable {
                refresh()
            }.onFailure { throwable ->
                Logger.d(LOG_TAG, "warmup skipped or failed: ${throwable.message}")
            }
        }
    }

    suspend fun refresh(restore: SessionSnapshot? = null): LlmRuntimeSnapshot = refreshMutex.withLock {
        val refreshStartedAtMs = System.currentTimeMillis()
        val gateway = RuntimeEnvironment.awaitSettingsGateway()
        val configId = boundConfigId
        val llmConfig = gateway.readLlmConfig(configId = configId.takeIf { it.isNotBlank() })
        if (restore == null) {
            sessionAssembler.validateLlmConfig(llmConfig)
        }
        contextWindowFlow.value = llmConfig.contextWindowTokens
        compactor.thresholdRatio = llmConfig.compactionThresholdPercent / 100f
        Logger.i(
            LOG_TAG,
            "config read provider=${llmConfig.provider} model=${llmConfig.model} " +
                    "maxTokens=${llmConfig.maxTokens} contextWindow=${llmConfig.contextWindowTokens} " +
                    "hasApiKey=${llmConfig.apiKey.isNotBlank()} hasProxy=${llmConfig.proxy.isNotBlank()}"
        )
        val protocol = LlmProtocol.fromWire(llmConfig.protocol)
        val runtimeMcpServers = runCatchingCancellable {
            gateway.listMcpServers()
        }.onFailure {
            Logger.w(LOG_TAG, "listMcpServers failed: ${it.message}", it)
        }.getOrDefault(emptyList())
        val customPyTools = runCatchingCancellable {
            gateway.listCustomPyTools()
        }.onFailure {
            Logger.w(LOG_TAG, "listCustomPyTools failed: ${it.message}", it)
        }.getOrDefault(emptyList())
        val builtinSettings = runCatchingCancellable {
            gateway.listBuiltinToolSettings()
        }.onFailure {
            Logger.w(LOG_TAG, "listBuiltinToolSettings failed: ${it.message}", it)
        }.getOrDefault(emptyList())
        val enabledSkills = gateway.listEnabledSkills()
        val resolvedTools = toolManager.resolve(
            customPyTools = customPyTools,
            mcpServers = runtimeMcpServers,
            builtinSettings = builtinSettings,
        )
        Logger.i(
            LOG_TAG,
            "tools resolved builtin=${resolvedTools.builtinTools.size} " +
                    "py=${resolvedTools.customPyTools.size} " +
                    "mcpServers=${resolvedTools.mcpServers.size}"
        )
        val configWithoutRuntimePrompt = ResolvedLlmConfig(
            endpoint = llmConfig.endpoint,
            apiKey = llmConfig.apiKey,
            model = llmConfig.model,
            baseSystemPrompt = llmConfig.prompt,
            finalSystemPrompt = llmConfig.prompt,
            proxy = llmConfig.proxy,
            supportsImages = llmConfig.supportsImages,
            idleTimeoutSeconds = llmConfig.idleTimeoutSeconds,
            retryMaxAttempts = llmConfig.retryMaxAttempts,
            maxTokens = llmConfig.maxTokens,
            thinkingLevel = llmConfig.thinkingLevel.takeIf(String::isNotBlank)
                ?.let(ThinkingLevel::fromWire),
            repetitionSensitivity = llmConfig.repetitionSensitivity,
        )

        val previousSession = runtimeState?.okia
        val activeSession = sessionAssembler.obtainSession(
            protocol = protocol,
            config = configWithoutRuntimePrompt,
            restore = restore,
            forceNew = restore != null,
        )
        activeSession.update {
            endpoint = configWithoutRuntimePrompt.endpoint.ifBlank {
                sessionAssembler.protocolDefaultEndpointFallback(protocol)
            }
            apiKey = configWithoutRuntimePrompt.apiKey
            model = configWithoutRuntimePrompt.model
            idleTimeoutSeconds = configWithoutRuntimePrompt.idleTimeoutSeconds
                ?: NO_IDLE_TIMEOUT_SECONDS
            retryPolicy = RetryPolicy(maxAttempts = configWithoutRuntimePrompt.retryMaxAttempts)
            maxTokens = configWithoutRuntimePrompt.maxTokens
            thinkingLevel = configWithoutRuntimePrompt.thinkingLevel
            repetitionConfig = configWithoutRuntimePrompt.repetitionSensitivity.toRepetitionConfig()
            proxy = configWithoutRuntimePrompt.proxy
            supportsImages =
                sessionAssembler.isImageSupported(configWithoutRuntimePrompt.supportsImages)
            mcpServers = toolSynchronizer.toOkiaMcpServers(resolvedTools.mcpServers)
        }
        toolSynchronizer.syncLocalTools(resolvedTools)
        val mcpSignature = toolSynchronizer.mcpServersSignature(resolvedTools.mcpServers)
        mcpRefreshScheduler.schedule(
            activeSession,
            mcpSignature,
            force = activeSession !== previousSession || restore != null,
        )
        // 分层提示词组装（Operit fork 同款引擎）：
        // 身份是替换不是拼接——人格非空时内置默认身份完全不出现；
        // 内置段各自有独立开关，由层配置控制。
        val prompt = PromptComposer.compose(
            PromptComposeContext(
                useEnglish = !java.util.Locale.getDefault().language.startsWith("zh"),
                userPersona = llmConfig.prompt,
                builtinTools = resolvedTools.builtinTools.map { it.name },
                customPyTools = resolvedTools.customPyTools.map { it.name },
                mcpServers = resolvedTools.mcpServers.map { it.name },
                skills = enabledSkills.map { skill ->
                    PromptSkill(
                        id = skill.id,
                        name = skill.name,
                        description = skill.description,
                        absoluteDir = skill.absoluteDir,
                    )
                },
                // 工作区不在这里注入：你的 ChatTurnPrompt 已按轮把 workspacePath 写进提示词，
                // 这里只放应用私有目录。
                sandboxPaths = imageManager.sandboxPaths().toList(),
                memoryItems = buildMemoryItems(llmConfig),
            )
        )
        val finalConfig =
            configWithoutRuntimePrompt.copy(finalSystemPrompt = prompt.finalPrompt)

        return@withLock LlmRuntimeSnapshot(finalConfig, resolvedTools, prompt).also { snapshot ->
            runtimeState = RuntimeState(
                configId = configId,
                snapshot = snapshot,
                okia = activeSession,
                sessionProtocol = protocol,
                contextWindowSource = llmConfig.contextWindowSource,
            )
            contextRevision.value += 1
            Logger.i(
                LOG_TAG,
                "refresh done elapsedMs=${System.currentTimeMillis() - refreshStartedAtMs} " +
                        "model=${snapshot.config.model}"
            )
        }
    }

    suspend fun snapshot(): LlmRuntimeSnapshot? = runtimeState?.snapshot

    /**
     * 确保存在一个可用会话实例（无则建空实例）并返回其树 id。
     */
    suspend fun ensureSession(): String {
        if (okia == null) {
            refresh()
        }
        return okia?.conversation?.value?.id
            ?: error("session not available")
    }

    /**
     * 恢复会话：以快照重建实例。
     */
    suspend fun openSession(restore: SessionSnapshot) {
        val startedAtMs = System.currentTimeMillis()
        prefixComposer.reset()
        findService<BrowserService>()?.closeOwner(tenantId)
        Logger.i(
            LOG_TAG,
            "open session id=${restore.id} entries=${restore.entries.size} started"
        )
        refresh(restore = restore)
        Logger.i(
            LOG_TAG,
            "open session done id=${restore.id} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
    }

    fun stream(
        query: String,
        images: List<ContentBlock.Image> = emptyList(),
        files: List<FileRef> = emptyList(),
        options: ChatTurnOptions = ChatTurnOptions(),
    ): Flow<LlmStreamEvent> = channelFlow {
        if (!executionMutex.tryLock()) {
            send(LlmStreamEvent.Error(message = "A turn is already running.", code = LlmErrorCode.TurnConflict))
            return@channelFlow
        }
        try {
            val workspace = options.workspacePath?.let { path ->
                WorkspaceDirectory.resolve(path) ?: run {
                    send(LlmStreamEvent.Error(message = "The bound workspace is unavailable. Rebind it in chat options."))
                    return@channelFlow
                }
            }
            val state = try {
                refresh()
                runtimeState
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                runtimeState?.takeIf { it.configId == boundConfigId } ?: run {
                    val code = throwable.toUserErrorCode()
                    val message = throwable.message?.trim()?.ifEmpty { null }
                    Logger.e(
                        LOG_TAG,
                        "refresh failed errorType=${throwable.eventTypeName()} message=$message"
                    )
                    send(
                        LlmStreamEvent.Error(
                            message = message,
                            throwable = throwable,
                            code = code,
                        )
                    )
                    return@channelFlow
                }
            }
            if (state == null) {
                send(LlmStreamEvent.Error(message = null, code = null))
                return@channelFlow
            }
            Logger.i(
                LOG_TAG,
                "refresh ok model=${state.snapshot.config.model} " +
                        "builtin=${state.snapshot.tools.builtinTools.size} " +
                        "py=${state.snapshot.tools.customPyTools.size} " +
                        "mcp=${state.snapshot.tools.mcpServers.size}"
            )

            val notifications = TerminalSessionPool.drainPendingNotifications()
            val mcpNotice = prefixComposer.mcpNotice(state.okia)
            val injection = prefixComposer.buildInjectionPrefixes(files, mcpNotice, notifications)
            if (injection != null) {
                Logger.i(
                    LOG_TAG,
                    "prefixes injected files=${files.size} mcp=${mcpNotice != null} " +
                            "notifications=${notifications.size} " +
                            "chars=${injection.length}"
                )
            }
            val effectiveQuery = if (injection != null) {
                injection + "\n\n" + query
            } else {
                query
            }

            activeWorkspacePath = workspace
            val planDescriptor = WorkflowPlanTool.descriptor
            val plan = if (options.workflowEnabled && toolRegistry.find(planDescriptor.wireName) == null) {
                WorkflowPlanTool().also { toolRegistry.register(planDescriptor, it) }
            } else {
                null
            }
            val acceptanceDescriptor = WorkspaceAcceptanceTool.descriptor
            val acceptance = workspace?.takeIf { options.workflowEnabled && toolRegistry.find(acceptanceDescriptor.wireName) == null }
                ?.let { WorkspaceAcceptanceTool(it).also { tool -> toolRegistry.register(acceptanceDescriptor, tool) } }
            var review: WorkspaceTurnReviews.Capture? = null
            var interrupted = true
            try {
                review = workspace?.let { WorkspaceTurnReviews.begin(it) }
                streamPipeline.execute(
                    session = state.okia,
                    query = effectiveQuery,
                    images = images,
                    systemPrompt = ChatTurnPrompt.compose(
                        base = state.snapshot.config.finalSystemPrompt,
                        options = options.copy(workspacePath = workspace),
                        planToolName = planDescriptor.wireName.takeIf { plan != null },
                    ),
                    thinkingLevel = options.reasoningEffort?.let { ThinkingLevel.fromWire(it.wireValue) },
                    channel = this,
                )
                interrupted = false
            } finally {
                if (plan != null && toolRegistry.find(planDescriptor.wireName)?.executor === plan) {
                    toolRegistry.remove(planDescriptor.wireName)
                }
                if (acceptance != null && toolRegistry.find(acceptanceDescriptor.wireName)?.executor === acceptance) {
                    toolRegistry.remove(acceptanceDescriptor.wireName)
                }
                val capture = review
                if (workspace != null && capture != null) withContext(NonCancellable) {
                    WorkspaceTurnReviews.finish(workspace, capture, interrupted)
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                throw throwable
            }
            Logger.e(
                LOG_TAG,
                "stream error stage=outer code=${throwable.toUserErrorCode()} " +
                        "errorType=${throwable.eventTypeName()} " +
                        "message=${throwable.message}"
            )
            send(
                LlmStreamEvent.Error(
                    message = throwable.message?.trim()?.ifEmpty { null },
                    throwable = throwable,
                    code = throwable.toUserErrorCode(),
                )
            )
        } finally {
            activeWorkspacePath = null
            executionMutex.unlock()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun closeSession() {
        Logger.i(LOG_TAG, "close session requested tenantId=$tenantId")
        TerminalSessionPool.closeTenantSessions(tenantId)
        findService<BrowserService>()?.closeOwner(tenantId)
        sessionAssembler.closeSession()
        prefixComposer.reset()
        runtimeState = null
        boundConfigId = ""
        Logger.i(LOG_TAG, "close session done")
    }

    suspend fun resetConversation() {
        Logger.i(LOG_TAG, "reset conversation requested")
        findService<BrowserService>()?.closeOwner(tenantId)
        sessionAssembler.resetConversation()
        prefixComposer.reset()
        runtimeState = null
        boundConfigId = ""
        Logger.i(LOG_TAG, "reset conversation done")
    }

    suspend fun stopCurrentRound() {
        Logger.i(LOG_TAG, "stop round requested")
        sessionAssembler.stopCurrentRound()
        Logger.i(LOG_TAG, "stop round done")
    }

    suspend fun ingestUserImage(uriString: String): IngestedImage? =
        imageManager.ingestUserImage(uriString)

    suspend fun compact(force: Boolean = false): Boolean {
        if (!executionMutex.tryLock()) return false
        publishCompaction(ContextCompaction.Phase.Running, manual = true)
        try {
            if (runtimeState?.configId != boundConfigId || okia == null) refresh()
            val session = okia ?: return false
            val history = session.conversation.value.history.map { it.message }
            val used = ContextPruner.prune(history).messages.sumOf(RequestContextBudget::message)
            if (!force && !Compactor.shouldCompact(used, contextWindowFlow.value, compactor.thresholdRatio)) return false
            val candidate = compactor.prepare(
                history, contextWindowFlow.value, contextReplyTokens ?: 4096,
                generate = ::generateCompactionSummary,
            ) ?: return false
            currentCoroutineContext().ensureActive()
            session.append(candidate)
            val payload = ContextPruner.payloadOf(candidate)
            publishCompaction(ContextCompaction.Phase.Compacted, payload?.tokensBefore, payload?.tokensAfter, manual = true)
            contextRevision.value += 1
            return true
        } catch (cancelled: CancellationException) {
            publishCompaction(ContextCompaction.Phase.Cancelled, manual = true)
            throw cancelled
        } catch (failure: Exception) {
            publishCompaction(ContextCompaction.Phase.Failed, manual = true)
            throw failure
        } finally {
            if (contextCompaction.value.phase == ContextCompaction.Phase.Running) {
                publishCompaction(ContextCompaction.Phase.Unchanged, manual = true)
            }
            executionMutex.unlock()
        }
    }

    private suspend fun generateSummary(systemPrompt: String, userPrompt: String): String {
        val state = runtimeState ?: run {
            refresh()
            runtimeState
        } ?: return ""
        val protocol = state.sessionProtocol ?: LlmProtocol.Default
        return sessionAssembler.generateSummary(
            protocol = protocol,
            config = state.snapshot.config,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
        )
    }

    private fun contextTokensOf(conversation: Conversation?): Long? {
        conversation?.live?.usage?.contextTokens?.let { return it }
        return conversation?.history?.asReversed()?.firstNotNullOfOrNull { entry ->
            (entry.message as? Message.Assistant)?.message?.usage?.contextTokens
        }
    }

    internal fun validateLlmConfig(config: RuntimeLlmConfig) =
        sessionAssembler.validateLlmConfig(config)

    private data class RuntimeState(
        val configId: String,
        val snapshot: LlmRuntimeSnapshot,
        val okia: Okia,
        val sessionProtocol: LlmProtocol?,
        val contextWindowSource: String,
    )
}

/**
 * 记忆层条目。memories 有内容时优先，否则退回 memoryPrompt。
 * 逻辑原先挂在旧 PromptComposer 的 companion 上；换成分层引擎后引擎只负责渲染，
 * 取数留在宿主侧——引擎不读偏好、不读文件。
 */
private fun buildMemoryItems(config: RuntimeLlmConfig): List<String> {
    val memories = config.memories.map(String::trim).filter(String::isNotBlank)
    if (memories.isNotEmpty()) return memories
    return listOfNotNull(config.memoryPrompt.trim().takeIf { it.isNotBlank() })
}
