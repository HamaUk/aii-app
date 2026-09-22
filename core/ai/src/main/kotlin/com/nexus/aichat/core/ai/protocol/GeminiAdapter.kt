package com.nexus.aichat.core.ai.protocol

import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ModelCapability
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.core.model.ReasoningEffort
import com.nexus.aichat.core.model.TokenUsage
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Native Google Gemini (`generateContent` / `streamGenerateContent`).
 *
 * Protocol differences that matter:
 *  - the model lives in the *path*, not the body: `/models/{model}:streamGenerateContent?alt=sse`.
 *  - roles are `user` / `model`; there is no system role - instructions go in `systemInstruction`.
 *  - tool calls arrive as complete `functionCall` parts, not incremental fragments.
 *  - thinking tokens come back as parts flagged `thought: true` and are billed as output.
 *  - usage is reported as `usageMetadata` (prompt / candidates / thoughts).
 */
class GeminiAdapter : ProviderAdapter {

    override val protocol: ProviderProtocol = ProviderProtocol.GOOGLE_GEMINI

    override fun chatUrl(config: ProviderConfig, modelId: String, streaming: Boolean): String {
        val cleanModel = modelId.removePrefix("models/").trim()
        val defaultPath = if (streaming) {
            "/models/$cleanModel:streamGenerateContent"
        } else {
            "/models/$cleanModel:generateContent"
        }
        val url = ProviderConfig.joinUrl(config.baseUrl, config.chatPathOverride ?: defaultPath)
        // `alt=sse` is mandatory: without it the endpoint returns a chunked JSON array, not SSE.
        return if (streaming) appendQuery(url, "alt", "sse") else url
    }

    override fun headers(config: ProviderConfig, apiKey: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        config.extraHeaders.forEach { (k, v) -> put(k, v) }
        apiKey?.takeIf { it.isNotBlank() }?.let {
            when (config.auth.scheme) {
                AuthScheme.QUERY_PARAM -> Unit   // applied by the engine via authenticatedUrl()
                AuthScheme.BEARER -> put("Authorization", "Bearer $it")
                AuthScheme.CUSTOM_HEADER -> put(config.auth.headerName?.takeIf { n -> n.isNotBlank() } ?: "x-goog-api-key", it)
                else -> put("x-goog-api-key", it)
            }
        }
    }

    override fun encodeChat(request: ProviderChatRequest, config: ProviderConfig): String = buildJsonObject {
        request.system?.takeIf { it.isNotBlank() }?.let { system ->
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", JsonPrimitive(system)) } }
            }
        }
        putJsonArray("contents") {
            request.messages.forEach { message -> add(encodeMessage(message)) }
        }
        if (request.tools.isNotEmpty() && config.supportsNativeTools) {
            putJsonArray("tools") {
                addJsonObject {
                    putJsonArray("functionDeclarations") {
                        request.tools.forEach { spec ->
                            addJsonObject {
                                put("name", JsonPrimitive(spec.name))
                                put("description", JsonPrimitive(spec.description))
                                put("parameters", NexusJson.instance.parseToJsonElement(spec.parametersJsonSchema))
                            }
                        }
                    }
                }
            }
            putJsonObject("toolConfig") {
                putJsonObject("functionCallingConfig") {
                    when (val choice = request.toolChoice) {
                        ToolChoice.None -> put("mode", JsonPrimitive("NONE"))
                        ToolChoice.Required -> put("mode", JsonPrimitive("ANY"))
                        ToolChoice.Auto -> put("mode", JsonPrimitive("AUTO"))
                        is ToolChoice.Specific -> {
                            put("mode", JsonPrimitive("ANY"))
                            putJsonArray("allowedFunctionNames") { add(JsonPrimitive(choice.toolName)) }
                        }
                    }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", JsonPrimitive(request.sampling.temperature.coerceIn(0.0, 2.0)))
            if (request.sampling.topP in 0.0..1.0 && request.sampling.topP != 1.0) {
                put("topP", JsonPrimitive(request.sampling.topP))
            }
            request.sampling.maxOutputTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
            if (request.sampling.stopSequences.isNotEmpty()) {
                putJsonArray("stopSequences") { request.sampling.stopSequences.forEach { add(JsonPrimitive(it)) } }
            }
            // Gemini 2.5 takes a token budget; Gemini 3 takes a discrete thinking level.
            val budget = request.sampling.reasoningBudgetTokens
            val effort = request.sampling.reasoningEffort
            if (budget != null || effort != null) {
                putJsonObject("thinkingConfig") {
                    put("includeThoughts", JsonPrimitive(true))
                    when {
                        budget != null -> put("thinkingBudget", JsonPrimitive(budget))
                        effort != null -> put("thinkingLevel", JsonPrimitive(effort.toGeminiLevel()))
                    }
                }
            }
        }
    }.toString()

    private fun ReasoningEffort.toGeminiLevel(): String =
        if (this == ReasoningEffort.MINIMAL || this == ReasoningEffort.LOW) "low" else "high"

    private fun encodeMessage(message: ProviderMessage) = buildJsonObject {
        val toolResults = message.content.filterIsInstance<ProviderContentPart.ToolResultBlock>()
        if (toolResults.isNotEmpty()) {
            put("role", JsonPrimitive("user"))
            putJsonArray("parts") {
                toolResults.forEach { result ->
                    // Gemini correlates a functionResponse by *name*, not by id, so the name has to
                    // survive the round-trip. `decodeStreamEvent` encodes it into the callId as
                    // "<id>:<name>"; `message.name` (set by the harness) is the fallback when it did not.
                    val name = result.callId.substringAfterLast(':', missingDelimiterValue = "")
                        .ifBlank { message.name.orEmpty() }
                        .ifBlank { "tool" }
                    addJsonObject {
                        putJsonObject("functionResponse") {
                            put("name", JsonPrimitive(name))
                            putJsonObject("response") { put("content", JsonPrimitive(result.content)) }
                        }
                    }
                }
            }
            return@buildJsonObject
        }

        put("role", JsonPrimitive(if (message.role == ProviderRole.ASSISTANT) "model" else "user"))
        putJsonArray("parts") {
            message.content.forEach { part ->
                when (part) {
                    is ProviderContentPart.Text -> if (part.text.isNotBlank()) addJsonObject {
                        put("text", JsonPrimitive(part.text))
                    }
                    is ProviderContentPart.ImageBase64 -> addJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", JsonPrimitive(part.mimeType))
                            put("data", JsonPrimitive(part.base64))
                        }
                    }
                    is ProviderContentPart.ToolResultBlock -> Unit
                }
            }
            message.toolCalls.forEach { call ->
                addJsonObject {
                    putJsonObject("functionCall") {
                        put("name", JsonPrimitive(call.toolName))
                        put("args", runCatching { NexusJson.instance.parseToJsonElement(call.argumentsJson) }
                            .getOrElse { buildJsonObject { } })
                    }
                }
            }
        }
    }

    override fun decodeStreamEvent(eventName: String?, data: String): ProviderStreamChunk? {
        if (data.isBlank()) return null
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(data) }.getOrNull()) ?: return null

        // Gemini reports blocks and safety errors inside a normal 200 response body.
        JsonX.obj(root["promptFeedback"])?.let { feedback ->
            if (JsonX.str(feedback, "blockReason") != null) {
                return ProviderStreamChunk(
                    finishReason = "blocked",
                    errorMessage = "Blocked by safety filters: ${JsonX.str(feedback, "blockReason")}",
                )
            }
        }

        val candidate = JsonX.array(root["candidates"])?.firstOrNull()?.let { JsonX.obj(it) }
        val parts = JsonX.array(JsonX.obj(candidate?.get("content"))?.get("parts")).orEmpty()

        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolDeltas = mutableListOf<ProviderToolCallDelta>()

        parts.forEachIndexed { index, element ->
            val part = JsonX.obj(element) ?: return@forEachIndexed
            val functionCall = JsonX.obj(part["functionCall"])
            when {
                functionCall != null -> toolDeltas += ProviderToolCallDelta(
                    index = index,
                    id = JsonX.str(part, "id") ?: "gemini_${JsonX.str(functionCall, "name")}_$index",
                    name = JsonX.str(functionCall, "name"),
                    // Gemini sends the whole args object at once - replace, never append.
                    argumentsFragment = functionCall["args"]?.toString() ?: "{}",
                    replaceArgs = true,
                )
                JsonX.bool(part, "thought") == true -> reasoning.append(JsonX.str(part, "text").orEmpty())
                else -> text.append(JsonX.str(part, "text").orEmpty())
            }
        }

        val usageMetadata = JsonX.obj(root["usageMetadata"])
        return ProviderStreamChunk(
            textDelta = text.toString().takeIf { it.isNotEmpty() },
            reasoningDelta = reasoning.toString().takeIf { it.isNotEmpty() },
            toolCallDeltas = toolDeltas,
            usage = usageMetadata?.let {
                val prompt = JsonX.int(it, "promptTokenCount") ?: 0
                val candidates = JsonX.int(it, "candidatesTokenCount") ?: 0
                val thoughts = JsonX.int(it, "thoughtsTokenCount") ?: 0
                TokenUsage(
                    inputTokens = prompt,
                    outputTokens = candidates + thoughts,
                    reasoningTokens = thoughts,
                    cachedInputTokens = JsonX.int(it, "cachedContentTokenCount") ?: 0,
                    totalTokens = JsonX.int(it, "totalTokenCount") ?: (prompt + candidates + thoughts),
                    providerReported = true,
                )
            },
            finishReason = JsonX.str(candidate, "finishReason")?.lowercase(),
            modelId = JsonX.str(root, "modelVersion"),
            providerId = JsonX.str(root, "responseId"),
        ).takeUnless { it.isEmpty }
    }

    override fun decodeChatResponse(body: String, config: ProviderConfig?): ProviderChatResponse {
        val chunk = decodeStreamEvent(null, body)
        return ProviderChatResponse(
            text = chunk?.textDelta.orEmpty(),
            reasoning = chunk?.reasoningDelta.orEmpty(),
            toolCalls = chunk?.toolCallDeltas.orEmpty().map { delta ->
                com.nexus.aichat.core.model.ToolCallRequest(
                    callId = delta.id ?: "gemini_call_${delta.index}",
                    toolName = delta.name.orEmpty(),
                    argumentsJson = delta.argumentsFragment ?: "{}",
                )
            },
            usage = chunk?.usage,
            finishReason = chunk?.finishReason,
            modelId = chunk?.modelId,
        )
    }

    override fun decodeError(status: Int, body: String, providerId: String?): AppError? {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return null
        val error = JsonX.obj(root["error"]) ?: return null
        val message = JsonX.str(error, "message") ?: return null
        val code = JsonX.int(error, "code")
        return when {
            status == 401 || status == 403 || code == 401 || code == 403 -> AppError.Auth(message, providerId = providerId)
            status == 429 || code == 429 -> AppError.RateLimited(message)
            else -> AppError.Provider(message, httpStatus = status, errorCode = JsonX.str(error, "status"), rawBody = body.take(2_000))
        }
    }

    override fun decodeModels(body: String, providerId: String): List<ModelInfo> {
        val root = JsonX.obj(runCatching { NexusJson.instance.parseToJsonElement(body) }.getOrNull()) ?: return emptyList()
        val array = JsonX.array(JsonX.obj(root)?.get("models")) ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = JsonX.obj(element) ?: return@mapNotNull null
            val rawName = JsonX.str(obj, "name") ?: return@mapNotNull null
            val id = rawName.removePrefix("models/")
            val methods = JsonX.array(obj["supportedGenerationMethods"])
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                .orEmpty()
            if (methods.isNotEmpty() && "generateContent" !in methods) return@mapNotNull null
            ModelInfo(
                id = id,
                displayName = JsonX.str(obj, "displayName") ?: id,
                providerId = providerId,
                contextWindow = JsonX.int(obj, "inputTokenLimit"),
                maxOutputTokens = JsonX.int(obj, "outputTokenLimit"),
                capabilities = ModelHeuristics.capabilitiesFor(id) +
                    setOfNotNull(ModelCapability.VISION.takeIf { id.contains("gemini") }),
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
}
