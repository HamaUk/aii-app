package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import com.nexus.aichat.core.ai.util.jsonEscape
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.TokenUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Escape hatch for endpoints that match none of the three dialects.
 *
 * Two layers of flexibility, both configured in the Custom Provider builder:
 *  1. **Request shaping** - an optional body template with `{{model}}`, `{{system}}`, `{{messages}}`,
 *     `{{prompt}}`, `{{stream}}`, `{{temperature}}`, `{{max_tokens}}` placeholders. Without a
 *     template we emit a sensible OpenAI-ish body.
 *  2. **Response shaping** - an optional dot-path (`choices.0.delta.content`, `data.text`,
 *     `output.0.content`) pointing at the text; otherwise we walk a list of known envelopes.
 *
 * Streaming reads newline-delimited frames and extracts whatever text it can, so even a bespoke
 * vendor endpoint degrades to *working*, not to *broken*.
 */
class RawRestAdapter : ProviderAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.RAW_REST

    override fun headers(config: ProviderConfig, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "application/json, text/event-stream")
        config.extraHeaders.forEach { (k, v) -> put(k, v) }
        apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            when (config.auth.scheme) {
                AuthScheme.NONE, AuthScheme.QUERY_PARAM -> Unit
                AuthScheme.BEARER -> put("Authorization", "Bearer $key")
                AuthScheme.X_API_KEY -> put("x-api-key", key)
                AuthScheme.X_GOOG_API_KEY -> put("x-goog-api-key", key)
                AuthScheme.CUSTOM_HEADER -> put(config.auth.headerName?.takeIf { n -> n.isNotBlank() } ?: "x-api-key", key)
            }
        }
    }

    override fun encodeChat(request: ProviderChatRequest, config: ProviderConfig): String {
        val template = config.requestBodyTemplate
        if (!template.isNullOrBlank()) return fillTemplate(template, request)

        return buildJsonObject {
            put("model", JsonPrimitive(request.model))
            request.system?.takeIf { it.isNotBlank() }?.let { put("system", JsonPrimitive(it)) }
            putJsonArray("messages") {
                request.messages.forEach { message ->
                    addJsonObject {
                        put("role", JsonPrimitive(message.role.name.lowercase()))
                        put("content", JsonPrimitive(message.text))
                    }
                }
            }
            put("stream", JsonPrimitive(request.streaming))
            put("temperature", JsonPrimitive(request.sampling.temperature))
            request.sampling.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        }.toString()
    }

    private fun fillTemplate(template: String, request: ProviderChatRequest): String {
        val messagesJson = buildJsonArray {
            request.messages.forEach { message ->
                addJsonObject {
                    put("role", JsonPrimitive(message.role.name.lowercase()))
                    put("content", JsonPrimitive(message.text))
                }
            }
        }.toString()
        val lastUserText = request.messages.lastOrNull { it.role == ProviderRole.USER }?.text.orEmpty()
        return template
            .replace("{{model}}", request.model.jsonEscaped())
            .replace("{{system}}", (request.system ?: "").jsonEscaped())
            .replace("{{messages}}", messagesJson)
            .replace("{{prompt}}", lastUserText.jsonEscaped())
            .replace("{{stream}}", request.streaming.toString())
            .replace("{{temperature}}", request.sampling.temperature.toString())
            .replace("{{max_tokens}}", (request.sampling.maxOutputTokens ?: 4_096).toString())
    }

    private fun String.jsonEscaped(): String = "\"${jsonEscape()}\""

    override fun decodeStreamEvent(eventName: String?, data: String): ProviderStreamChunk? {
        if (data.isBlank()) return null
        val trimmed = data.trim()

        // Plain-text streaming (some SSE endpoints just push prose).
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return ProviderStreamChunk(textDelta = data)
        }
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(trimmed) }.getOrNull()) ?: return null
        if (root is JsonArray) {
            return ProviderStreamChunk(textDelta = root.joinToString("") { (it as? JsonPrimitive)?.content.orEmpty() })
        }

        val usage = JsonX.obj(root["usage"])
            ?: JsonX.obj(root["usageMetadata"])
            ?: JsonX.obj(JsonX.array(root["choices"])?.firstOrNull()?.let { JsonX.obj(it) }?.get("usage"))
        val finish = JsonX.str(root, "finish_reason")
            ?: JsonX.str(root, "stop_reason")
            ?: JsonX.str(JsonX.array(root["choices"])?.firstOrNull(), "finish_reason")

        return ProviderStreamChunk(
            textDelta = extractText(root),
            reasoningDelta = JsonX.str(root, "reasoning_content") ?: JsonX.str(root, "reasoning"),
            usage = usage?.let {
                val input = JsonX.int(it, "prompt_tokens") ?: JsonX.int(it, "input_tokens") ?: JsonX.int(it, "promptTokenCount") ?: 0
                val output = JsonX.int(it, "completion_tokens") ?: JsonX.int(it, "output_tokens") ?: JsonX.int(it, "candidatesTokenCount") ?: 0
                TokenUsage(inputTokens = input, outputTokens = output, totalTokens = input + output, providerReported = true)
            },
            finishReason = finish,
        ).takeUnless { it.isEmpty }
    }

    /** Walks the configured dot-path, then a list of well-known envelopes. */
    override fun decodeChatResponse(body: String, config: ProviderConfig?): ProviderChatResponse {
        val root = runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()
        // The custom-provider wizard's `responseTextPath` is the whole point of this adapter: without
        // it a bespoke envelope would only decode when it happens to look like a known shape.
        val path = config?.responseTextPath?.takeIf { it.isNotBlank() }
        return ProviderChatResponse(text = extractText(root, path).orEmpty())
    }

    fun extractTextWithPath(root: JsonElement?, path: String?): String? = extractText(root, path)

    private fun extractText(root: JsonElement?, configuredPath: String? = null): String? {
        if (root == null) return null
        configuredPath?.takeIf { it.isNotBlank() }?.let { path ->
            walk(root, path.split('.'))?.let { return primitiveToString(it) }
        }
        if (root is JsonPrimitive) return root.content
        val obj = JsonX.obj(root) ?: return null

        // 1. OpenAI-ish
        JsonX.array(obj["choices"])?.firstOrNull()?.let { choice ->
            val delta = JsonX.obj(JsonX.obj(choice)?.get("delta"))
            JsonX.str(delta, "content")?.let { return it }
            JsonX.str(JsonX.obj(choice), "text")?.let { return it }
            JsonX.str(JsonX.obj(JsonX.obj(choice)?.get("message")), "content")?.let { return it }
        }
        // 2. Ollama native / simple vendors
        JsonX.str(obj, "response")?.let { return it }
        JsonX.str(obj, "content")?.let { return it }
        JsonX.str(obj, "text")?.let { return it }
        JsonX.str(obj, "output_text")?.let { return it }
        JsonX.str(obj, "completion")?.let { return it }
        // 3. OpenAI Responses-style envelope
        JsonX.array(obj["output"])?.forEach { item ->
            JsonX.array(JsonX.obj(item)?.get("content"))?.forEach { part ->
                JsonX.str(JsonX.obj(part), "text")?.let { return it }
            }
        }
        // 4. Deeply nested `data` wrapper
        JsonX.obj(obj["data"])?.let { data -> return extractText(data) }
        return null
    }

    private fun walk(root: JsonElement, path: List<String>): JsonElement? {
        var current: JsonElement? = root
        path.forEach { segment ->
            current = when (val node = current) {
                is JsonObject -> node[segment]
                is JsonArray -> segment.toIntOrNull()?.let { node.getOrNull(it) }
                else -> null
            }
            if (current == null) return null
        }
        return current
    }

    private fun primitiveToString(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.content
        is JsonArray -> element.joinToString("") { primitiveToString(it).orEmpty() }
        is JsonObject -> extractText(element)
    }

    override fun decodeError(status: Int, body: String, providerId: String?): AppError? {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return null
        val message = JsonX.str(root, "message")
            ?: JsonX.str(root, "error")
            ?: JsonX.str(JsonX.obj(root["error"]), "message")
            ?: JsonX.str(root, "detail")
            ?: return null
        return when (status) {
            401, 403 -> AppError.Auth(message, providerId = providerId)
            429 -> AppError.RateLimited(message)
            else -> AppError.Provider(message, httpStatus = status, rawBody = body.take(2_000))
        }
    }

    override fun decodeModels(body: String, providerId: String): List<ModelInfo> {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return emptyList()
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonX.array(root["data"]) ?: JsonX.array(root["models"]) ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { id ->
                    ModelInfo(id = id, providerId = providerId, source = com.nexus.aichat.core.model.ModelSource.DISCOVERED)
                }
                is JsonObject -> {
                    val id = JsonX.str(element, "id") ?: JsonX.str(element, "name") ?: JsonX.str(element, "model")
                    id?.takeIf { it.isNotBlank() }?.let {
                        ModelInfo(
                            id = it.removePrefix("models/"),
                            displayName = it.removePrefix("models/"),
                            providerId = providerId,
                            capabilities = ModelHeuristics.capabilitiesFor(it),
                            source = com.nexus.aichat.core.model.ModelSource.DISCOVERED,
                        )
                    }
                }
                else -> null
            }
        }
    }
}
