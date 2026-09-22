package com.nexus.aichat.domain.model

import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.ToolCallStatus

/**
 * One node of the reasoning tree, in render order.
 *
 * This is a *UI* model, deliberately separate from `:core:model`'s `AgentEvent` and `MessagePart`:
 * the event stream is a transport detail (events arrive in bursts, out of order across steps, with
 * fragments), while this is the settled structure the tree renders and the user can expand, collapse
 * and click into.
 *
 * The processor that builds it is [com.nexus.aichat.domain.agent.ThoughtProcessor].
 */
sealed interface AgentStep {

    val id: String
    val stepIndex: Int
    val startedAtEpochMs: Long

    /** Model reasoning tokens ("thinking"). */
    data class Thinking(
        override val id: String,
        override val stepIndex: Int,
        override val startedAtEpochMs: Long,
        val text: String,
        val isStreaming: Boolean = false,
        val durationMs: Long? = null,
        val tokens: Int? = null,
    ) : AgentStep

    /** A tool the agent decided to call, with its lifecycle and result. */
    data class ToolInvocation(
        override val id: String,
        override val stepIndex: Int,
        override val startedAtEpochMs: Long,
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
        val status: ToolCallStatus,
        val resultPreview: String? = null,
        val resultContent: String? = null,
        val isError: Boolean = false,
        val durationMs: Long? = null,
        val requiresApproval: Boolean = false,
        val approvalReason: String? = null,
    ) : AgentStep

    /** The agent asking the user something, mid-run. */
    data class Clarification(
        override val id: String,
        override val stepIndex: Int,
        override val startedAtEpochMs: Long,
        val question: String,
        val answer: String? = null,
    ) : AgentStep

    /** Model prose that is not the final answer (usually a "let me check" narration). */
    data class Narration(
        override val id: String,
        override val stepIndex: Int,
        override val startedAtEpochMs: Long,
        val text: String,
    ) : AgentStep

    /** The bottom line, rendered outside the collapsible tree. */
    data class Answer(
        override val id: String,
        override val stepIndex: Int,
        override val startedAtEpochMs: Long,
        val text: String,
        val isStreaming: Boolean,
    ) : AgentStep
}

/** Everything the chat surface needs to render a live or settled run. */
data class AgentTrace(
    val mode: AgentMode = AgentMode.REACT,
    val steps: List<AgentStep> = emptyList(),
    val finalAnswer: String = "",
    val isRunning: Boolean = false,
    val awaitingApproval: AgentStep.ToolInvocation? = null,
    val awaitingClarification: AgentStep.Clarification? = null,
) {
    val hasThoughts: Boolean get() = steps.isNotEmpty()
    val toolCallCount: Int get() = steps.count { it is AgentStep.ToolInvocation }
    val thinkingTokens: Int get() = steps.filterIsInstance<AgentStep.Thinking>().sumOf { it.tokens ?: 0 }
}
