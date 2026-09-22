package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.core.model.ToolCallRequest

/**
 * Folds a stream of [ProviderStreamChunk]s into one coherent turn.
 *
 * Three problems this solves, each of which shows up as a visible bug if you skip it:
 *  1. **Fragmented tool calls.** OpenAI sends tool-call arguments as JSON string fragments split at
 *     arbitrary byte boundaries (`{"ur`, `l": "http`, `s://…}`). The draft map reassembles them.
 *  2. **Reasoning vs answer.** Reasoning tokens must never leak into the answer bubble, and they must
 *     be counted separately for cost/telemetry.
 *  3. **Redundant frames.** Several local servers re-send the accumulated message on every chunk.
 *     The accumulator emits only genuinely new content downstream so the UI does not double-render.
 */
class StreamAccumulator {

    private val textBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    private val toolDrafts = LinkedHashMap<Int, ToolCallDraft>()

    private var lastTextSnapshotLength = 0
    private var lastReasoningSnapshotLength = 0

    var usage: TokenUsage? = null
        private set

    var finishReason: String? = null
        private set

    var providerId: String? = null
        private set

    val text: String get() = textBuilder.toString()
    val reasoning: String get() = reasoningBuilder.toString()
    val hasToolCalls: Boolean get() = toolDrafts.isNotEmpty()

    fun accept(chunk: ProviderStreamChunk): ProviderStreamChunk? {
        chunk.errorMessage?.let { return chunk }

        chunk.usage?.let { usage = usage?.plus(it) ?: it }
        chunk.finishReason?.let { finishReason = it }
        chunk.providerId?.let { providerId = it }

        var newText: String? = null
        chunk.textDelta?.takeIf { it.isNotEmpty() }?.let { delta ->
            textBuilder.append(delta)
            newText = delta
        }

        var newReasoning: String? = null
        chunk.reasoningDelta?.takeIf { it.isNotEmpty() }?.let { delta ->
            reasoningBuilder.append(delta)
            newReasoning = delta
        }

        chunk.toolCallDeltas.forEach { delta ->
            val draft = toolDrafts.getOrPut(delta.index) { ToolCallDraft(index = delta.index) }
            draft.merge(delta)
        }

        val normalized = chunk.copy(
            textDelta = newText,
            reasoningDelta = newReasoning,
            toolCallDeltas = chunk.toolCallDeltas,
        )
        return normalized.takeUnless { it.isEmpty }
    }

    /**
     * Collapses repeated "full snapshot" streams (a few OpenAI-compatible servers, plus every
     * server behind a buffering proxy) into a delta-only view. Call this when the accumulated text
     * turns out to be a superstring of what was already rendered.
     */
    fun diffAgainstRendered(renderedSoFar: String): String? {
        val current = textBuilder.toString()
        if (current.length <= renderedSoFar.length) return null
        if (!current.startsWith(renderedSoFar)) {
            lastTextSnapshotLength = renderedSoFar.length
            return current
        }
        val delta = current.substring(renderedSoFar.length)
        lastTextSnapshotLength = current.length
        return delta.ifEmpty { null }
    }

    fun toolCallRequests(): List<ToolCallRequest> = toolDrafts.values
        .sortedBy { it.index }
        .filter { it.name.isNotBlank() }
        .map { draft ->
            ToolCallRequest(
                callId = draft.id ?: "call_${draft.index}_${draft.name}",
                toolName = draft.name,
                argumentsJson = draft.arguments.toString().ifBlank { "{}" },
            )
        }

    fun snapshot(): AccumulatedTurn = AccumulatedTurn(
        text = textBuilder.toString(),
        reasoning = reasoningBuilder.toString(),
        toolCalls = toolCallRequests(),
        usage = usage,
        finishReason = finishReason,
        snapshotLengths = lastTextSnapshotLength to lastReasoningSnapshotLength,
    )

    private class ToolCallDraft(val index: Int) {
        var id: String? = null
        var name: String = ""
        val arguments = StringBuilder()

        fun merge(delta: ProviderToolCallDelta) {
            delta.id?.takeIf { it.isNotBlank() }?.let { id = it }
            delta.name?.takeIf { it.isNotBlank() }?.let { name = it }
            delta.argumentsFragment?.let { fragment ->
                if (delta.replaceArgs) {
                    arguments.clear()
                    arguments.append(fragment)
                } else {
                    arguments.append(fragment)
                }
            }
        }
    }
}

data class AccumulatedTurn(
    val text: String,
    val reasoning: String,
    val toolCalls: List<ToolCallRequest>,
    val usage: TokenUsage?,
    val finishReason: String?,
    val snapshotLengths: Pair<Int, Int> = 0 to 0,
)
