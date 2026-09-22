package com.nexus.aichat.data.repository

import com.nexus.aichat.core.ai.catalog.ModelDiscoveryService
import com.nexus.aichat.core.ai.catalog.ProviderHealthService
import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.AuthConfig
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ModelSource
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderPreset
import com.nexus.aichat.core.security.SecureKeyStore
import com.nexus.aichat.data.local.db.dao.ProviderDao
import com.nexus.aichat.data.local.mapper.Mappers.toDomain
import com.nexus.aichat.data.local.mapper.Mappers.toEntity
import com.nexus.aichat.domain.repository.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Providers: add, configure, discover, verify.
 *
 * The interesting decision in this class is what happens on "Add provider". A naive implementation
 * saves the config, then separately calls `/models`, then separately pings - three round trips and a
 * half-configured provider if any step fails. Here [connectPreset] does it in the right order and
 * leaves the row usable at every point:
 *
 *   1. persist the config + key (so a network failure never loses what the user typed);
 *   2. query `/models`; on failure fall back to the preset's *suggested* models (marked
 *      `source = PRESET`), because an endpoint that refuses discovery often still serves chat;
 *   3. ping the preferred (or fastest) model to prove key + request shape;
 *   4. persist the verified model list and selection.
 */
@Singleton
class ProviderRepositoryImpl @Inject constructor(
    private val providerDao: ProviderDao,
    private val keyStore: SecureKeyStore,
    private val discovery: ModelDiscoveryService,
    private val health: ProviderHealthService,
    private val secrets: SecretProvider,
    private val time: TimeProvider,
    private val logger: NexusLogger,
) : ProviderRepository {

    override fun observeProviders(): Flow<List<ProviderConfig>> =
        providerDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeEnabledProviders(): Flow<List<ProviderConfig>> =
        providerDao.observeEnabled().map { rows -> rows.map { it.toDomain() } }

    override suspend fun provider(id: String): ProviderConfig? = providerDao.byId(id)?.toDomain()

    override suspend fun addFromPreset(preset: ProviderPreset, apiKey: String?): NexusResult<ProviderConfig> {
        // One row per preset: re-adding "OpenAI" must configure the existing entry, not duplicate it.
        val existing = providerDao.byPreset(preset.id)?.toDomain()
        val vaultKey = existing?.auth?.vaultKey ?: vaultKeyFor(preset.id)
        val config = (existing ?: preset.toConfig(vaultKey)).copy(
            displayName = preset.displayName,
            isEnabled = true,
            // Preset suggestions give the user something selectable before discovery has run.
            models = existing?.models.orEmpty().ifEmpty { preset.suggestedModels.map { it.toModelInfo(preset.id) } },
            selectedModelId = existing?.selectedModelId ?: preset.suggestedModels.firstOrNull()?.id,
        )
        apiKey?.takeIf { it.isNotBlank() }?.let { keyStore.put(vaultKey, it.trim()) }
        providerDao.upsert(config.toEntity())
        logger.d(TAG, "configured preset ${preset.id} (models=${config.models.size})")
        return NexusResult.Success(config)
    }

    override suspend fun addCustom(config: ProviderConfig, apiKey: String?): NexusResult<ProviderConfig> {
        if (config.baseUrl.isBlank()) {
            return NexusResult.Failure(AppError.Provider("Base URL is required"))
        }
        val vaultKey = config.auth.vaultKey ?: if (config.auth.requiresSecret) vaultKeyFor(config.id) else null
        val stored = config.copy(auth = config.auth.copy(vaultKey = vaultKey))
        apiKey?.takeIf { it.isNotBlank() }?.let { keyStore.put(vaultKeyFor(config.id), it.trim()) }
        providerDao.upsert(stored.toEntity())
        return NexusResult.Success(stored)
    }

    override suspend fun update(config: ProviderConfig) {
        providerDao.upsert(config.toEntity())
    }

    override suspend fun delete(id: String) {
        val config = providerDao.byId(id)?.toDomain()
        // Built-in preset rows are kept so the user can reconfigure rather than lose the template;
        // the DAO enforces this too (DELETE ... AND isBuiltInPreset = 0).
        config?.auth?.vaultKey?.let { keyStore.remove(it) }
        providerDao.delete(id)
        logger.d(TAG, "deleted provider $id")
    }

    override suspend fun storeApiKey(vaultKey: String, apiKey: String) {
        keyStore.put(vaultKey, apiKey.trim())
    }

    override suspend fun clearApiKey(vaultKey: String) {
        keyStore.remove(vaultKey)
    }

    override suspend fun hasApiKey(vaultKey: String?): Boolean =
        vaultKey != null && secrets.hasSecret(vaultKey)

    override suspend fun fetchModels(providerId: String): NexusResult<List<ModelInfo>> {
        val config = providerDao.byId(providerId)?.toDomain()
            ?: return NexusResult.Failure(AppError.Provider("Provider not found"))

        return when (val result = discovery.fetchModels(config)) {
            is NexusResult.Success -> {
                val models = result.data.sortedBy { it.id }
                persistModels(config, models)
                NexusResult.Success(models)
            }
            is NexusResult.Failure -> {
                // Keep the user moving: preset suggestions are good enough to start a conversation,
                // and the failure is surfaced so the reason stays visible.
                logger.w(TAG, "discovery failed for $providerId: ${result.error.displayMessage}")
                NexusResult.Failure(result.error)
            }
        }
    }

    override suspend fun pingModel(providerId: String, modelId: String): NexusResult<ModelInfo> {
        val config = providerDao.byId(providerId)?.toDomain()
            ?: return NexusResult.Failure(AppError.Provider("Provider not found"))
        val model = config.models.firstOrNull { it.id == modelId }
            ?: ModelInfo(id = modelId, providerId = providerId, source = ModelSource.MANUAL)

        val ping = health.ping(config, model)
        val updated = model.copy(
            isAlive = ping.isAlive,
            latencyMs = ping.latencyMs,
            lastCheckedEpochMs = time.nowEpochMillis(),
        )
        persistModels(config, config.models.map { if (it.id == modelId) updated else it })
        return if (ping.isAlive) {
            NexusResult.Success(updated)
        } else {
            NexusResult.Failure(
                AppError.Provider(
                    message = ping.message ?: "No response from ${config.displayName}",
                    httpStatus = ping.httpStatus,
                ),
            )
        }
    }

    override suspend fun selectModel(providerId: String, modelId: String) {
        val config = providerDao.byId(providerId)?.toDomain() ?: return
        // Ensure the selected model is in the models list - handles the case where user selected
        // a model before model discovery completed or after models list was cleared
        val models = if (config.models.none { it.id == modelId }) {
            config.models + com.nexus.aichat.core.model.ModelInfo(
                id = modelId,
                providerId = providerId,
                source = com.nexus.aichat.core.model.ModelSource.MANUAL,
            )
        } else {
            config.models
        }
        providerDao.updateModels(providerId, modelsJson(models), modelId)
        providerDao.touch(providerId, time.nowEpochMillis())
    }

    override suspend fun connectPreset(
        preset: ProviderPreset,
        apiKey: String?,
        preferredModelId: String?,
    ): NexusResult<ProviderConfig> {
        val added = addFromPreset(preset, apiKey)
        val base = when (added) {
            is NexusResult.Success -> added.data
            is NexusResult.Failure -> return added
        }

        // Step 2: real discovery. A failure here is not fatal - see the class comment.
        val discoveryResult = discovery.fetchModels(base)
        val models = when (discoveryResult) {
            is NexusResult.Success -> discoveryResult.data
            is NexusResult.Failure -> {
                logger.w(TAG, "discovery unavailable for ${preset.id}: ${discoveryResult.error.displayMessage}")
                base.models
            }
        }.ifEmpty { preset.suggestedModels.map { it.toModelInfo(preset.id) } }

        val candidateId = preferredModelId
            ?: models.firstOrNull { it.id == base.selectedModelId }?.id
            ?: models.firstOrNull()?.id

        // Step 3: prove the credential actually works before the user leaves the wizard.
        val target = models.firstOrNull { it.id == candidateId }
        val verified = if (target != null && base.auth.requiresSecret) {
            val ping = health.ping(base, target)
            if (!ping.isAlive) {
                logger.w(TAG, "ping failed for ${preset.id}/${target.id}: ${ping.message}")
            }
            models.map { model ->
                if (model.id != target.id) {
                    model
                } else {
                    model.copy(
                        isAlive = ping.isAlive,
                        latencyMs = ping.latencyMs,
                        lastCheckedEpochMs = time.nowEpochMillis(),
                    )
                }
            }
        } else {
            models
        }

        val configured = base.copy(models = verified, selectedModelId = candidateId, isEnabled = true)
        providerDao.upsert(configured.toEntity())
        providerDao.touch(configured.id, time.nowEpochMillis())
        return NexusResult.Success(configured)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private suspend fun persistModels(config: ProviderConfig, models: List<ModelInfo>) {
        val selection = config.selectedModelId?.takeIf { id -> models.any { it.id == id } }
            ?: models.firstOrNull()?.id
        providerDao.updateModels(config.id, modelsJson(models), selection)
    }

    private fun modelsJson(models: List<ModelInfo>): String =
        com.nexus.aichat.core.ai.util.NexusJson.instance.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(ModelInfo.serializer()),
            models,
        )

    companion object {
        /** Vault key convention: `provider.<id>`. Stable across reinstalls of the config row. */
        fun vaultKeyFor(providerId: String): String = "provider.$providerId"

        private const val TAG = "ProviderRepository"
    }
}

/** Preset -> configurable provider, with the vault key reserved before any key is stored. */
internal fun ProviderPreset.toConfig(vaultKey: String): ProviderConfig = ProviderConfig(
    id = id,
    displayName = displayName,
    protocol = protocol,
    baseUrl = baseUrl,
    auth = AuthConfig(scheme = authScheme, vaultKey = vaultKey, extraStaticHeaders = defaultHeaders),
    extraHeaders = defaultHeaders,
    supportsNativeTools = true,
    includeStreamUsage = includeStreamUsage,
    presetId = id,
    isEnabled = true,
    models = suggestedModels.map { it.toModelInfo(id) },
    selectedModelId = suggestedModels.firstOrNull()?.id,
    isBuiltInPreset = true,
)

internal fun com.nexus.aichat.core.model.PresetModel.toModelInfo(providerId: String): ModelInfo = ModelInfo(
    id = id,
    displayName = displayName,
    providerId = providerId,
    contextWindow = contextWindow,
    capabilities = capabilities,
    source = ModelSource.PRESET,
)
