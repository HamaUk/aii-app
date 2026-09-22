package com.nexus.aichat.core.model

import com.nexus.aichat.core.common.error.AppError
import kotlinx.serialization.Serializable

@Serializable
enum class AgentMode(val label: String, val blurb: String) {
    SINGLE_SHOT("Direct", "One provider call, no tools, no loop. Fastest possible answer."),
    REACT("Agentic (ReAct)", "Think -> act -> observe -> self-correct, looping until the task is done."),
    PLAN_THEN_EXECUTE("Plan then execute", "Writes an explicit plan first, then works through it step by step."),
}

@Serializable
data class AgentOptions(
    val mode: AgentMode = AgentMode.REACT,
    val maxSteps: Int = 8,
    val maxToolCallsPerStep: Int = 3,
    val maxTotalToolCalls: Int = 12,
    val maxWallClockMs: Long = 5 * 60_000,
    val enabledToolNames: Set<String> = emptySet(),          // empty == every registered, non-disabled tool
    val disabledToolNames: Set<String> = emptySet(),
    val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.AUTO_APPROVE_SAFE,
    val showReasoning: ReasoningVisibility = ReasoningVisibility.EXPANDED_WHILE_STREAMING,
    val allowSelfCorrection: Boolean = true,
    val allowClarificationPauses: Boolean = true,
    val forceToolsOnFirstStep: Boolean = false,
    val temperatureForTools: Double? = 0.2,
    val parallelToolCalls: Boolean = false,
    /**
     * When the selected model advertises [ModelCapability.REASONING], ask the provider for its
     * reasoning stream (Anthropic thinking blocks, Gemini thought summaries, o-series effort).
     * DeepSeek-style providers stream it unconditionally and ignore this.
     */
    val autoEnableReasoning: Boolean = true,
    val defaultReasoningBudgetTokens: Int = 4_096,
)

@Serializable
enum class ReasoningVisibility(val label: String) {
    EXPANDED_WHILE_STREAMING("Stream thoughts open, auto-collapse"),
    COLLAPSED("Always collapsed"),
    HIDDEN("Never show (still recorded)"),
}

/** Where the agent currently is. Drives the live status chip above the composer. */
@Serializable
enum class AgentPhase(val label: String) {
    IDLE("Idle"),
    ANALYZING("Reading the request"),
    THINKING("Thinking"),
    CALLING_TOOL("Calling a tool"),
    OBSERVING("Reading tool output"),
    REFLECTING("Checking its own work"),
    ANSWERING("Writing the answer"),
    WAITING_FOR_USER("Waiting for you"),
    DONE("Done"),
}

@Serializable
enum class AgentStopReason {
    COMPLETED,
    MAX_STEPS_REACHED,
    MAX_TOOL_CALLS_REACHED,
    WALL_CLOCK_EXCEEDED,
    USER_CANCELLED,
    AWAITING_USER_INPUT,
    ERROR,
}

/**
 * The single observable contract of the agent harness. Everything the UI needs to render a live
 * run - thinking tokens, tool milestones, partial answer text, token accounting - arrives here as
 * an ordered stream, so the Chat screen is a pure function of this flow.
 */
sealed interface AgentEvent {

    val runId: String

    data class RunStarted(
        override val runId: String,
        val conversationId: String,
        val providerId: String,
        val modelId: String,
        val mode: AgentMode,
        val startedAtEpochMs: Long,
    ) : AgentEvent

    data class PhaseChanged(override val runId: String, val phase: AgentPhase) : AgentEvent

    /** Live reasoning tokens. DeepSeek `reasoning_content`, Anthropic `thinking_delta`, Gemini thoughts. */
    data class ThoughtDelta(override val runId: String, val stepIndex: Int, val text: String) : AgentEvent

    data class ThoughtCompleted(
        override val runId: String,
        val stepIndex: Int,
        val text: String,
        val durationMs: Long,
        val tokens: Int? = null,
    ) : AgentEvent

    /** Streaming final-answer text. */
    data class AnswerDelta(override val runId: String, val text: String) : AgentEvent

    /**
     * The engine rewrote text it had already streamed (e.g. a tool call printed as text was lifted
     * out of the answer). The UI replaces the bubble body with [fullText] instead of appending.
     */
    data class AnswerRevised(
        override val runId: String,
        val fullText: String,
        val reason: String,
    ) : AgentEvent

    data class ToolCallProposed(
        override val runId: String,
        val stepIndex: Int,
        val request: ToolCallRequest,
        val requiresApproval: Boolean,
        val approvalReason: String? = null,
    ) : AgentEvent

    data class ToolCallStarted(
        override val runId: String,
        val request: ToolCallRequest,
    ) : AgentEvent

    data class ToolCallFinished(
        override val runId: String,
        val result: ToolResult,
    ) : AgentEvent

    /** The model explicitly asked a question instead of guessing - loop parks until answered. */
    data class ClarificationRequested(
        override val runId: String,
        val question: String,
        val requestId: String,
    ) : AgentEvent

    data class UsageUpdated(override val runId: String, val usage: TokenUsage) : AgentEvent

    data class AssistantMessageFinalized(
        override val runId: String,
        val message: Message,
    ) : AgentEvent

    data class BudgetWarning(
        override val runId: String,
        val stepsUsed: Int,
        val maxSteps: Int,
    ) : AgentEvent

    data class RunFinished(
        override val runId: String,
        val reason: AgentStopReason,
        val steps: Int,
        val usage: TokenUsage,
        val finalMessageId: String?,
        val durationMs: Long,
    ) : AgentEvent

    data class RunFailed(override val runId: String, val error: AppError) : AgentEvent
}

/** Everything the orchestrator needs for one run. Immutable, so a run can be replayed in tests. */
@Serializable
data class AgentRunRequest(
    val runId: String,
    val conversationId: String,
    val provider: ProviderConfig,
    val model: ModelInfo,
    val history: List<Message>,
    val userMessage: Message,
    val personaSystemPrompt: String?,
    val options: AgentOptions = AgentOptions(),
    val sampling: SamplingOptions = SamplingOptions(),
    val streaming: Boolean = true,
)

@Serializable
data class SamplingOptions(
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxOutputTokens: Int? = null,
    val stopSequences: List<String> = emptyList(),
    /** Sent as `reasoning_effort` / `thinking.budget_tokens` / `thinkingConfig` where supported. */
    val reasoningBudgetTokens: Int? = null,
    val reasoningEffort: ReasoningEffort? = null,
)

@Serializable
enum class ReasoningEffort(val wireValue: String) {
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}
