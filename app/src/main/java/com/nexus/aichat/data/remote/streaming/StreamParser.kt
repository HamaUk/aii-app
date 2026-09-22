package com.nexus.aichat.data.remote.streaming

import com.nexus.aichat.core.ai.util.NexusJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * One decoded streaming frame, in the OpenAI style that most servers actually speak.
 *
 * This is the *fallback* vocabulary. Normal traffic is decoded by the protocol adapters in `:core:ai`,
 * which know each vendor's quirks. This parser exists for raw-REST endpoints and for the inspector,
 * where the app must make sense of whatever a user's server emits.
 */
sealed interface ChatDelta {

    data class Text(val text: String) : ChatDelta

    data class Reasoning(val text: String) : ChatDelta

    data class ToolFragment(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsFragment: String?,
    ) : ChatDelta

    data class Usage(val inputTokens: Int, val outputTokens: Int, val reasoningTokens: Int) : ChatDelta

    data class Finish(val reason: String?) : ChatDelta

    data class ServerError(val message: String) : ChatDelta
}

/**
 * Tolerant frame parser.
 *
 * Design rule: **never throw at the user.** A custom endpoint with an unusual envelope should degrade
 * to "this frame was not recognised" in the inspector, never to a failed conversation. Every accessor
 * below is therefore defensive, and an unknown frame returns null instead of raising.
 */
object StreamParser {

    private val TEXT_PATHS = listOf(
        "choices.0.delta.content",
        "delta.text",
        "token.text",
        "message.content",
        "response",
        "text",
    )

    private val REASONING_PATHS = listOf(
        "choices.0.delta.reasoning_content",
        "choices.0.delta.reasoning",
        "delta.thinking",
        "reasoning_content",
    )

    private val TOOL_PATHS = listOf(
        "choices.0.delta.tool_calls.0",
        "delta.tool_calls.0",
        "choices.0.message.tool_calls.0",
    )

    /** Blank keep-alives, SSE comments and `[DONE]` never reach a renderer. */
    fun parse(data: String): ChatDelta? {
        val payload = data.trim()
        if (payload.isEmpty() || payload.startsWith(":")) return null
        if (payload == DONE) return ChatDelta.Finish("stop")

        val root = parseObject(payload) ?: return null

        root.textAt("error.message")?.let { return ChatDelta.ServerError(it) }
        root.textAt("error")?.let { return ChatDelta.ServerError(it) }

        firstText(root, TEXT_PATHS)?.let { return ChatDelta.Text(it) }
        firstText(root, REASONING_PATHS)?.let { return ChatDelta.Reasoning(it) }

        root.objectAt("usage")?.let { usage ->
            val input = usage.intAt("prompt_tokens") ?: usage.intAt("input_tokens") ?: 0
            val output = usage.intAt("completion_tokens") ?: usage.intAt("output_tokens") ?: 0
            val reasoning = usage.intAt("completion_tokens_details.reasoning_tokens") ?: 0
            if (input + output > 0) return ChatDelta.Usage(input, output, reasoning)
        }

        for (path in TOOL_PATHS) {
            val call = root.objectAt(path) ?: continue
            val function = call.objectAt("function") ?: call
            val name = function.textAt("name")
            val arguments = function.textAt("arguments") ?: function.textAt("input")
            if (name != null || arguments != null) {
                return ChatDelta.ToolFragment(
                    index = call.intAt("index") ?: 0,
                    id = call.textAt("id"),
                    name = name,
                    argumentsFragment = arguments,
                )
            }
        }

        val finish = root.textAt("choices.0.finish_reason") ?: root.textAt("finish_reason")
        return finish?.let { ChatDelta.Finish(it) }
    }

    /** True when a buffer is valid JSON - the inspector uses this to mark "looks like" vs "is" JSON. */
    fun isJson(payload: String): Boolean = parseObject(payload) != null

    // --- defensive traversal ---------------------------------------------------------------------

    private fun parseObject(payload: String): JsonObject? =
        runCatching { NexusJson.instance.parseToJsonElement(payload) as? JsonObject }.getOrNull()

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    /**
     * Walks a dotted path such as `choices.0.delta.content`.
     *
     * The traversal is over [JsonElement], not [JsonObject], because half of these paths pass through an
     * array: `choices` is a list and `0` indexes it. Each segment is either an object key or an array
     * index, and a segment that does not exist simply ends the walk with null.
     */
    private fun JsonObject.walk(path: String): JsonElement? {
        var current: JsonElement = this
        for (segment in path.split('.')) {
            val next: JsonElement? = when (val element = current) {
                is JsonObject -> element[segment]
                is JsonArray -> segment.toIntOrNull()?.let(element::getOrNull)
                else -> null
            }
            current = next ?: return null
        }
        return current
    }

    private fun JsonObject.objectAt(path: String): JsonObject? = walk(path)?.asObject()

    private fun JsonObject.textAt(path: String): String? {
        val primitive = walk(path) as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
    }

    private fun JsonObject.intAt(path: String): Int? = rawIntAt(path)

    private fun JsonObject.rawIntAt(path: String): Int? = (walk(path) as? JsonPrimitive)?.intOrNull

    private fun firstText(root: JsonObject, paths: List<String>): String? {
        for (path in paths) root.textAt(path)?.let { return it }
        return null
    }

    private const val DONE = "[DONE]"
}
