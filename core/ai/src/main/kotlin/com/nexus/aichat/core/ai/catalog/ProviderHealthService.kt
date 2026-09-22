package com.nexus.aichat.core.ai.catalog

import com.nexus.aichat.core.ai.protocol.AdapterRegistry
import com.nexus.aichat.core.ai.protocol.ProviderChatRequest
import com.nexus.aichat.core.ai.protocol.ProviderMessage
import com.nexus.aichat.core.ai.protocol.ProviderRole
import com.nexus.aichat.core.ai.protocol.ToolChoice
import com.nexus.aichat.core.ai.protocol.authenticatedUrl
import com.nexus.aichat.core.ai.protocol.ProviderContentPart
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.ai.transport.HttpRequestSpec
import com.nexus.aichat.core.ai.transport.HttpVerb
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.SamplingOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Latency / liveness probe - the "Test" button next to every model.
 *
 * It performs a real, minimal generation (`max_tokens = 1`, no tools, temperature 0) rather than a
 * `HEAD` or a `/models` lookup, because that is the only test that proves the *model* is actually
 * served, the key is valid and the request body shape is accepted. Cost: one token.
 *
 * Status classification is deliberately generous about "alive but unhappy" (429 => alive) and strict
 * about "not there" (404 => dead), because that is what makes the model picker trustworthy.
 */
class ProviderHealthService(
    private val transport: ChatTransport,
    private val adapters: AdapterRegistry,
    private val secrets: SecretProvider,
    private val clock: TimeProvider,
    private val logger: NexusLogger,
) {

    data class PingResult(
        val modelId: String,
        val displayName: String,
        val isAlive: Boolean,
        val latencyMs: Long?,
        val httpStatus: Int? = null,
        val message: String? = null,
    )

    suspend fun ping(config: ProviderConfig, model: ModelInfo): PingResult {
        val adapter = adapters.forConfig(config)
        val apiKey = runCatching { secrets.apiKeyFor(config) }.getOrNull()
        val url = config.authenticatedUrl(adapter.chatUrl(config, model.id, streaming = false), apiKey)

        val body = adapter.encodeChat(
            ProviderChatRequest(
                model = model.id,
                system = null,
                messages = listOf(
                    ProviderMessage(
                        role = ProviderRole.USER,
                        content = listOf(ProviderContentPart.Text("ping")),
                    ),
                ),
                tools = emptyList(),
                toolChoice = ToolChoice.None,
                sampling = SamplingOptions(temperature = 0.0, maxOutputTokens = 1),
                streaming = false,
            ),
            config,
        )

        val startedAt = clock.elapsedMillis()
        return try {
            val response = transport.execute(
                HttpRequestSpec(
                    verb = HttpVerb.POST,
                    url = url,
                    headers = adapter.headers(config, apiKey),
                    body = body,
                    label = "health.ping",
                ),
                connectTimeoutMs = 15_000,
                readTimeoutMs = 45_000,
            )
            val latency = clock.elapsedMillis() - startedAt
            when {
                response.isSuccess -> PingResult(model.id, model.displayName, true, latency, response.status)
                response.status == 429 -> PingResult(
                    model.id, model.displayName, true, latency, response.status,
                    "Alive but rate limited right now.",
                )
                response.status == 401 || response.status == 403 -> PingResult(
                    model.id, model.displayName, false, latency, response.status,
                    adapter.decodeError(response.status, response.body, config.id)?.message ?: "Authentication failed.",
                )
                response.status == 404 -> PingResult(
                    model.id, model.displayName, false, latency, response.status,
                    "Model not found on this endpoint (check the exact id).",
                )
                response.status in 400..499 -> PingResult(
                    model.id, model.displayName, true, latency, response.status,
                    adapter.decodeError(response.status, response.body, config.id)?.message
                        ?: "Endpoint responded (HTTP ${response.status}); request shape may need tweaking.",
                )
                else -> PingResult(
                    model.id, model.displayName, false, latency, response.status,
                    "Provider error (HTTP ${response.status}).",
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            val error = AppError.from(t)
            logger.w(TAG, "ping failed for ${config.id}/${model.id}: ${error.displayMessage}")
            PingResult(model.id, model.displayName, false, null, null, error.displayMessage)
        }
    }

    /** Probes a whole list in small parallel batches - enough to feel instant, gentle on the provider. */
    suspend fun pingAll(
        config: ProviderConfig,
        models: List<ModelInfo> = config.models,
        parallelism: Int = 3,
    ): List<PingResult> = coroutineScope {
        models.chunked(parallelism.coerceAtLeast(1)).flatMap { batch ->
            batch.map { model -> async { ping(config, model) } }.awaitAll()
        }
    }

    /** Applies probe results back onto the config so the picker can show latency + alive state. */
    fun applyToConfig(config: ProviderConfig, results: List<PingResult>): ProviderConfig {
        val byId = results.associateBy { it.modelId }
        val now = clock.nowEpochMillis()
        return config.copy(
            models = config.models.map { model ->
                byId[model.id]?.let { result ->
                    model.copy(
                        isAlive = result.isAlive,
                        latencyMs = result.latencyMs,
                        lastCheckedEpochMs = now,
                    )
                } ?: model
            },
        )
    }

    companion object {
        private const val TAG = "ProviderHealth"
    }
}
