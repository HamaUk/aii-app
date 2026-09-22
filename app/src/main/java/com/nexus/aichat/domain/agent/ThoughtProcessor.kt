package com.nexus.aichat.domain.agent

import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.AgentPhase
import com.nexus.aichat.core.model.AgentStopReason
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.domain.model.AgentStep
import com.nexus.aichat.domain.model.AgentTrace
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Folds the agent's event stream into the structure the chat surface renders.
 *
 * This is the piece that makes a streaming ReAct run look calm instead of frantic: events arrive as a
 * *stream of fragments* (`ThoughtDelta` per token, `ToolCallStarted/Finished` pairs, narration text
 * that may be replaced), and the processor accumulates them into stable [AgentStep] nodes that the
 * LazyColumn can key on without flicker.
 *
 * Rules it enforces:
 *  - one [AgentStep.Thinking] node per (runId, stepIndex) - deltas mutate it, they never create nodes;
 *  - `AnswerRevised` *replaces* the answer instead of appending, because the engine lifts tool calls
 *    that a model printed as text back out of the bubble;
 *  - tool milestones are keyed by `callId`, so a retried call updates its card rather than stacking;
 *  - the trace is immutable on the outside: every event produces a new [AgentTrace] value.
 */
@Singleton
class ThoughtProcessor @Inject constructor() {

    /** Fresh accumulator for a run. One per run; owned by the ViewModel, never shared across chats. */
    fun newAccumulator(runId: String = UUID.randomUUID().toString()): Accumulator = Accumulator(runId)

    /** Mutable accumulator. One per active run; owned by the ViewModel, never shared across chats. */
    class Accumulator(private val runId: String = UUID.randomUUID().toString()) {

        private val steps = mutableListOf<AgentStep>()
        private var answer = StringBuilder()
        private var phase = AgentPhase.IDLE
        private var awaitingApproval: AgentStep.ToolInvocation? = null
        private var awaitingClarification: AgentStep.Clarification? = null
        private var running = false
        private var usage: TokenUsage = TokenUsage()
        private var stopReason: AgentStopReason? = null

        val currentRunId: String get() = runId
        val accumulatedUsage: TokenUsage get() = usage
        val terminalReason: AgentStopReason? get() = stopReason

        fun onEvent(event: AgentEvent): AgentTrace = when (event) {
            is AgentEvent.RunStarted -> {
                running = true
                phase = AgentPhase.ANALYZING
                snapshot()
            }

            is AgentEvent.PhaseChanged -> {
                phase = event.phase
                snapshot()
            }

            is AgentEvent.ThoughtDelta -> {
                val index = steps.indexOfLast { it is AgentStep.Thinking && it.stepIndex == event.stepIndex }
                if (index >= 0) {
                    val existing = steps[index] as AgentStep.Thinking
                    steps[index] = existing.copy(text = existing.text + event.text, isStreaming = true)
                } else {
                    steps += AgentStep.Thinking(
                        id = "$runId:think:${event.stepIndex}",
                        stepIndex = event.stepIndex,
                        startedAtEpochMs = System.currentTimeMillis(),
                        text = event.text,
                        isStreaming = true,
                    )
                }
                snapshot()
            }

            is AgentEvent.ThoughtCompleted -> {
                val index = steps.indexOfLast { it is AgentStep.Thinking && it.stepIndex == event.stepIndex }
                if (index >= 0) {
                    val existing = steps[index] as AgentStep.Thinking
                    steps[index] = existing.copy(
                        text = event.text.ifBlank { existing.text },
                        isStreaming = false,
                        durationMs = event.durationMs,
                        tokens = event.tokens,
                    )
                } else {
                    steps += AgentStep.Thinking(
                        id = "$runId:think:${event.stepIndex}",
                        stepIndex = event.stepIndex,
                        startedAtEpochMs = System.currentTimeMillis(),
                        text = event.text,
                        isStreaming = false,
                        durationMs = event.durationMs,
                        tokens = event.tokens,
                    )
                }
                snapshot()
            }

            is AgentEvent.AnswerDelta -> {
                answer.append(event.text)
                snapshot()
            }

            // The engine rewrote text it already streamed (a printed tool call was lifted out).
            is AgentEvent.AnswerRevised -> {
                answer = StringBuilder(event.fullText)
                snapshot()
            }

            is AgentEvent.ToolCallProposed -> {
                upsertTool(
                    AgentStep.ToolInvocation(
                        id = event.request.callId,
                        stepIndex = event.stepIndex,
                        startedAtEpochMs = System.currentTimeMillis(),
                        callId = event.request.callId,
                        toolName = event.request.toolName,
                        argumentsJson = event.request.argumentsJson,
                        status = com.nexus.aichat.core.model.ToolCallStatus.AWAITING_APPROVAL,
                        requiresApproval = event.requiresApproval,
                        approvalReason = event.approvalReason,
                    ),
                )
                awaitingApproval = steps.filterIsInstance<AgentStep.ToolInvocation>()
                    .lastOrNull { it.callId == event.request.callId }
                snapshot()
            }

            is AgentEvent.ToolCallStarted -> {
                upsertToolByCallId(event.request.callId) {
                    it.copy(status = com.nexus.aichat.core.model.ToolCallStatus.RUNNING)
                }
                awaitingApproval = null
                phase = AgentPhase.CALLING_TOOL
                snapshot()
            }

            is AgentEvent.ToolCallFinished -> {
                upsertToolByCallId(event.result.callId) {
                    it.copy(
                        status = if (event.result.isError) {
                            com.nexus.aichat.core.model.ToolCallStatus.FAILED
                        } else {
                            com.nexus.aichat.core.model.ToolCallStatus.SUCCEEDED
                        },
                        resultPreview = event.result.preview,
                        resultContent = event.result.content,
                        isError = event.result.isError,
                        durationMs = event.result.durationMs,
                    )
                }
                phase = AgentPhase.OBSERVING
                snapshot()
            }

            is AgentEvent.ClarificationRequested -> {
                awaitingClarification = AgentStep.Clarification(
                    id = event.requestId,
                    stepIndex = steps.maxOfOrNull { it.stepIndex } ?: 0,
                    startedAtEpochMs = System.currentTimeMillis(),
                    question = event.question,
                )
                awaitingClarification?.let { steps += it }
                phase = AgentPhase.WAITING_FOR_USER
                snapshot()
            }

            is AgentEvent.UsageUpdated -> {
                usage = event.usage
                snapshot()
            }

            is AgentEvent.BudgetWarning -> {
                phase = AgentPhase.REFLECTING
                snapshot()
            }

            is AgentEvent.AssistantMessageFinalized -> {
                // The finalised message is the source of truth once the run ends; the streaming
                // accumulator has done its job and is replaced by persisted parts.
                answer = StringBuilder(event.message.plainText)
                running = false
                phase = AgentPhase.DONE
                snapshot()
            }

            is AgentEvent.RunFinished -> {
                stopReason = event.reason
                usage = event.usage
                running = false
                phase = AgentPhase.DONE
                snapshot()
            }

            is AgentEvent.RunFailed -> {
                running = false
                phase = AgentPhase.DONE
                snapshot()
            }
        }

        private fun upsertTool(step: AgentStep.ToolInvocation) {
            val index = steps.indexOfFirst { it is AgentStep.ToolInvocation && it.callId == step.callId }
            if (index >= 0) steps[index] = step else steps += step
        }

        private fun upsertToolByCallId(callId: String, transform: (AgentStep.ToolInvocation) -> AgentStep.ToolInvocation) {
            val index = steps.indexOfFirst { it is AgentStep.ToolInvocation && it.callId == callId }
            if (index >= 0) steps[index] = transform(steps[index] as AgentStep.ToolInvocation)
        }

        fun snapshot(): AgentTrace = AgentTrace(
            steps = steps.toList(),
            finalAnswer = answer.toString(),
            isRunning = running,
            awaitingApproval = awaitingApproval,
            awaitingClarification = awaitingClarification,
        )

        /** Phase + usage are surfaced separately so the status chip does not recompose the feed. */
        fun status(): AgentStatus = AgentStatus(phase = phase, usage = usage, isRunning = running)
    }

    data class AgentStatus(
        val phase: AgentPhase = AgentPhase.IDLE,
        val usage: TokenUsage = TokenUsage(),
        val isRunning: Boolean = false,
    )
}
