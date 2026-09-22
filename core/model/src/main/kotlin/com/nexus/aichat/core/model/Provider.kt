package com.nexus.aichat.core.model

import kotlinx.serialization.Serializable

/**
 * Wire protocols Nexus can speak. Everything else (auth quirks, streaming shape, tool-call
 * encoding) is derived from this single value, which is what makes arbitrary custom endpoints
 * work without a per-vendor integration.
 */
@Serializable
enum class ProviderProtocol(val label: String, val description: String) {
    OPENAI_COMPATIBLE(
        label = "OpenAI-compatible",
        description = "POST {base}/chat/completions with SSE. Covers OpenAI, Groq, DeepSeek, Mistral, " +
            "OpenRouter, xAI, Together, Fireworks, vLLM, LM Studio, Ollama, llama.cpp and most proxies.",
    ),
    ANTHROPIC_MESSAGES(
        label = "Anthropic-compatible",
        description = "POST {base}/messages with x-api-key auth and typed SSE events. Anthropic, Bedrock " +
            "gateways, LiteLLM and Anthropic-shaped proxies.",
    ),
    GOOGLE_GEMINI(
        label = "Google Gemini",
        description = "Native generateContent / streamGenerateContent with x-goog-api-key and " +
            "thought-summary parts.",
    ),
    RAW_REST(
        label = "Raw REST (best effort)",
        description = "Generic POST with a hand-rolled body template and a lenient response parser. Use " +
            "for exotic or self-hosted endpoints that match nothing else.",
    ),
}

/** How a provider expects the credential to be presented. */
@Serializable
enum class AuthScheme {
    NONE,
    BEARER,          // Authorization: Bearer <key>
    X_API_KEY,       // x-api-key: <key>   (Anthropic)
    X_GOOG_API_KEY,  // x-goog-api-key: <key> (Gemini)
    QUERY_PARAM,     // ?key=<key>  (Gemini classic, some gateways)
    CUSTOM_HEADER,   // user-defined header name
}

/**
 * Credential *reference*, never the secret itself: configs are written to a database and to
 * exported backups, while the material stays in the Keystore-backed store under [vaultKey].
 */
@Serializable
data class AuthConfig(
    val scheme: AuthScheme,
    val vaultKey: String?,
    val headerName: String? = null,
    val queryParamName: String? = null,
    val extraStaticHeaders: Map<String, String> = emptyMap(),
) {
    val requiresSecret: Boolean get() = scheme != AuthScheme.NONE
}

/** Capability flags used to gate UI affordances (vision attach button, thinking tree, tool plugs). */
@Serializable
enum class ModelCapability {
    TEXT,
    VISION,
    AUDIO_IN,
    TOOL_CALLING,
    REASONING,
    JSON_MODE,
    STREAMING,
    LONG_CONTEXT,
}

@Serializable
data class ModelPricing(
    val inputPerMillionUsd: Double? = null,
    val outputPerMillionUsd: Double? = null,
) {
    fun estimateUsd(inputTokens: Int, outputTokens: Int): Double? {
        val i = inputPerMillionUsd ?: return null
        val o = outputPerMillionUsd ?: return null
        return (inputTokens / 1_000_000.0) * i + (outputTokens / 1_000_000.0) * o
    }
}

/**
 * A discoverable model. Produced by "Fetch models" (`GET {base}/models`) or by the preset catalog,
 * then enriched by the ping/latency probe and by static heuristics (capabilities/context window).
 */
@Serializable
data class ModelInfo(
    val id: String,
    val displayName: String = id,
    val providerId: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.TEXT, ModelCapability.STREAMING),
    val pricing: ModelPricing? = null,
    val source: ModelSource = ModelSource.DISCOVERED,
    val latencyMs: Long? = null,
    val lastCheckedEpochMs: Long? = null,
    val isAlive: Boolean = true,
) {
    val supportsVision: Boolean get() = ModelCapability.VISION in capabilities
    val supportsTools: Boolean get() = ModelCapability.TOOL_CALLING in capabilities
    val supportsReasoning: Boolean get() = ModelCapability.REASONING in capabilities
}

@Serializable
enum class ModelSource { PRESET, DISCOVERED, MANUAL }

/**
 * A configured endpoint: either instantiated from a [ProviderPreset] or built from scratch by the
 * user. Custom endpoints are first-class - the same struct powers a hosted vendor and an Ollama
 * box on the LAN.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val auth: AuthConfig = AuthConfig(AuthScheme.NONE, vaultKey = null),
    val extraHeaders: Map<String, String> = emptyMap(),
    val modelsPath: String = "/models",
    val chatPathOverride: String? = null,
    val requestTimeoutMs: Long = 120_000,
    val connectTimeoutMs: Long = 20_000,
    val supportsNativeTools: Boolean = true,
    /** Some self-hosted servers 400 on `stream_options.include_usage`; presets disable it there. */
    val includeStreamUsage: Boolean = true,
    /** Raw-REST only: body template with {{model}} {{system}} {{messages}} {{prompt}} {{stream}} placeholders. */
    val requestBodyTemplate: String? = null,
    /** Advanced override: dot-path into a non-standard response envelope, e.g. `choices.0.delta.content`. */
    val responseTextPath: String? = null,
    val presetId: String? = null,
    val isEnabled: Boolean = true,
    val models: List<ModelInfo> = emptyList(),
    val selectedModelId: String? = null,
    val createdAtEpochMs: Long = 0,
    val lastUsedEpochMs: Long? = null,
    val isBuiltInPreset: Boolean = false,
    val notes: String? = null,
) {
    /** Full URL for a non-streaming or streaming chat call. */
    fun chatUrl(): String = joinUrl(baseUrl, chatPathOverride ?: protocol.defaultChatPath())

    /** Full URL for model discovery. */
    fun modelsUrl(): String = joinUrl(baseUrl, modelsPath)

    val selectedModel: ModelInfo? get() = models.firstOrNull { it.id == selectedModelId }

    companion object {
        fun joinUrl(base: String, path: String): String {
            val b = base.trim().trimEnd('/')
            val p = path.trim().removePrefix("/")
            return if (p.isEmpty()) b else "$b/$p"
        }
    }
}

fun ProviderProtocol.defaultChatPath(): String = when (this) {
    ProviderProtocol.OPENAI_COMPATIBLE -> "/chat/completions"
    ProviderProtocol.ANTHROPIC_MESSAGES -> "/messages"
    ProviderProtocol.GOOGLE_GEMINI -> "/models/{model}:streamGenerateContent"
    ProviderProtocol.RAW_REST -> "/chat/completions"
}

/**
 * A pre-configured provider template. The "add a provider" flow needs nothing but an API key:
 * everything else here is prefilled and can still be overridden per instance.
 */
@Serializable
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val baseUrl: String,
    val authScheme: AuthScheme,
    val docsUrl: String,
    val keyHint: String,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val suggestedModels: List<PresetModel> = emptyList(),
    val isLocal: Boolean = false,
    val includeStreamUsage: Boolean = true,
    val tagline: String = "",
)

@Serializable
data class PresetModel(
    val id: String,
    val displayName: String,
    val contextWindow: Int? = null,
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.TEXT, ModelCapability.STREAMING),
)

/**
 * The shipped catalog. Keyed by preset id; instances reference the preset for icons/copy only.
 * `isLocal` presets get their base URLs pre-rewritten for the emulator loopback
 * (10.0.2.2) by the UI layer where relevant.
 */
object ProviderPresets {

    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "openai",
            displayName = "OpenAI",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.openai.com/v1",
            authScheme = AuthScheme.BEARER,
            docsUrl = "https://platform.openai.com/api-keys",
            keyHint = "sk-...",
            tagline = "GPT family, o-series reasoning, vision",
            suggestedModels = listOf(
                PresetModel("gpt-5.2", "GPT-5.2", 400_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.REASONING)),
                PresetModel("gpt-5.2-mini", "GPT-5.2 mini", 400_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION)),
                PresetModel("gpt-4.1", "GPT-4.1", 1_000_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.LONG_CONTEXT)),
            ),
        ),
        ProviderPreset(
            id = "anthropic",
            displayName = "Anthropic Claude",
            protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
            baseUrl = "https://api.anthropic.com/v1",
            authScheme = AuthScheme.X_API_KEY,
            docsUrl = "https://console.anthropic.com/settings/keys",
            keyHint = "sk-ant-...",
            defaultHeaders = mapOf("anthropic-version" to "2023-06-01"),
            tagline = "Extended thinking, tool use, vision",
            suggestedModels = listOf(
                PresetModel("claude-sonnet-4-6", "Claude Sonnet 4.6", 1_000_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.REASONING, ModelCapability.LONG_CONTEXT)),
                PresetModel("claude-opus-4-6", "Claude Opus 4.6", 500_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.REASONING)),
                PresetModel("claude-haiku-4-5", "Claude Haiku 4.5", 200_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING)),
            ),
        ),
        ProviderPreset(
            id = "google",
            displayName = "Google Gemini",
            protocol = ProviderProtocol.GOOGLE_GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            authScheme = AuthScheme.X_GOOG_API_KEY,
            docsUrl = "https://aistudio.google.com/app/apikey",
            keyHint = "AIza...",
            tagline = "Native thinking summaries, huge context",
            suggestedModels = listOf(
                PresetModel("gemini-3-pro", "Gemini 3 Pro", 1_000_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.REASONING, ModelCapability.LONG_CONTEXT)),
                PresetModel("gemini-3-flash", "Gemini 3 Flash", 1_000_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION)),
                PresetModel("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", 1_000_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.VISION)),
            ),
        ),
        ProviderPreset(
            id = "deepseek",
            displayName = "DeepSeek",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.deepseek.com/v1",
            authScheme = AuthScheme.BEARER,
            docsUrl = "https://platform.deepseek.com/api_keys",
            keyHint = "sk-...",
            tagline = "R-series reasoning traces, strong coding",
            suggestedModels = listOf(
                PresetModel("deepseek-reasoner", "DeepSeek Reasoner", 128_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.REASONING, ModelCapability.TOOL_CALLING)),
                PresetModel("deepseek-chat", "DeepSeek Chat", 128_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING)),
            ),
        ),
        ProviderPreset(
            id = "groq",
            displayName = "Groq",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.groq.com/openai/v1",
            authScheme = AuthScheme.BEARER,
            docsUrl = "https://console.groq.com/keys",
            keyHint = "gsk_...",
            tagline = "LPU inference - instant tokens",
            suggestedModels = listOf(
                PresetModel("llama-3.3-70b-versatile", "Llama 3.3 70B", 128_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING)),
                PresetModel("openai/gpt-oss-120b", "GPT-OSS 120B", 131_072, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.REASONING)),
            ),
        ),
        ProviderPreset(
            id = "openrouter",
            displayName = "OpenRouter",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://openrouter.ai/api/v1",
            authScheme = AuthScheme.BEARER,
            docsUrl = "https://openrouter.ai/keys",
            keyHint = "sk-or-...",
            defaultHeaders = mapOf("X-Title" to "Nexus Agent"),
            tagline = "300+ models behind one key",
            suggestedModels = listOf(
                PresetModel("@preset/free", "Free models router", 128_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING)),
                PresetModel("anthropic/claude-sonnet-4.6", "Claude Sonnet 4.6", 1_000_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.REASONING)),
            ),
        ),
        ProviderPreset(
            id = "mistral",
            displayName = "Mistral AI",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.mistral.ai/v1",
            authScheme = AuthScheme.BEARER,
            docsUrl = "https://console.mistral.ai/api-keys",
            keyHint = "...",
            tagline = "EU-hosted, strong function calling",
            suggestedModels = listOf(
                PresetModel("mistral-large-latest", "Mistral Large", 131_072, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING)),
                PresetModel("magistral-medium-latest", "Magistral Medium", 131_072, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.REASONING, ModelCapability.TOOL_CALLING)),
            ),
        ),
        ProviderPreset(
            id = "xai",
            displayName = "xAI Grok",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "https://api.x.ai/v1",
            authScheme = AuthScheme.BEARER,
            docsUrl = "https://console.x.ai",
            keyHint = "xai-...",
            tagline = "Grok family, live search variants",
            suggestedModels = listOf(
                PresetModel("grok-4.1", "Grok 4.1", 256_000, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING, ModelCapability.VISION, ModelCapability.REASONING)),
            ),
        ),
        ProviderPreset(
            id = "ollama",
            displayName = "Ollama (local)",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:11434/v1",
            authScheme = AuthScheme.NONE,
            docsUrl = "https://ollama.com/download",
            keyHint = "(no key needed)",
            isLocal = true,
            includeStreamUsage = false,
            tagline = "Fully offline. Use 10.0.2.2 on the emulator.",
            suggestedModels = listOf(
                PresetModel("qwen3:8b", "Qwen3 8B", 40_960, setOf(ModelCapability.TEXT, ModelCapability.STREAMING, ModelCapability.TOOL_CALLING)),
            ),
        ),
        ProviderPreset(
            id = "lmstudio",
            displayName = "LM Studio (local)",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "http://localhost:1234/v1",
            authScheme = AuthScheme.NONE,
            docsUrl = "https://lmstudio.ai/docs/local-server",
            keyHint = "(no key needed)",
            isLocal = true,
            includeStreamUsage = false,
            tagline = "Desktop-served GGUF/MLX models on your LAN",
        ),
        ProviderPreset(
            id = "custom",
            displayName = "Custom endpoint",
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            baseUrl = "",
            authScheme = AuthScheme.CUSTOM_HEADER,
            docsUrl = "",
            keyHint = "any token / header value",
            tagline = "vLLM, llama.cpp, LiteLLM, a private proxy - you name it",
        ),
    )

    fun byId(id: String): ProviderPreset? = ALL.firstOrNull { it.id == id }
}
