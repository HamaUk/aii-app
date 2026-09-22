package com.nexus.aichat.core.ai.agent

import com.nexus.aichat.core.ai.engine.ChatCall
import com.nexus.aichat.core.ai.engine.ChatCompletionClient
import com.nexus.aichat.core.ai.engine.StreamEvent
import com.nexus.aichat.core.ai.protocol.ProviderChatRequest
import com.nexus.aichat.core.ai.protocol.ProviderContentPart
import com.nexus.aichat.core.ai.protocol.ProviderMessage
import com.nexus.aichat.core.ai.protocol.ProviderRole
import com.nexus.aichat.core.ai.protocol.ToolChoice
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import com.nexus.aichat.core.common.di.NexusDispatchers
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.error.AppException
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.AgentPhase
import com.nexus.aichat.core.model.AgentRunRequest
import com.nexus.aichat.core.model.AgentStopReason
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.MessageStatus
import com.nexus.aichat.core.model.ReasoningVisibility
import com.nexus.aichat.core.model.SamplingOptions
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.core.model.ToolApprovalPolicy
import com.nexus.aichat.core.model.ToolCallRequest
import com.nexus.aichat.core.model.ToolCallStatus
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the user (or the policy engine) decided about a proposed tool call. */
enum class ToolApprovalDecision { APPROVE, APPROVE_FOR_RUN, REJECT }

/**
 * The agentic execution engine - Nexus's local harness.
 *
 * This is a ReAct loop with a budget, a guard rail and an escape hatch:
 *
 *   analyse -> (think) -> call tools -> observe -> verify -> ... -> answer
 *
 * Design decisions worth defending in review:
 *  - **One run per instance at a time is not enforced**, but every run is keyed by `runId`, so the
 *    UI can hold several suspended runs (e.g. a paused clarification) without interference.
 *  - **The harness, not the model, owns the loop.** A model cannot burn tokens indefinitely: steps,
 *    tool calls and wall-clock are capped, and hitting the cap still produces a usable message.
 *  - **Identical calls are intercepted** before execution. Small models loop on the same call; the
 *    interception turns an infinite loop into one wasted step.
 *  - **Failures inside a step are observations, not exceptions.** A tool error is fed back so the
 *    model can self-correct; only transport/auth failures abort the run.
 *  - **The final message is assembled here** (reasoning + tool milestones + prose), which is what
 *    lets the UI render the collapsible thought tree from a single persisted entity.
 */
class AgentOrchestrator(
    private val client: ChatCompletionClient,
    private val secrets: SecretProvider,
    private val tools: ToolRegistry,
    private val promptBuilder: AgentPromptBuilder,
    private val clock: TimeProvider,
    private val dispatchers: NexusDispatchers,
    private val logger: NexusLogger,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    private val activeRuns = ConcurrentHashMap<String, RunHandle>()

    val runningRunIds: Set<String> get() = activeRuns.keys.toSet()

    fun isRunning(runId: String): Boolean = activeRuns.containsKey(runId)

    // -----------------------------------------------------------------------------------------
    // Public control surface
    // -----------------------------------------------------------------------------------------

    /**
     * Executes one agent run. The returned flow is cold: work starts on collection and stops when
     * the collector is cancelled (the Stop button), which also aborts the in-flight HTTP stream.
     */
    fun run(request: AgentRunRequest): Flow<AgentEvent> = flow {
        val runId = request.runId
        val handle = RunHandle(runId)
        handle.job = currentCoroutineContext()[Job]
        activeRuns[runId] = handle
        val emitEvent: suspend (AgentEvent) -> Unit = { event -> emit(event) }

        val startedAtEpochMs = clock.nowEpochMillis()
        val startedAtElapsed = clock.elapsedMillis()
        val options = request.options
        val parts = mutableListOf<MessagePart>()
        val toolPartIndex = mutableMapOf<String, Int>()
        val executedSignatures = mutableSetOf<String>()
        val executedOutputs = mutableMapOf<String, String>()

        var stepIndex = 0
        var toolCallCount = 0
        var answered = false
        var cumulativeUsage = TokenUsage()
        var stopReason = AgentStopReason.COMPLETED
        var answer = StringBuilder()
        var finishReason: String? = null

        emitEvent(
            AgentEvent.RunStarted(
                runId = runId,
                conversationId = request.conversationId,
                providerId = request.provider.id,
                modelId = request.model.id,
                mode = options.mode,
                startedAtEpochMs = startedAtEpochMs,
            ),
        )

        try {
            val apiKey = secrets.apiKeyFor(request.provider)
            if (request.provider.auth.requiresSecret && apiKey.isNullOrBlank()) {
                throw AppException(
                    AppError.Auth(
                        "No API key stored for ${request.provider.displayName}. Add one in Settings > Providers.",
                        providerId = request.provider.id,
                    ),
                )
            }

            val activeTools = if (options.mode == AgentMode.SINGLE_SHOT) {
                emptyList()
            } else {
                tools.enabledFor(options, request.model)
            }
            val toolSpecs: List<ToolSpec> = buildList {
                addAll(activeTools.map { it.spec })
                if (options.allowClarificationPauses && options.mode != AgentMode.SINGLE_SHOT) {
                    add(AgentPromptBuilder.ASK_USER_SPEC)
                }
            }

            val systemPrompt = promptBuilder.buildSystemPrompt(
                userPersona = request.personaSystemPrompt,
                tools = toolSpecs,
                options = options,
                model = request.model,
                currentDateTime = formatNow(),
                userLocale = java.util.Locale.getDefault().toLanguageTag(),
            )

            val transcript = promptBuilder.buildTranscript(
                history = request.history,
                userMessage = request.userMessage,
            ).toMutableList()

            logger.i(TAG, "run $runId start: ${request.model.id} mode=${options.mode} tools=${toolSpecs.size}")

            while (stepIndex < options.maxSteps) {
                if (clock.elapsedMillis() - startedAtElapsed > options.maxWallClockMs) {
                    stopReason = AgentStopReason.WALL_CLOCK_EXCEEDED
                    break
                }
                if (stepIndex == options.maxSteps - 1 && options.maxSteps > 1) {
                    emitEvent(AgentEvent.BudgetWarning(runId, stepIndex, options.maxSteps))
                }

                emitEvent(
                    AgentEvent.PhaseChanged(
                        runId,
                        if (stepIndex == 0) AgentPhase.ANALYZING else AgentPhase.REFLECTING,
                    ),
                )

                val step = streamStep(
                    request = request,
                    apiKey = apiKey,
                    transcript = transcript,
                    systemPrompt = systemPrompt,
                    toolSpecs = toolSpecs,
                    stepIndex = stepIndex,
                    emitEvent = emitEvent,
                )
                stepIndex++   // one completed model call: this is what "steps" means in the UI and in the budget
                step.usage?.let { cumulativeUsage += it }
                finishReason = step.finishReason ?: finishReason
                answer = StringBuilder(step.text)

                // The reasoning trace is a first-class part of the message: it is what the collapsible
                // thought tree renders after the conversation is reloaded from the database.
                if (step.reasoning.isNotBlank() && options.showReasoning != ReasoningVisibility.HIDDEN) {
                    parts += MessagePart.Reasoning(
                        text = step.reasoning,
                        isStreaming = false,
                        durationMs = step.reasoningDurationMs,
                        tokens = step.usage?.reasoningTokens?.takeIf { it > 0 },
                        provider = request.provider.displayName,
                    )
                }

                // --- Terminal step: the model answered without asking for tools -----------------
                if (step.toolCalls.isEmpty()) {
                    if (step.text.isNotBlank()) parts += MessagePart.Text(step.text)
                    answered = true
                    stopReason = AgentStopReason.COMPLETED
                    break
                }

                // Narration that preceded the tool calls belongs to the thought tree, not the answer.
                if (step.text.isNotBlank()) parts += MessagePart.Text(step.text)

                transcript += ProviderMessage(
                    role = ProviderRole.ASSISTANT,
                    content = if (step.text.isBlank()) emptyList() else listOf(ProviderContentPart.Text(step.text)),
                    toolCalls = step.toolCalls,
                )

                var budgetExhausted = false
                var attemptedThisStep = 0
                for (call in step.toolCalls) {
                    if (toolCallCount >= options.maxTotalToolCalls || attemptedThisStep >= options.maxToolCallsPerStep) {
                        budgetExhausted = true
                        stopReason = AgentStopReason.MAX_TOOL_CALLS_REACHED
                        // Every tool call in an assistant turn must be answered exactly once, or strict
                        // providers (OpenAI, Groq) reject the next request outright.
                        transcript += ProviderMessage(
                            role = ProviderRole.TOOL,
                            content = listOf(
                                ProviderContentPart.ToolResultBlock(
                                    callId = call.callId,
                                    content = "<tool_error>This run's tool budget was reached, so this call was " +
                                        "not executed. Answer with the information you already have.</tool_error>",
                                    isError = true,
                                ),
                            ),
                            name = call.toolName,
                        )
                        continue
                    }
                    attemptedThisStep++
                    toolCallCount++

                    val result = executeToolCall(
                        request = request,
                        call = call,
                        handle = handle,
                        options = options,
                        activeTools = activeTools,
                        executedSignatures = executedSignatures,
                        executedOutputs = executedOutputs,
                        parts = parts,
                        toolPartIndex = toolPartIndex,
                        stepIndex = stepIndex,
                        apiKey = apiKey,
                        emitEvent = emitEvent,
                    )

                    transcript += ProviderMessage(
                        role = ProviderRole.TOOL,
                        content = listOf(
                            ProviderContentPart.ToolResultBlock(
                                callId = call.callId,
                                content = result.content,
                                isError = result.isError,
                            ),
                        ),
                        name = call.toolName,
                    )
                }

                if (budgetExhausted || stopReason == AgentStopReason.MAX_TOOL_CALLS_REACHED) break
            }

            // A run that never produced an answer and used up its step budget stopped because of the
            // budget, not because it finished - the UI has to say so.
            if (!answered && stopReason == AgentStopReason.COMPLETED && stepIndex >= options.maxSteps) {
                stopReason = AgentStopReason.MAX_STEPS_REACHED
            }

            emitEvent(AgentEvent.UsageUpdated(runId, cumulativeUsage))

            val finalMessage = Message(
                id = newId(),
                conversationId = request.conversationId,
                role = MessageRole.ASSISTANT,
                parts = parts.toList(),
                parentId = request.userMessage.id,
                status = MessageStatus.COMPLETE,
                createdAtEpochMs = clock.nowEpochMillis(),
                updatedAtEpochMs = clock.nowEpochMillis(),
                providerId = request.provider.id,
                modelId = request.model.id,
                usage = cumulativeUsage,
                agentRunId = runId,
                finishReason = finishReason,
            )


            emitEvent(AgentEvent.AssistantMessageFinalized(runId, finalMessage))
            emitEvent(
                AgentEvent.RunFinished(
                    runId = runId,
                    reason = stopReason,
                    steps = stepIndex,
                    usage = cumulativeUsage,
                    finalMessageId = finalMessage.id,
                    durationMs = clock.elapsedMillis() - startedAtElapsed,
                ),
            )
        } catch (cancellation: CancellationException) {
            // Stop button. The ViewModel persists whatever it mirrored; nothing is emitted here
            // because a cancelled collector cannot receive events.
            logger.i(TAG, "run $runId cancelled after $stepIndex step(s)")
            throw cancellation
        } catch (t: Throwable) {
            val error = if (t is AppException) t.error else AppError.from(t)
            logger.e(TAG, "run $runId failed: ${error.displayMessage}", t)
            emitEvent(AgentEvent.RunFailed(runId, error))
        } finally {
            activeRuns.remove(runId)
            handle.cancelPending()
        }
    }

    /** Answers a pending [AgentEvent.ClarificationRequested]. Returns false if the run is gone. */
    suspend fun submitClarification(runId: String, requestId: String, answer: String): Boolean {
        val deferred = activeRuns[runId]?.clarifications?.remove(requestId) ?: return false
        return deferred.complete(answer)
    }

    /** Resolves a pending [AgentEvent.ToolCallProposed]. Returns false if the proposal expired. */
    suspend fun resolveApproval(runId: String, callId: String, decision: ToolApprovalDecision): Boolean {
        val handle = activeRuns[runId] ?: return false
        if (decision == ToolApprovalDecision.APPROVE_FOR_RUN) {
            handle.pendingToolName[callId]?.let { handle.approvedForRun += it }
        }
        val deferred = handle.approvals.remove(callId) ?: return false
        return deferred.complete(decision)
    }

    /** Aborts a run: closes the socket, cancels tool work, releases every suspended await. */
    fun cancel(runId: String) {
        activeRuns[runId]?.let { handle ->
            handle.cancelPending()
            handle.job?.cancel(CancellationException("Cancelled by user"))
        }
    }

    fun cancelAll() {
        activeRuns.keys.forEach(::cancel)
    }

    // -----------------------------------------------------------------------------------------
    // One model round-trip
    // -----------------------------------------------------------------------------------------

    private data class StepOutcome(
        val text: String,
        val reasoning: String,
        val reasoningDurationMs: Long,
        val toolCalls: List<ToolCallRequest>,
        val usage: TokenUsage?,
        val finishReason: String?,
    )

    private suspend fun streamStep(
        request: AgentRunRequest,
        apiKey: String?,
        transcript: List<ProviderMessage>,
        systemPrompt: String,
        toolSpecs: List<ToolSpec>,
        stepIndex: Int,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): StepOutcome {
        val sampling = resolveSampling(request)
        val chatRequest = ProviderChatRequest(
            model = request.model.id,
            system = systemPrompt,
            messages = transcript,
            tools = toolSpecs,
            toolChoice = if (toolSpecs.isEmpty()) ToolChoice.None else ToolChoice.Auto,
            sampling = sampling,
            streaming = request.streaming,
        )

        var text = StringBuilder()
        var reasoning = StringBuilder()
        var toolCalls: List<ToolCallRequest> = emptyList()
        var usage: TokenUsage? = null
        var finishReason: String? = null
        val reasoningStartedAt = clock.elapsedMillis()

        client.stream(
            ChatCall(config = request.provider, apiKey = apiKey, request = chatRequest, maxRetries = 2),
        ).collect { event ->
            when (event) {
                is StreamEvent.Started -> emitEvent(AgentEvent.PhaseChanged(request.runId, AgentPhase.THINKING))

                is StreamEvent.ReasoningDelta -> {
                    reasoning.append(event.text)
                    emitEvent(AgentEvent.ThoughtDelta(request.runId, stepIndex, event.text))
                }

                is StreamEvent.TextDelta -> {
                    text.append(event.text)
                    emitEvent(AgentEvent.AnswerDelta(request.runId, event.text))
                }

                is StreamEvent.TextRevised -> {
                    text = StringBuilder(event.fullText)
                    emitEvent(AgentEvent.AnswerRevised(request.runId, event.fullText, event.reason))
                }

                is StreamEvent.ToolCallDelta -> Unit    // fragments are assembled by the engine

                is StreamEvent.Usage -> usage = event.usage

                is StreamEvent.Finished -> {
                    text = StringBuilder(event.text)
                    reasoning = StringBuilder(event.reasoning)
                    toolCalls = event.toolCalls
                    usage = event.usage ?: usage
                    finishReason = event.finishReason
                }
            }
        }

        if (reasoning.isNotEmpty() && request.options.showReasoning != ReasoningVisibility.HIDDEN) {
            emitEvent(
                AgentEvent.ThoughtCompleted(
                    runId = request.runId,
                    stepIndex = stepIndex,
                    text = reasoning.toString(),
                    durationMs = clock.elapsedMillis() - reasoningStartedAt,
                    tokens = usage?.reasoningTokens,
                ),
            )
        }

        return StepOutcome(
            text = text.toString(),
            reasoning = reasoning.toString(),
            reasoningDurationMs = clock.elapsedMillis() - reasoningStartedAt,
            toolCalls = toolCalls,
            usage = usage,
            finishReason = finishReason,
        )
    }

    /**
     * Reasoning control: only ask for thinking when the model advertises it and the user has not
     * already expressed a preference, then normalise to what the chosen protocol expects.
     */
    private fun resolveSampling(request: AgentRunRequest): SamplingOptions {
        val sampling = request.sampling
        val wantsReasoning = request.options.autoEnableReasoning &&
            request.model.supportsReasoning &&
            request.options.showReasoning != ReasoningVisibility.HIDDEN

        return when {
            sampling.reasoningBudgetTokens != null || sampling.reasoningEffort != null -> sampling
            wantsReasoning -> sampling.copy(
                reasoningBudgetTokens = request.options.defaultReasoningBudgetTokens,
                reasoningEffort = com.nexus.aichat.core.model.ReasoningEffort.MEDIUM,
            )
            else -> sampling
        }
    }

    // -----------------------------------------------------------------------------------------
    // Tool execution (approval, dedupe, clarification, error containment)
    // -----------------------------------------------------------------------------------------

    private suspend fun executeToolCall(
        request: AgentRunRequest,
        call: ToolCallRequest,
        handle: RunHandle,
        options: AgentOptions,
        activeTools: List<AgentTool>,
        executedSignatures: MutableSet<String>,
        executedOutputs: MutableMap<String, String>,
        parts: MutableList<MessagePart>,
        toolPartIndex: MutableMap<String, Int>,
        stepIndex: Int,
        apiKey: String?,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): ToolResult {
        val runId = request.runId
        val arguments = parseArguments(call.argumentsJson)

        /**
         * Exit path for calls that never reach the tool.
         *
         * The thought tree renders one milestone per *attempt*, so an unknown tool, a duplicate call or a
         * rejection still has to produce a part and a `ToolCallFinished` event - otherwise the UI shows a
         * run in which the model did nothing, and the persisted transcript loses the reason.
         */
        suspend fun finishSkipped(result: ToolResult, status: ToolCallStatus): ToolResult {
            val existing = toolPartIndex[call.callId]
            if (existing != null) {
                parts[existing] = (parts[existing] as MessagePart.ToolCall).copy(
                    status = status,
                    isError = true,
                    resultPreview = result.preview.take(400),
                    resultContent = result.content,
                    finishedAtEpochMs = clock.nowEpochMillis(),
                )
            } else {
                parts += MessagePart.ToolCall(
                    callId = call.callId,
                    toolName = call.toolName,
                    argumentsJson = call.argumentsJson,
                    status = status,
                    isError = true,
                    resultPreview = result.preview.take(400),
                    resultContent = result.content,
                    startedAtEpochMs = clock.nowEpochMillis(),
                    finishedAtEpochMs = clock.nowEpochMillis(),
                )
                toolPartIndex[call.callId] = parts.lastIndex
            }
            emitEvent(AgentEvent.ToolCallFinished(runId, result))
            return result
        }

        // --- Clarification pseudo-tool: parks the loop until the user answers --------------------
        if (call.toolName == AgentPromptBuilder.ASK_USER_TOOL) {
            return handleClarification(request, call, arguments, handle, parts, toolPartIndex, emitEvent)
        }

        val tool = activeTools.firstOrNull { it.spec.name == call.toolName }
            ?: tools.byName(call.toolName)
            ?: return finishSkipped(
                result = ToolResult.error(
                    call.callId,
                    call.toolName,
                    "Unknown tool '${call.toolName}'. Available: ${activeTools.joinToString { it.spec.name }}",
                ),
                status = ToolCallStatus.FAILED,
            )

        // --- Duplicate guard: identical call == identical result ---------------------------------
        val signature = "${call.toolName}:${call.argumentsJson.trim()}"
        if (signature in executedSignatures) {
            logger.i(TAG, "duplicate tool call intercepted: $signature")
            return finishSkipped(
                result = ToolResult(
                callId = call.callId,
                toolName = call.toolName,
                content = buildString {
                    appendLine("<tool_error>")
                    appendLine("This exact call (${call.toolName}) was already executed in this run and returned:")
                    appendLine("---")
                    appendLine(executedOutputs[signature]?.take(1_500) ?: "(no cached output)")
                    appendLine("---")
                    appendLine("Use that result, change your arguments, or answer with what you have.")
                    appendLine("</tool_error>")
                },
                ).copy(isError = true),
                status = ToolCallStatus.FAILED,
            )
        }

        val partIndex = parts.size
        parts += MessagePart.ToolCall(
            callId = call.callId,
            toolName = call.toolName,
            argumentsJson = call.argumentsJson,
            status = ToolCallStatus.REQUESTED,
            startedAtEpochMs = clock.nowEpochMillis(),
        )
        toolPartIndex[call.callId] = partIndex

        // --- Approval gate ----------------------------------------------------------------------
        val requiresApproval = requiresApproval(tool, options, handle)
        if (requiresApproval) {
            val deferred = CompletableDeferred<ToolApprovalDecision>()
            handle.approvals[call.callId] = deferred
            handle.pendingToolName[call.callId] = call.toolName
            updatePart(parts, toolPartIndex, call.callId) { it.copy(status = ToolCallStatus.AWAITING_APPROVAL) }

            emitEvent(
                AgentEvent.ToolCallProposed(
                    runId = runId,
                    stepIndex = stepIndex,
                    request = call,
                    requiresApproval = true,
                    approvalReason = approvalReason(tool),
                ),
            )

            val decision = try {
                deferred.await()
            } catch (cancellation: CancellationException) {
                handle.approvals.remove(call.callId)
                throw cancellation
            }
            handle.approvals.remove(call.callId)
            handle.pendingToolName.remove(call.callId)

            if (decision == ToolApprovalDecision.REJECT) {
                return finishSkipped(
                    result = ToolResult.error(
                        call.callId,
                        call.toolName,
                        "The user rejected this tool call. Do not retry it - answer with what you have.",
                    ),
                    status = ToolCallStatus.REJECTED,
                )
            }
            if (decision == ToolApprovalDecision.APPROVE_FOR_RUN) handle.approvedForRun += call.toolName
        }

        // --- Execute ---------------------------------------------------------------------------
        updatePart(parts, toolPartIndex, call.callId) { it.copy(status = ToolCallStatus.RUNNING) }
        emitEvent(AgentEvent.ToolCallStarted(runId, call))
        emitEvent(AgentEvent.PhaseChanged(runId, AgentPhase.CALLING_TOOL))

        val context = ToolContext(
            runId = runId,
            conversationId = request.conversationId,
            timeProvider = clock,
            logger = logger,
            dispatchers = dispatchers,
            reportProgress = { line -> emitEvent(AgentEvent.ThoughtDelta(runId, stepIndex, "\n· $line")) },
        )

        val raw = tool.execute(arguments, context)
        val (clamped, truncated) = ToolArgs.clamp(raw.content, tool.spec.maxOutputChars)
        val result = raw.copy(content = clamped, truncated = truncated)

        updatePart(parts, toolPartIndex, call.callId) {
            it.copy(
                status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.SUCCEEDED,
                resultPreview = result.preview.take(400),
                resultContent = result.content,
                isError = result.isError,
                finishedAtEpochMs = clock.nowEpochMillis(),
            )
        }

        if (!result.isError) {
            executedSignatures += signature
            executedOutputs[signature] = result.content
        }

        emitEvent(AgentEvent.ToolCallFinished(runId, result))
        emitEvent(AgentEvent.PhaseChanged(runId, AgentPhase.OBSERVING))
        return result
    }

    private suspend fun handleClarification(
        request: AgentRunRequest,
        call: ToolCallRequest,
        arguments: JsonObject,
        handle: RunHandle,
        parts: MutableList<MessagePart>,
        toolPartIndex: MutableMap<String, Int>,
        emitEvent: suspend (AgentEvent) -> Unit,
    ): ToolResult {
        val runId = request.runId
        val question = (arguments["question"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: "Could you clarify what you need?"
        val requestId = newId()

        val partIndex = parts.size
        parts += MessagePart.ClarificationRequest(question = question)
        toolPartIndex[call.callId] = partIndex
        parts += MessagePart.ToolCall(
            callId = call.callId,
            toolName = AgentPromptBuilder.ASK_USER_TOOL,
            argumentsJson = call.argumentsJson,
            status = ToolCallStatus.AWAITING_APPROVAL,
            startedAtEpochMs = clock.nowEpochMillis(),
        )
        toolPartIndex[call.callId] = parts.size - 1

        val deferred = CompletableDeferred<String>()
        handle.clarifications[requestId] = deferred
        emitEvent(AgentEvent.PhaseChanged(runId, AgentPhase.WAITING_FOR_USER))
        emitEvent(AgentEvent.ClarificationRequested(runId, question, requestId))

        val answer = try {
            deferred.await()
        } finally {
            handle.clarifications.remove(requestId)
        }

        parts[partIndex] = MessagePart.ClarificationRequest(question = question, answer = answer)
        updatePart(parts, toolPartIndex, call.callId) {
            it.copy(
                status = ToolCallStatus.SUCCEEDED,
                resultPreview = "User: $answer",
                resultContent = "User answered: $answer",
                finishedAtEpochMs = clock.nowEpochMillis(),
            )
        }
        emitEvent(AgentEvent.PhaseChanged(runId, AgentPhase.THINKING))
        return ToolResult(
            callId = call.callId,
            toolName = AgentPromptBuilder.ASK_USER_TOOL,
            content = "The user answered: $answer",
            preview = answer.take(200),
        )
    }

    private fun requiresApproval(tool: AgentTool, options: AgentOptions, handle: RunHandle): Boolean =
        when (options.approvalPolicy) {
            ToolApprovalPolicy.DENY_ALL -> true
            ToolApprovalPolicy.ASK_EVERY_TIME -> true
            ToolApprovalPolicy.ASK_FIRST_TIME -> tool.spec.name !in handle.approvedForRun
            ToolApprovalPolicy.AUTO_APPROVE_SAFE ->
                tool.spec.defaultApproval == ApprovalRequirement.ASK_EVERY_TIME ||
                    (tool.spec.isDestructive && tool.spec.defaultApproval != ApprovalRequirement.AUTO)
        }

    private fun approvalReason(tool: AgentTool): String = when {
        tool.spec.isDestructive -> "`${tool.spec.name}` can change state on your device or an external service."
        tool.spec.requiresNetwork -> "`${tool.spec.name}` will make a network request."
        else -> "Your approval policy asks before running `${tool.spec.name}`."
    }

    private fun parseArguments(json: String): JsonObject =
        runCatching { NexusJson.instance.parseToJsonElement(json) as? JsonObject }
            .getOrNull()
            ?: runCatching {
                // Models occasionally emit single quotes or trailing commas; repair once, then give up.
                NexusJson.instance.parseToJsonElement(
                    json.replace('\'', '"').replace(Regex(",\\s*([}\\]])"), "$1"),
                ) as? JsonObject
            }.getOrNull()
            ?: buildJsonObject { put("raw", json) }

    private inline fun updatePart(
        parts: MutableList<MessagePart>,
        index: MutableMap<String, Int>,
        callId: String,
        transform: (MessagePart.ToolCall) -> MessagePart.ToolCall,
    ) {
        val position = index[callId] ?: return
        val current = parts.getOrNull(position) as? MessagePart.ToolCall ?: return
        parts[position] = transform(current)
    }

    private fun formatNow(): String = java.time.Instant.ofEpochMilli(clock.nowEpochMillis())
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm z"))

    // -----------------------------------------------------------------------------------------
    // Per-run state
    // -----------------------------------------------------------------------------------------

    private class RunHandle(val runId: String) {
        @Volatile var job: Job? = null
        val clarifications = ConcurrentHashMap<String, CompletableDeferred<String>>()
        val approvals = ConcurrentHashMap<String, CompletableDeferred<ToolApprovalDecision>>()

        /** Tools approved "for the rest of this run" (policy ASK_FIRST_TIME / APPROVE_FOR_RUN). */
        val approvedForRun: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** callId -> tool name, so APPROVE_FOR_RUN can whitelist the right tool. */
        val pendingToolName = ConcurrentHashMap<String, String>()

        fun cancelPending() {
            clarifications.values.forEach { it.cancel() }
            approvals.values.forEach { it.cancel() }
            clarifications.clear()
            approvals.clear()
            pendingToolName.clear()
        }
    }

    companion object {
        private const val TAG = "AgentOrchestrator"
    }
}
