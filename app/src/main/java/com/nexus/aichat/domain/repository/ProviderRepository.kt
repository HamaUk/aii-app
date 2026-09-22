package com.nexus.aichat.domain.repository

import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.domain.model.ProviderConfig
import com.nexus.aichat.domain.model.ProviderPreset
import kotlinx.coroutines.flow.Flow

/**
 * Provider lifecycle: add, configure, discover, verify.
 *
 * Note that this repository never sees a raw API key in a read path - it deals with vault *keys*. The
 * two write operations ([storeApiKey]/[clearApiKey]) are the only places a secret moves through it,
 * and they hand it straight to the Keystore-backed store.
 */
interface ProviderRepository {

    fun observeProviders(): Flow<List<ProviderConfig>>

    fun observeEnabledProviders(): Flow<List<ProviderConfig>>

    suspend fun provider(id: String): ProviderConfig?

    suspend fun addFromPreset(preset: ProviderPreset, apiKey: String?): NexusResult<ProviderConfig>

    suspend fun addCustom(config: ProviderConfig, apiKey: String?): NexusResult<ProviderConfig>

    suspend fun update(config: ProviderConfig)

    suspend fun delete(id: String)

    suspend fun storeApiKey(vaultKey: String, apiKey: String)

    suspend fun clearApiKey(vaultKey: String)

    suspend fun hasApiKey(vaultKey: String?): Boolean

    /** Queries `GET {base}/models`, with the preset fallback ladder. */
    suspend fun fetchModels(providerId: String): NexusResult<List<ModelInfo>>

    /** Real 1-token completion: proves key + model id + request shape all work. */
    suspend fun pingModel(providerId: String, modelId: String): NexusResult<ModelInfo>

    suspend fun selectModel(providerId: String, modelId: String)

    /** Creates and verifies a provider in one step - what the "Add provider" flow actually does. */
    suspend fun connectPreset(preset: ProviderPreset, apiKey: String?, preferredModelId: String?): NexusResult<ProviderConfig>
}
