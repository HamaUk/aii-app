package com.nexus.aichat.data.remote.streaming

import com.nexus.aichat.core.ai.engine.ChatCall
import com.nexus.aichat.core.ai.engine.ChatCompletionClient
import com.nexus.aichat.core.ai.engine.StreamEvent
import com.nexus.aichat.core.ai.protocol.ProviderChatRequest
import com.nexus.aichat.core.ai.protocol.ProviderChatResponse
import com.nexus.aichat.core.ai.protocol.ProviderContentPart
import com.nexus.aichat.core.ai.protocol.ProviderMessage
import com.nexus.aichat.core.ai.protocol.ProviderRole
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.common.result.map
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.SamplingOptions
import com.nexus.aichat.core.util.Constants
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider calls that are not agent runs.
 *
 * Three places in the app need to talk to a model without starting a ReAct loop, and each has a good
 * reason: conversation titles (must be cheap and silent), branch summaries (must not appear in the
 * transcript), and the "test this endpoint" button (must fail loudly and immediately). Routing them
 * through the orchestrator would create phantom runs with tools, retries and persisted messages.
 *
 * Everything still goes through [ChatCompletionClient], so protocol handling, streaming, retries and
 * usage accounting are identical to the agent path - only the policy differs.
 */
@Singleton
class ProviderStreamAdapter @Inject constructor(
    private val engine: ChatCompletionClient,
    private val secrets: SecretProvider,
) {

    /** Streams a single completion with no tools and no loop. */
    fun stream(
        config: ProviderConfig,
        model: ModelInfo,
        system: String?,
        userText: String,
        sampling: SamplingOptions = SamplingOptions(temperature = 0.4, maxOutputTokens = 1_024),
    ): Flow<StreamEvent> = engine.stream(
        ChatCall(
            config = config,
            apiKey = null,                       // resolved by the engine per call, never held here
            request = request(config, model, system, userText, sampling, streaming = true),
            maxRetries = 1,
        ),
    )

    /** Buffered single completion. The right shape for titles and summaries: short, disposable. */
    suspend fun complete(
        config: ProviderConfig,
        model: ModelInfo,
        system: String?,
        userText: String,
        sampling: SamplingOptions = SamplingOptions(temperature = 0.3, maxOutputTokens = 512),
    ): NexusResult<ProviderChatResponse> {
        val key = secrets.apiKeyFor(config)
        if (config.auth.requiresSecret && key.isNullOrBlank()) {
            // Fail before the socket opens: "no key stored" is a settings problem, not a network one,
            // and the caller can route the user straight to the vault field.
            return NexusResult.Failure(
                AppError.Auth("No API key stored for ${config.displayName}", providerId = config.id),
            )
        }
        return engine.complete(
            ChatCall(
                config = config,
                apiKey = key,
                request = request(config, model, system, userText, sampling, streaming = false),
                maxRetries = 1,
            ),
        )
    }

    /**
     * Names a conversation. Deliberately: no tools, temperature low, tiny budget, and a hard cap on
     * the reply - a title generator that can run away is a title generator that costs money.
     */
    suspend fun suggestTitle(
        config: ProviderConfig,
        model: ModelInfo,
        firstUserMessage: String,
        assistantReply: String,
    ): NexusResult<String> {
        val system = "You write conversation titles. Reply with a title of at most six words: " +
            "no quotes, no trailing punctuation, no preamble. Match the language of the user's message."
        val prompt = "User: ${firstUserMessage.take(1_500)}\n\nAssistant: ${assistantReply.take(1_500)}"
        return complete(
            config = config,
            model = model,
            system = system,
            userText = prompt,
            sampling = SamplingOptions(temperature = 0.2, maxOutputTokens = 32, stopSequences = listOf("\n")),
        ).map { response -> response.text.trim().trim('"').take(60) }   // :core:common map()
    }

    private fun request(
        config: ProviderConfig,
        model: ModelInfo,
        system: String?,
        userText: String,
        sampling: SamplingOptions,
        streaming: Boolean,
    ) = ProviderChatRequest(
        model = model.id,
        system = system,
        messages = listOf(
            ProviderMessage(role = ProviderRole.USER, content = listOf(ProviderContentPart.Text(userText))),
        ),
        tools = emptyList(),
        sampling = sampling.copy(
            maxOutputTokens = sampling.maxOutputTokens
                ?: Constants.Limits.TOOL_OUTPUT_MAX_CHARS.coerceAtMost(1_024),
        ),
        streaming = streaming,
    )
}
