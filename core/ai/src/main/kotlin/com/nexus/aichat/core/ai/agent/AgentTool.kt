package com.nexus.aichat.core.ai.agent

import com.nexus.aichat.core.common.di.NexusDispatchers
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.AgentEvent
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Ambient context handed to a tool at execution time. Tools must be side-effect-free outside of this
 * context and must never block the caller's thread.
 */
data class ToolContext(
    val runId: String,
    val conversationId: String,
    val timeProvider: TimeProvider,
    val logger: NexusLogger,
    val dispatchers: NexusDispatchers,
    /** Progress reporting into the live run (rendered as sub-steps in the thought tree). */
    val reportProgress: suspend (String) -> Unit = {},
    /** Extra event emission for tools that yield artifacts (citations, extracted documents). */
    val emit: suspend (AgentEvent) -> Unit = {},
)

/**
 * A pluggable capability of the agent.
 *
 * Implementations live in this module when they are pure JVM (web fetch, HTTP search, time) and in
 * the Android layer when they need platform services (PDF rasterisation, OCR, gallery access).
 */
interface AgentTool {

    val spec: ToolSpec

    /**
     * @param arguments the model-supplied arguments object, already parsed. Never trust its shape:
     *   every tool validates and returns a [ToolResult] error rather than throwing.
     */
    suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult
}

/** Convenience base class that maps thrown exceptions into well-formed tool errors. */
abstract class SafeAgentTool : AgentTool {

    final override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val startedAt = context.timeProvider.elapsedMillis()
        return try {
            executeChecked(arguments, context, startedAt)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            context.logger.w(TAG, "tool ${spec.name} failed: ${t.message}", t)
            ToolResult.error(
                callId = ARG_CALL_ID,
                toolName = spec.name,
                message = t.message ?: t::class.simpleName ?: "unknown error",
                cause = t,
            )
        }
    }

    protected abstract suspend fun executeChecked(
        arguments: JsonObject,
        context: ToolContext,
        startedAtMs: Long,
    ): ToolResult

    private companion object {
        const val TAG = "AgentTool"
        const val ARG_CALL_ID = "unknown"
    }
}

/** Argument helpers shared by every tool implementation. */
object ToolArgs {

    fun string(arguments: JsonObject, key: String): String? =
        arguments[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.takeIf { it.isNotBlank() }

    fun int(arguments: JsonObject, key: String, default: Int): Int =
        arguments[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: default

    fun boolean(arguments: JsonObject, key: String, default: Boolean): Boolean =
        arguments[key]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() } ?: default

    fun stringList(arguments: JsonObject, key: String): List<String> =
        arguments[key]?.let { element ->
            when (element) {
                is kotlinx.serialization.json.JsonArray -> element.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                is kotlinx.serialization.json.JsonPrimitive -> listOf(element.content)
                else -> emptyList()
            }
        }.orEmpty()

    /** Truncates tool output to the spec's budget, keeping head and tail (errors live at the end). */
    fun clamp(content: String, maxChars: Int): Pair<String, Boolean> {
        if (content.length <= maxChars) return content to false
        val head = (maxChars * 0.7).toInt()
        val tail = maxChars - head - ELLIPSIS.length
        return (content.take(head) + ELLIPSIS + content.takeLast(tail)) to true
    }

    private const val ELLIPSIS = "\n\n... [truncated by Nexus: middle of the output omitted] ...\n\n"
}
