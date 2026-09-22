package com.nexus.aichat.core.ai.engine

import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.core.model.ToolCallRequest

/**
 * Normalised streaming output of the engine, independent of provider and protocol.
 * This is what the ViewModel renders; it never sees a raw SSE frame.
 */
sealed interface StreamEvent {

    data class Started(val atEpochMs: Long, val modelId: String, val providerId: String) : StreamEvent

    data class TextDelta(val text: String) : StreamEvent

    data class ReasoningDelta(val text: String) : StreamEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsFragment: String?,
    ) : StreamEvent

    data class Usage(val usage: TokenUsage, val timeToFirstTokenMs: Long?) : StreamEvent

    /**
     * The full answer text after post-processing. Emitted when the engine had to rewrite the stream
     * it already sent (e.g. it extracted a tool call that a model printed as text), so the UI can
     * swap the bubble content instead of appending.
     */
    data class TextRevised(val fullText: String, val reason: String) : StreamEvent

    data class Finished(
        val finishReason: String?,
        val text: String,
        val reasoning: String,
        val toolCalls: List<ToolCallRequest>,
        val usage: TokenUsage?,
        val durationMs: Long,
        val timeToFirstTokenMs: Long?,
    ) : StreamEvent
}
