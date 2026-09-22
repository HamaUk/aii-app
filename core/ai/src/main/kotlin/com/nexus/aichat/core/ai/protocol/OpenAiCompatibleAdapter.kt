package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ModelCapability
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.TokenUsage
import com.nexus.aichat.core.common.error.AppError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `POST {base}/chat/completions` with SSE deltas.
 *
 * This single adapter covers OpenAI, Azure-style gateways, Groq, DeepSeek, Mistral, OpenRouter, xAI,
 * Together, Fireworks, Perplexity, vLLM, SGLang, llama.cpp server, LM Studio, Ollama (`/v1` shim) and
 * essentially every private proxy in existence - which is why the custom-endpoint builder defaults
 * to this protocol.
 */
class OpenAiCompatibleAdapter : ProviderAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE

    override fun headers(config: ProviderConfig, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        config.extraHeaders.forEach { (k, v) -> put(k, v) }
        when (config.auth.scheme) {
            AuthScheme.BEARER -> apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
            AuthScheme.X_API_KEY -> apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
            AuthScheme.X_GOOG_API_KEY -> apiKey?.takeIf { it.isNotBlank() }?.let { put("x-goog-api-key", it) }
            AuthScheme.CUSTOM_HEADER -> apiKey?.takeIf { it.isNotBlank() }?.let {
                put(config.auth.headerName?.takeIf { name -> name.isNotBlank() } ?: "x-api-key", it)
            }
            // Gateways that want ?key=... on a chat URL are rare; handled in [chatUrl].
            AuthScheme.QUERY_PARAM -> Unit
            AuthScheme.NONE -> Unit
        }
    }

    override fun encodeChat(request: ProviderChatRequest, config: ProviderConfig): String {
        val body = buildJsonObject {
            put("model", JsonPrimitive(request.model))
            putJsonArray("messages") {
                request.system?.takeIf { it.isNotBlank() }?.let { system ->
                    addJsonObject {
                        put("role", JsonPrimitive("system"))
                        put("content", JsonPrimitive(system))
                    }
                }
                request.messages.forEach { message -> add(encodeMessage(message)) }
            }
            put("stream", JsonPrimitive(request.streaming))
            if (request.streaming && config.includeStreamUsage) {
                putJsonObject("stream_options") { put("include_usage", JsonPrimitive(true)) }
            }
            request.sampling.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
            request.sampling.temperature.takeIf { it >= 0 }?.let { put("temperature", JsonPrimitive(it)) }
            request.sampling.topP.takeIf { it in 0.0..1.0 && it != 1.0 }?.let { put("top_p", JsonPrimitive(it)) }
            request.sampling.reasoningEffort?.let { put("reasoning_effort", JsonPrimitive(it.wireValue)) }
            if (request.sampling.stopSequences.isNotEmpty()) {
                putJsonArray("stop") { request.sampling.stopSequences.forEach { add(JsonPrimitive(it)) } }
            }
            if (request.tools.isNotEmpty() && config.supportsNativeTools) {
                putJsonArray("tools") {
                    request.tools.forEach { spec ->
                        addJsonObject {
                            put("type", JsonPrimitive("function"))
                            putJsonObject("function") {
                                put("name", JsonPrimitive(spec.name))
                                put("description", JsonPrimitive(spec.description))
                                put("parameters", NexusJson.instance.parseToJsonElement(spec.parametersJsonSchema))
                            }
                        }
                    }
                }
                put("tool_choice", encodeToolChoice(request.toolChoice))
            } else if (request.toolChoice is ToolChoice.None) {
                put("tool_choice", JsonPrimitive("none"))
            }
        }
        return body.toString()
    }

    private fun encodeToolChoice(choice: ToolChoice) = when (choice) {
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Specific -> buildJsonObject {
            put("type", JsonPrimitive("function"))
            putJsonObject("function") { put("name", JsonPrimitive(choice.toolName)) }
        }
    }

    private fun encodeMessage(message: ProviderMessage): JsonObject = buildJsonObject {
        put("role", JsonPrimitive(message.role.wireName()))
        when {
            // Assistant turn that requested tools: content may be empty, tool_calls carries the intent.
            message.toolCalls.isNotEmpty() -> {
                put("content", JsonPrimitive(message.text.ifBlank { "" }))
                putJsonArray("tool_calls") {
                    message.toolCalls.forEach { call ->
                        addJsonObject {
                            put("id", JsonPrimitive(call.callId))
                            put("type", JsonPrimitive("function"))
                            putJsonObject("function") {
                                put("name", JsonPrimitive(call.toolName))
                                put("arguments", JsonPrimitive(call.argumentsJson))
                            }
                        }
                    }
                }
            }
            // Tool observation fed back to the model.
            message.role == ProviderRole.TOOL -> {
                put("tool_call_id", JsonPrimitive(message.toolResultCallId().orEmpty()))
                message.name?.let { put("name", JsonPrimitive(it)) }
                put("content", JsonPrimitive(message.text))
            }
            // Multimodal user/assistant turn.
            message.content.any { it is ProviderContentPart.ImageBase64 } -> {
                putJsonArray("content") {
                    message.content.forEach { part ->
                        when (part) {
                            is ProviderContentPart.Text -> addJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(part.text))
                            }
                            is ProviderContentPart.ImageBase64 -> addJsonObject {
                                put("type", JsonPrimitive("image_url"))
                                putJsonObject("image_url") {
                                    put("url", JsonPrimitive("data:${part.mimeType};base64,${part.base64}"))
                                }
                            }
                            is ProviderContentPart.ToolResultBlock -> addJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(part.content))
                            }
                        }
                    }
                }
            }
            else -> put("content", JsonPrimitive(message.text))
        }
    }

    override fun decodeStreamEvent(eventName: String?, data: String): ProviderStreamChunk? {
        if (data.isBlank()) return null
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(data) }.getOrNull()) ?: return null
        if (root !is JsonObject) return null

        // Mid-stream error frames are legal in the OpenAI dialect and must not be swallowed.
        root["error"]?.let { errorElement ->
            val message = JsonX.str(JsonX.obj(errorElement), "message")
                ?: JsonX.str(root, "error")
                ?: errorElement.toString()
            return ProviderStreamChunk(finishReason = "error", errorMessage = message)
        }

        val choice = JsonX.array(root["choices"])?.firstOrNull()?.let { JsonX.obj(it) }
        val delta = JsonX.obj(choice?.get("delta"))
        val usage = parseUsage(root["usage"])

        var text: String? = null
        var reasoning: String? = null

        if (delta != null) {
            text = extractText(delta["content"])
            // DeepSeek / vLLM / OpenRouter / Groq all agree on `reasoning_content`;
            // a few proxies use `reasoning` or `reasoning_text`.
            reasoning = JsonX.str(delta, "reasoning_content")
                ?: JsonX.str(delta, "reasoning")
                ?: JsonX.str(delta, "reasoning_text")
        } else if (choice != null) {
            // Non-streaming shape leaking into a stream.
            text = extractText(JsonX.obj(choice["message"])?.get("content"))
        }

        val toolDeltas = JsonX.array(delta?.get("tool_calls"))?.mapIndexedNotNull { position, element ->
            val call = JsonX.obj(element) ?: return@mapIndexedNotNull null
            ProviderToolCallDelta(
                index = JsonX.int(call, "index") ?: position,
                id = JsonX.str(call, "id"),
                name = JsonX.str(JsonX.obj(call["function"]), "name"),
                argumentsFragment = JsonX.str(JsonX.obj(call["function"]), "arguments"),
            )
        }.orEmpty()

        val finish = JsonX.str(choice, "finish_reason")

        return ProviderStreamChunk(
            textDelta = text,
            reasoningDelta = reasoning,
            toolCallDeltas = toolDeltas,
            usage = usage,
            finishReason = finish,
            providerId = JsonX.str(root, "id"),
            modelId = JsonX.str(root, "model"),
        ).takeUnless { it.isEmpty }
    }

    /** `content` is a string in the spec, but servers send arrays of parts often enough to matter. */
    private fun extractText(element: kotlinx.serialization.json.JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.content.takeIf { it.isNotEmpty() }
        else -> JsonX.array(element)
            ?.mapNotNull { part -> JsonX.str(part, "text") ?: JsonX.str(part, "content") }
            ?.joinToString("")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun parseUsage(element: kotlinx.serialization.json.JsonElement?): TokenUsage? {
        val usage = JsonX.obj(element) ?: return null
        val prompt = JsonX.int(usage, "prompt_tokens") ?: JsonX.int(usage, "input_tokens") ?: 0
        val completion = JsonX.int(usage, "completion_tokens") ?: JsonX.int(usage, "output_tokens") ?: 0
        val reasoning = JsonX.int(JsonX.obj(usage["completion_tokens_details"]), "reasoning_tokens") ?: 0
        val cached = JsonX.int(JsonX.obj(usage["prompt_tokens_details"]), "cached_tokens") ?: 0
        val total = JsonX.int(usage, "total_tokens") ?: (prompt + completion)
        return TokenUsage(
            inputTokens = prompt,
            outputTokens = completion,
            reasoningTokens = reasoning,
            cachedInputTokens = cached,
            totalTokens = total,
            providerReported = true,
        )
    }

    override fun decodeChatResponse(body: String, config: ProviderConfig?): ProviderChatResponse {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return ProviderChatResponse("")
        val choice = JsonX.array(root["choices"])?.firstOrNull()
        val message = JsonX.obj(JsonX.obj(choice)?.get("message"))
        val toolCalls = JsonX.array(message?.get("tool_calls"))?.mapIndexedNotNull { index, element ->
            val call = JsonX.obj(element) ?: return@mapIndexedNotNull null
            com.nexus.aichat.core.model.ToolCallRequest(
                callId = JsonX.str(call, "id") ?: "call_$index",
                toolName = JsonX.str(JsonX.obj(call["function"]), "name").orEmpty(),
                argumentsJson = JsonX.str(JsonX.obj(call["function"]), "arguments") ?: "{}",
            )
        }.orEmpty()
        return ProviderChatResponse(
            text = extractText(message?.get("content")).orEmpty(),
            reasoning = JsonX.str(message, "reasoning_content").orEmpty(),
            toolCalls = toolCalls,
            usage = parseUsage(root["usage"]),
            finishReason = JsonX.str(JsonX.obj(choice), "finish_reason"),
            modelId = JsonX.str(root, "model"),
        )
    }

    override fun decodeError(status: Int, body: String, providerId: String?): AppError? {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) as? JsonObject ?: return null
        val error = JsonX.obj(root["error"]) ?: root
        val message = JsonX.str(error, "message") ?: JsonX.str(root, "message") ?: return null
        val code = JsonX.str(error, "code") ?: JsonX.str(error, "type")
        return when (status) {
            401, 403 -> AppError.Auth(message, providerId = providerId)
            402 -> AppError.RateLimited(message)
            429 -> AppError.RateLimited(message)
            else -> AppError.Provider(message, httpStatus = status, errorCode = code, rawBody = body.take(2_000))
        }
    }

    override fun decodeModels(body: String, providerId: String): List<ModelInfo> {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return emptyList()
        val array = JsonX.array(root) ?: JsonX.array(JsonX.obj(root)?.get("data")) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = JsonX.obj(element) ?: return@mapNotNull null
            val id = JsonX.str(obj, "id") ?: JsonX.str(obj, "name") ?: return@mapNotNull null
            if (id.isBlank()) return@mapNotNull null
            ModelInfo(
                id = id,
                displayName = JsonX.str(obj, "name") ?: id,
                providerId = providerId,
                contextWindow = JsonX.int(obj, "context_length")
                    ?: JsonX.int(obj, "context_window")
                    ?: JsonX.int(obj, "max_context_length"),
                capabilities = ModelHeuristics.capabilitiesFor(id),
                source = ModelSource.DISCOVERED,
            )
        }.sortedBy { it.id }
    }

    override fun fallbackModels(config: ProviderConfig): List<ModelInfo> =
        com.nexus.aichat.core.model.ProviderPresets.byId(config.presetId ?: "")
            ?.suggestedModels
            ?.map { preset ->
                ModelInfo(
                    id = preset.id,
                    displayName = preset.displayName,
                    providerId = config.id,
                    contextWindow = preset.contextWindow,
                    capabilities = preset.capabilities,
                    source = ModelSource.PRESET,
                )
            }
            .orEmpty()
}

internal fun ProviderMessage.toolResultCallId(): String? =
    content.filterIsInstance<ProviderContentPart.ToolResultBlock>().firstOrNull()?.callId

internal fun ProviderRole.wireName(): String = when (this) {
    ProviderRole.SYSTEM -> "system"
    ProviderRole.USER -> "user"
    ProviderRole.ASSISTANT -> "assistant"
    ProviderRole.TOOL -> "tool"
}

/**
 * Query-param auth cannot be applied where the URL is built, because only the engine holds the
 * resolved secret. Adapters build the bare URL; the engine calls this before dispatching.
 */
fun ProviderConfig.authenticatedUrl(url: String, apiKey: String?): String =
    if (auth.scheme == AuthScheme.QUERY_PARAM && !apiKey.isNullOrBlank()) {
        appendQuery(url, auth.queryParamName?.takeIf { it.isNotBlank() } ?: "key", apiKey)
    } else {
        url
    }

internal fun appendQuery(url: String, name: String, value: String): String {
    if (value.isBlank()) return url
    val separator = if (url.contains('?')) '&' else '?'
    return "$url$separator$name=$value"
}

/**
 * Name-based capability inference for discovered models.
 *
 * `/models` reports an id and nothing else on most providers, so the ping test and the UI gates
 * (image attach button, thinking tree, tool plugs) need a best-effort guess. Anything wrong here is
 * recoverable: the user can override capabilities per model in Settings.
 */
object ModelHeuristics {

    private val visionTokens = listOf("vision", "gpt-4o", "gpt-4.1", "gpt-5", "o3", "o4", "claude", "gemini", "llava", "qwen-vl", "qwen2-vl", "qwen3-vl", "pixtral", "internvl", "molmo", "phi-4-multimodal", "grok")
    private val reasoningTokens = listOf("reason", "r1", "o1", "o3", "o4", "thinking", "magistral", "qwq", "deepseek-r", "gpt-5", "gemini-3", "-pro")
    private val toolTokens = listOf("gpt", "claude", "gemini", "mistral", "qwen", "llama-3", "llama-4", "deepseek", "command", "grok", "glm", "kimi", "minimax", "hermes", "functionary", "granite")
    private val embedTokens = listOf("embed", "embedding", "rerank", "bge-", "e5-", "text-embedding")

    fun capabilitiesFor(modelId: String): Set<ModelCapability> {
        val id = modelId.lowercase()
        if (embedTokens.any { id.contains(it) }) {
            return setOf(ModelCapability.TEXT)
        }
        val caps = mutableSetOf(ModelCapability.TEXT, ModelCapability.STREAMING)
        if (visionTokens.any { id.contains(it) }) caps += ModelCapability.VISION
        if (reasoningTokens.any { id.contains(it) }) caps += ModelCapability.REASONING
        if (toolTokens.any { id.contains(it) }) caps += ModelCapability.TOOL_CALLING
        if (id.contains("128k") || id.contains("1m") || id.contains("2m") || id.contains("200k") || id.contains("400k")) {
            caps += ModelCapability.LONG_CONTEXT
        }
        // Audio input is rare enough that we only trust explicit naming.
        if (id.contains("audio") || id.contains("realtime") || id.contains("-omni")) caps += ModelCapability.AUDIO_IN
        return caps
    }
}
