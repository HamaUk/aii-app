package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import com.nexus.aichat.core.model.ToolCallRequest

/**
 * Recovers tool calls that a model printed as *text* instead of returning them through the protocol.
 *
 * This is not paranoia: smaller open models (and any model served by an endpoint with native tool
 * calling switched off) routinely emit
 *
 *     {"name": "web_fetch", "arguments": {"url": "https://…"}}
 *
 * or the Hermes/Qwen envelope
 *
 *     <tool_call>{"name": "web_fetch", "arguments": {...}}</tool_call>
 *
 * Without this, an agentic run on a self-hosted model looks like it "decided to print JSON" -
 * so we detect and execute those calls, then strip the raw text from the rendered answer.
 */
object InlineToolCallParser {

    private val ENVELOPES = listOf(
        Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""", RegexOption.IGNORE_CASE),
        Regex("""```(?:json|tool_call|tool-code)\s*([\s\S]*?)```""", RegexOption.IGNORE_CASE),
        Regex("""<\|tool_call\|>\s*([\s\S]*?)\s*<\|/tool_call\|>"""),
    )

    data class Extracted(
        val calls: List<ToolCallRequest>,
        /** Text with every recognised tool-call envelope removed. */
        val cleanedText: String,
    ) {
        val found: Boolean get() = calls.isNotEmpty()
    }

    /**
     * @param knownToolNames when non-empty, a candidate is only accepted if it names a real tool -
     *   this is what keeps a legitimate JSON code block in an answer from being executed.
     */
    fun extract(
        text: String,
        knownToolNames: Set<String> = emptySet(),
        prefix: String = "inline",
    ): Extracted {
        if (text.isBlank()) return Extracted(emptyList(), text)

        val calls = mutableListOf<ToolCallRequest>()
        var cleaned = text
        var counter = 0

        ENVELOPES.forEach { regex ->
            cleaned = regex.replace(cleaned) { match ->
                val payload = match.groupValues[1].trim()
                val parsed = parseCall(payload, knownToolNames, "${prefix}_${counter}")
                if (parsed != null) {
                    counter++
                    calls += parsed
                    ""                       // strip the envelope from the visible answer
                } else {
                    match.value              // not a tool call: leave the code block alone
                }
            }
        }

        // Bare JSON object on its own line (last resort, strictly gated on a known tool name).
        if (calls.isEmpty() && knownToolNames.isNotEmpty()) {
            val lineRegex = Regex("""(?m)^\s*(\{[^\n]*"name"\s*:[^\n]*\})\s*$""")
            cleaned = lineRegex.replace(cleaned) { match ->
                val parsed = parseCall(match.groupValues[1], knownToolNames, "${prefix}_${counter}")
                if (parsed != null) {
                    counter++
                    calls += parsed
                    ""
                } else {
                    match.value
                }
            }
        }

        return Extracted(calls = calls, cleanedText = cleaned.trim())
    }

    private fun parseCall(payload: String, knownToolNames: Set<String>, fallbackId: String): ToolCallRequest? {
        val element = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(payload) }.getOrNull()) ?: return null

        // Shape A: {"name": "...", "arguments": {...}}  |  {"name": "...", "parameters": {...}}
        val name = JsonX.str(element, "name")
            ?: JsonX.str(JsonX.obj(element["function"]), "name")
            ?: JsonX.str(element, "tool")
            ?: return null
        if (name.isBlank()) return null
        if (knownToolNames.isNotEmpty() && name !in knownToolNames) return null

        val arguments = element.let { root ->
            JsonX.obj(root)?.get("arguments")
                ?: JsonX.obj(root)?.get("parameters")
                ?: JsonX.obj(root)?.get("args")
                ?: JsonX.obj(JsonX.obj(root)?.get("function"))?.get("arguments")
        }
        val argumentsJson = when (arguments) {
            null -> "{}"
            is kotlinx.serialization.json.JsonPrimitive -> arguments.content
            else -> arguments.toString()
        }
        val id = JsonX.str(element, "id") ?: fallbackId
        return ToolCallRequest(callId = id, toolName = name, argumentsJson = argumentsJson)
    }
}
