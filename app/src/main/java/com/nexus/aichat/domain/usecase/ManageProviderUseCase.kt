package com.nexus.aichat.domain.usecase

import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.model.AuthConfig
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderPreset
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.data.remote.api.AnthropicApi
import com.nexus.aichat.data.remote.api.GeminiApi
import com.nexus.aichat.data.remote.api.GenericOpenAICompatibleApi
import com.nexus.aichat.data.remote.api.OpenAIApi
import com.nexus.aichat.domain.repository.ProviderRepository
import java.util.UUID
import javax.inject.Inject

/**
 * Create, edit and remove providers - preset or hand-built.
 *
 * Everything that decides whether a config is *usable* lives here: URL shape per protocol, the auth
 * scheme a protocol expects, which header a key goes in, and the vault key under which it must be
 * stored. The screens stay dumb forms; this is the only place a `ProviderConfig` is assembled.
 */
class ManageProviderUseCase @Inject constructor(
    private val providerRepository: ProviderRepository,
) {

    /** A blank custom provider with sensible protocol defaults, for the builder's first frame. */
    fun draftConfig(
        protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
        displayName: String = "",
        baseUrl: String = "",
    ): ProviderConfig {
        val id = UUID.randomUUID().toString()
        return ProviderConfig(
            id = id,
            displayName = displayName,
            protocol = protocol,
            baseUrl = baseUrl,
            auth = AuthConfig(scheme = protocol.defaultAuthScheme(), vaultKey = "provider.$id"),
            modelsPath = defaultModelsPath(protocol),
            supportsNativeTools = true,
            includeStreamUsage = protocol == ProviderProtocol.OPENAI_COMPATIBLE,
            isEnabled = true,
        )
    }

    suspend fun connectPreset(preset: ProviderPreset, apiKey: String?, preferredModelId: String? = null) =
        providerRepository.connectPreset(preset, apiKey, preferredModelId)

    suspend fun saveCustom(config: ProviderConfig, apiKey: String?): NexusResult<ProviderConfig> {
        val problem = validate(config)
        if (problem != null) {
            return NexusResult.Failure(com.nexus.aichat.core.common.error.AppError.Provider(problem))
        }
        return providerRepository.addCustom(config, apiKey)
    }

    suspend fun update(config: ProviderConfig, apiKey: String? = null): NexusResult<ProviderConfig> {
        val problem = validate(config)
        if (problem != null) {
            return NexusResult.Failure(com.nexus.aichat.core.common.error.AppError.Provider(problem))
        }
        if (!apiKey.isNullOrBlank()) {
            config.auth.vaultKey?.let { providerRepository.storeApiKey(it, apiKey) }
        }
        providerRepository.update(config)
        return NexusResult.Success(config)
    }

    suspend fun delete(providerId: String) = providerRepository.delete(providerId)

    suspend fun clearApiKey(vaultKey: String) = providerRepository.clearApiKey(vaultKey)

    suspend fun fetchModels(providerId: String): NexusResult<List<ModelInfo>> =
        providerRepository.fetchModels(providerId)

    suspend fun pingModel(providerId: String, modelId: String): NexusResult<ModelInfo> =
        providerRepository.pingModel(providerId, modelId)

    suspend fun selectModel(providerId: String, modelId: String) = providerRepository.selectModel(providerId, modelId)

    suspend fun addManualModel(provider: ProviderConfig, modelId: String): NexusResult<ProviderConfig> {
        val id = modelId.trim()
        if (id.isBlank()) {
            return NexusResult.Failure(com.nexus.aichat.core.common.error.AppError.Provider("Enter a model id"))
        }
        val model = ModelInfo(
            id = id,
            providerId = provider.id,
            capabilities = GenericOpenAICompatibleApi.inferCapabilities(id),
            source = ModelSource.MANUAL,
        )
        val updated = provider.copy(models = (provider.models.filterNot { it.id == id } + model), selectedModelId = id)
        providerRepository.update(updated)
        return NexusResult.Success(updated)
    }

    /** Human-readable validation problem, or null when the config is coherent. */
    fun validate(config: ProviderConfig): String? {
        if (config.displayName.isBlank()) return "Give the provider a name"
        val urlProblem = when (config.protocol) {
            ProviderProtocol.OPENAI_COMPATIBLE -> OpenAIApi.validateBaseUrl(config.baseUrl)
            ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicApi.validateBaseUrl(config.baseUrl)
            ProviderProtocol.GOOGLE_GEMINI -> GeminiApi.validateBaseUrl(config.baseUrl)
            ProviderProtocol.RAW_REST -> if (config.baseUrl.isBlank()) "Base URL is required" else null
        }
        if (urlProblem != null) return urlProblem
        if (config.auth.scheme == AuthScheme.CUSTOM_HEADER && config.auth.headerName.isNullOrBlank()) {
            return "Name the header that carries your key"
        }
        if (config.protocol == ProviderProtocol.RAW_REST && config.requestBodyTemplate.isNullOrBlank()) {
            return "A raw REST provider needs a request body template"
        }
        return null
    }

    private fun ProviderProtocol.defaultAuthScheme(): AuthScheme = when (this) {
        ProviderProtocol.OPENAI_COMPATIBLE -> AuthScheme.BEARER
        ProviderProtocol.ANTHROPIC_MESSAGES -> AuthScheme.X_API_KEY
        ProviderProtocol.GOOGLE_GEMINI -> AuthScheme.X_GOOG_API_KEY
        ProviderProtocol.RAW_REST -> AuthScheme.NONE
    }

    private fun defaultModelsPath(protocol: ProviderProtocol): String = when (protocol) {
        ProviderProtocol.GOOGLE_GEMINI -> GeminiApi.MODELS_PATH
        ProviderProtocol.ANTHROPIC_MESSAGES -> AnthropicApi.MODELS_PATH
        else -> OpenAIApi.MODELS_PATH
    }
}
