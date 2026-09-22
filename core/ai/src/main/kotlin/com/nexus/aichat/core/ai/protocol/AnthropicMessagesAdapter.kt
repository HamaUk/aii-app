package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.TokenUsage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `POST {base}/messages` - the native Anthropic Messages API with typed SSE events.
 *
 * Protocol differences that matter:
 *  - `system` is a top-level parameter, never a message.
 *  - `max_tokens` is **required**; we fall back to 8192 when the user has not set one.
 *  - every request must carry `anthropic-version`; it is injected from the preset's default headers.
 *  - tool *results* are `user` turns containing `tool_result` blocks - not a `tool` role as in OpenAI.
 *  - extended thinking arrives as `thinking_delta` fragments and pins temperature to 1.0.
 */
class AnthropicMessagesAdapter : ProviderAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.ANTHROPIC_MESSAGES

    override fun headers(config: ProviderConfig, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("anthropic-version", config.extraHeaders["anthropic-version"] ?: DEFAULT_VERSION)
        config.extraHeaders.forEach { (k, v) -> if (k != "anthropic-version") put(k, v) }
        apiKey?.takeIf { it.isNotBlank() }?.let {
            when (config.auth.scheme) {
                AuthScheme.BEARER -> put("Authorization", "Bearer $it")
                AuthScheme.CUSTOM_HEADER -> put(config.auth.headerName?.takeIf { n -> n.isNotBlank() } ?: "x-api-key", it)
                else -> put("x-api-key", it)
            }
        }
    }

    override fun chatUrl(config: ProviderConfig, modelId: String, streaming: Boolean): String =
        ProviderConfig.joinUrl(config.baseUrl, config.chatPathOverride ?: "/messages")

    override fun encodeChat(request: ProviderChatRequest, config: ProviderConfig): String {
        val thinkingEnabled = request.sampling.reasoningBudgetTokens?.let { it >= MIN_THINKING_BUDGET } == true
        val maxTokens = request.sampling.maxOutputTokens
            ?: request.sampling.reasoningBudgetTokens?.plus(DEFAULT_MAX_TOKENS)?.coerceAtMost(64_000)
            ?: DEFAULT_MAX_TOKENS

        val body = buildJsonObject {
            put("model", JsonPrimitive(request.model))
            put("max_tokens", JsonPrimitive(maxTokens.coerceAtLeast(if (thinkingEnabled) MIN_THINKING_BUDGET + 1 else 1)))
            put("stream", JsonPrimitive(request.streaming))

            request.system?.takeIf { it.isNotBlank() }?.let { put("system", JsonPrimitive(it)) }

            putJsonArray("messages") {
                request.messages.forEach { message -> add(encodeMessage(message)) }
            }

            if (thinkingEnabled) {
                // Anthropic requires temperature == 1 and no top_p when thinking is on.
                putJsonObject("thinking") {
                    put("type", JsonPrimitive("enabled"))
                    put("budget_tokens", JsonPrimitive(request.sampling.reasoningBudgetTokens!!))
                }
                put("temperature", JsonPrimitive(1.0))
            } else {
                put("temperature", JsonPrimitive(request.sampling.temperature.coerceIn(0.0, 1.0)))
                if (request.sampling.topP in 0.0..1.0 && request.sampling.topP != 1.0) {
                    put("top_p", JsonPrimitive(request.sampling.topP))
                }
            }

            if (request.sampling.stopSequences.isNotEmpty()) {
                putJsonArray("stop_sequences") { request.sampling.stopSequences.forEach { add(JsonPrimitive(it)) } }
            }

            if (request.tools.isNotEmpty() && config.supportsNativeTools) {
                putJsonArray("tools") {
                    request.tools.forEach { spec ->
                        addJsonObject {
                            put("name", JsonPrimitive(spec.name))
                            put("description", JsonPrimitive(spec.description))
                            put("input_schema", NexusJson.instance.parseToJsonElement(spec.parametersJsonSchema))
                        }
                    }
                }
                put("tool_choice", encodeToolChoice(request.toolChoice))
            }
        }
        return body.toString()
    }

    private fun encodeToolChoice(choice: ToolChoice) = when (choice) {
        ToolChoice.Auto -> buildJsonObject { put("type", JsonPrimitive("auto")) }
        ToolChoice.None -> buildJsonObject { put("type", JsonPrimitive("none")) }
        ToolChoice.Required -> buildJsonObject { put("type", JsonPrimitive("any")) }
        is ToolChoice.Specific -> buildJsonObject {
            put("type", JsonPrimitive("tool"))
            put("name", JsonPrimitive(choice.toolName))
        }
    }

    private fun encodeMessage(message: ProviderMessage) = buildJsonObject {
        // Tool observations are user turns carrying tool_result blocks.
        val toolResults = message.content.filterIsInstance<ProviderContentPart.ToolResultBlock>()
        if (toolResults.isNotEmpty()) {
            put("role", JsonPrimitive("user"))
            putJsonArray("content") {
                toolResults.forEach { result ->
                    addJsonObject {
                        put("type", JsonPrimitive("tool_result"))
                        put("tool_use_id", JsonPrimitive(result.callId))
                        put("content", JsonPrimitive(result.content))
                        if (result.isError) put("is_error", JsonPrimitive(true))
                    }
                }
            }
            return@buildJsonObject
        }

        put("role", JsonPrimitive(if (message.role == ProviderRole.ASSISTANT) "assistant" else "user"))
        putJsonArray("content") {
            message.content.forEach { part ->
                when (part) {
                    is ProviderContentPart.Text -> if (part.text.isNotBlank()) addJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(part.text))
                    }
                    is ProviderContentPart.ImageBase64 -> addJsonObject {
                        put("type", JsonPrimitive("image"))
                        putJsonObject("source") {
                            put("type", JsonPrimitive("base64"))
                            put("media_type", JsonPrimitive(part.mimeType))
                            put("data", JsonPrimitive(part.base64))
                        }
                    }
                    is ProviderContentPart.ToolResultBlock -> Unit   // handled above
                }
            }
            message.toolCalls.forEach { call ->
                addJsonObject {
                    put("type", JsonPrimitive("tool_use"))
                    put("id", JsonPrimitive(call.callId))
                    put("name", JsonPrimitive(call.toolName))
                    put("input", runCatching { NexusJson.instance.parseToJsonElement(call.argumentsJson) }
                        .getOrElse { buildJsonObject { } })
                }
            }
        }
    }

    override fun decodeStreamEvent(eventName: String?, data: String): ProviderStreamChunk? {
        if (data.isBlank()) return null
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(data) }.getOrNull()) ?: return null
        val type = JsonX.str(root, "type") ?: eventName ?: return null

        return when (type) {
            "message_start" -> {
                val usage = JsonX.obj(JsonX.obj(root["message"])?.get("usage"))
                ProviderStreamChunk(
                    usage = TokenUsage(
                        inputTokens = JsonX.int(usage, "input_tokens") ?: 0,
                        outputTokens = JsonX.int(usage, "output_tokens") ?: 0,
                        providerReported = true,
                    ).let { it.copy(totalTokens = it.inputTokens + it.outputTokens) },
                    modelId = JsonX.str(JsonX.obj(root["message"]), "model"),
                    providerId = JsonX.str(JsonX.obj(root["message"]), "id"),
                )
            }

            "content_block_start" -> {
                val block = JsonX.obj(root["content_block"])
                val index = JsonX.int(root, "index") ?: 0
                when (JsonX.str(block, "type")) {
                    "tool_use" -> ProviderStreamChunk(
                        toolCallDeltas = listOf(
                            ProviderToolCallDelta(
                                index = index,
                                id = JsonX.str(block, "id"),
                                name = JsonX.str(block, "name"),
                                argumentsFragment = null,
                            ),
                        ),
                    )
                    else -> null
                }
            }

            "content_block_delta" -> {
                val delta = JsonX.obj(root["delta"]) ?: return null
                val index = JsonX.int(root, "index") ?: 0
                when (JsonX.str(delta, "type")) {
                    "text_delta" -> ProviderStreamChunk(textDelta = JsonX.str(delta, "text"))
                    "thinking_delta" -> ProviderStreamChunk(reasoningDelta = JsonX.str(delta, "thinking"))
                    "input_json_delta" -> ProviderStreamChunk(
                        toolCallDeltas = listOf(
                            ProviderToolCallDelta(index = index, argumentsFragment = JsonX.str(delta, "partial_json")),
                        ),
                    )
                    "signature_delta" -> null    // thinking-block signature: not user-visible
                    else -> null
                }
            }

            "message_delta" -> {
                val usage = JsonX.obj(root["usage"])
                ProviderStreamChunk(
                    usage = usage?.let {
                        TokenUsage(
                            outputTokens = JsonX.int(it, "output_tokens") ?: 0,
                            providerReported = true,
                        )
                    },
                    finishReason = JsonX.str(JsonX.obj(root["delta"]), "stop_reason"),
                )
            }

            "error" -> ProviderStreamChunk(
                finishReason = "error",
                errorMessage = JsonX.str(JsonX.obj(root["error"]), "message") ?: data,
            )

            "ping", "message_stop", "content_block_stop" -> null

            else -> null
        }
    }

    override fun decodeChatResponse(body: String, config: ProviderConfig?): ProviderChatResponse {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return ProviderChatResponse("")
        val blocks = JsonX.array(root["content"]) ?: return ProviderChatResponse("")
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<com.nexus.aichat.core.model.ToolCallRequest>()
        blocks.forEachIndexed { index, element ->
            val block = JsonX.obj(element) ?: return@forEachIndexed
            when (JsonX.str(block, "type")) {
                "text" -> text.append(JsonX.str(block, "text").orEmpty())
                "thinking" -> reasoning.append(JsonX.str(block, "thinking").orEmpty())
                "tool_use" -> calls += com.nexus.aichat.core.model.ToolCallRequest(
                    callId = JsonX.str(block, "id") ?: "toolu_$index",
                    toolName = JsonX.str(block, "name").orEmpty(),
                    argumentsJson = block["input"]?.toString() ?: "{}",
                )
            }
        }
        val usage = JsonX.obj(root["usage"])
        return ProviderChatResponse(
            text = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = calls,
            usage = usage?.let {
                val input = JsonX.int(it, "input_tokens") ?: 0
                val output = JsonX.int(it, "output_tokens") ?: 0
                TokenUsage(inputTokens = input, outputTokens = output, totalTokens = input + output, providerReported = true)
            },
            finishReason = JsonX.str(root, "stop_reason"),
            modelId = JsonX.str(root, "model"),
        )
    }

    override fun decodeError(status: Int, body: String, providerId: String?): AppError? {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return null
        val error = JsonX.obj(root["error"]) ?: JsonX.obj(root) ?: return null
        val message = JsonX.str(error, "message") ?: return null
        val type = JsonX.str(error, "type")
        return when {
            status == 401 || status == 403 -> AppError.Auth(message, providerId = providerId)
            status == 429 || type == "rate_limit_error" -> AppError.RateLimited(message)
            status == 529 || type == "overloaded_error" -> AppError.Provider("Anthropic is overloaded: $message", 529)
            else -> AppError.Provider(message, httpStatus = status, errorCode = type, rawBody = body.take(2_000))
        }
    }

    override fun decodeModels(body: String, providerId: String): List<ModelInfo> {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return emptyList()
        val array = JsonX.array(JsonX.obj(root)?.get("data")) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = JsonX.obj(element) ?: return@mapNotNull null
            val id = JsonX.str(obj, "id") ?: return@mapNotNull null
            ModelInfo(
                id = id,
                displayName = JsonX.str(obj, "display_name") ?: id,
                providerId = providerId,
                contextWindow = 200_000,
                maxOutputTokens = 64_000,
                capabilities = ModelHeuristics.capabilitiesFor(id),
                source = ModelSource.DISCOVERED,
            )
        }
    }

    override fun fallbackModels(config: ProviderConfig): List<ModelInfo> =
        com.nexus.aichat.core.model.ProviderPresets.byId(config.presetId ?: "")?.suggestedModels?.map { preset ->
            ModelInfo(
                id = preset.id,
                displayName = preset.displayName,
                providerId = config.id,
                contextWindow = preset.contextWindow,
                capabilities = preset.capabilities,
                source = ModelSource.PRESET,
            )
        }.orEmpty()

    companion object {
        const val DEFAULT_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 8_192
        const val MIN_THINKING_BUDGET = 1_024
    }
}
