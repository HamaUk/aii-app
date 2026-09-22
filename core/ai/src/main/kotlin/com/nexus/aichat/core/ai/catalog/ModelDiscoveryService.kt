package com.nexus.aichat.core.ai.catalog

import com.nexus.aichat.core.ai.protocol.AdapterRegistry
import com.nexus.aichat.core.ai.protocol.authenticatedUrl
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.ai.transport.HttpRequestSpec
import com.nexus.aichat.core.ai.transport.HttpStatusException
import com.nexus.aichat.core.ai.transport.HttpVerb
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.model.ProviderConfig
import kotlinx.coroutines.CancellationException

/**
 * "Fetch models" - live discovery against the provider's own catalogue.
 *
 * Works for all four protocols because the adapter owns the shape of the response:
 * OpenAI-compatible and Anthropic return `{data:[{id}]}`, Gemini returns
 * `{models:[{name, inputTokenLimit, supportedGenerationMethods}]}`, Raw REST tries both.
 *
 * Degradation ladder, so a user is never left with an empty model list:
 *   live `/models`  ->  preset catalogue  ->  the models already stored on the config.
 */
class ModelDiscoveryService(
    private val transport: ChatTransport,
    private val adapters: AdapterRegistry,
    private val secrets: SecretProvider,
    private val clock: TimeProvider,
    private val logger: NexusLogger,
) {

    suspend fun fetchModels(config: ProviderConfig): NexusResult<List<ModelInfo>> {
        val adapter = adapters.forConfig(config)
        val apiKey = runCatching { secrets.apiKeyFor(config) }.getOrNull()
        val url = config.authenticatedUrl(adapter.modelsUrl(config), apiKey)

        val spec = HttpRequestSpec(
            verb = HttpVerb.GET,
            url = url,
            headers = adapter.headers(config, apiKey),
            label = "${config.protocol.name.lowercase()}.models",
        )

        return try {
            val response = transport.execute(spec, connectTimeoutMs = 15_000, readTimeoutMs = 30_000)
            if (!response.isSuccess) {
                val decoded = adapter.decodeError(response.status, response.body, config.id)
                val error = HttpStatusException(response.status, response.body, response.headers["retry-after"])
                    .toAppError(decoded)
                logger.w(TAG, "discovery failed for ${config.id}: ${error.displayMessage}")
                // A provider that has no /models endpoint is common (some proxies). Fall back, don't fail.
                val fallback = fallbacks(config, adapter)
                if (fallback.isNotEmpty()) NexusResult.Success(fallback) else NexusResult.Failure(error)
            } else {
                val discovered = adapter.decodeModels(response.body, config.id)
                val models = if (discovered.isNotEmpty()) {
                    discovered.map { enrich(it, config) }
                } else {
                    fallbacks(config, adapter)
                }
                if (models.isEmpty()) {
                    NexusResult.Failure(
                        AppError.Provider(
                            "The provider returned no models. Check the base URL, or add a model id manually.",
                            httpStatus = response.status,
                        ),
                    )
                } else {
                    NexusResult.Success(models)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            val fallback = fallbacks(config, adapter)
            if (fallback.isNotEmpty()) NexusResult.Success(fallback) else NexusResult.Failure(AppError.from(t))
        }
    }

    /** Discovery + bookkeeping: keeps a still-valid selection, otherwise selects the first model. */
    suspend fun refresh(config: ProviderConfig): NexusResult<ProviderConfig> = when (val result = fetchModels(config)) {
        is NexusResult.Failure -> result
        is NexusResult.Success -> {
            val models = result.data
            val selected = config.selectedModelId?.takeIf { id -> models.any { it.id == id } }
                ?: models.firstOrNull { it.isAlive }?.id
            NexusResult.Success(config.copy(models = models, selectedModelId = selected))
        }
    }

    /** Adds the workspace-aware touches discovery alone cannot know. */
    private fun enrich(model: ModelInfo, config: ProviderConfig): ModelInfo {
        val previous = config.models.firstOrNull { it.id == model.id }
        return model.copy(
            lastCheckedEpochMs = previous?.lastCheckedEpochMs ?: model.lastCheckedEpochMs,
            latencyMs = previous?.latencyMs,
            isAlive = previous?.isAlive ?: true,
            source = ModelSource.DISCOVERED,
            // Preserve capabilities the user may have hand-edited in Settings.
            capabilities = model.capabilities,
        )
    }

    private fun fallbacks(
        config: ProviderConfig,
        adapter: com.nexus.aichat.core.ai.protocol.ProviderAdapter,
    ): List<ModelInfo> = adapter.fallbackModels(config).ifEmpty { config.models }

    companion object {
        private const val TAG = "ModelDiscovery"
        /** Discovery results are cached this long before the UI nudges a refresh. */
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1_000L

        fun isStale(model: ModelInfo, nowMs: Long): Boolean =
            model.lastCheckedEpochMs?.let { nowMs - it > CACHE_TTL_MS } ?: true
    }
}
