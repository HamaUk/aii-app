package com.nexus.aichat.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.aichat.core.common.result.NexusResult
import com.nexus.aichat.core.model.AuthConfig
import com.nexus.aichat.core.model.AuthScheme
import com.nexus.aichat.core.model.ProviderConfig
import com.nexus.aichat.core.model.ProviderPreset
import com.nexus.aichat.core.model.ProviderPresets
import com.nexus.aichat.core.model.ProviderProtocol
import com.nexus.aichat.domain.repository.ProviderRepository
import com.nexus.aichat.domain.usecase.ManageProviderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Provider list state + the in-progress setup form. */
data class ProvidersUiState(
    val presets: List<ProviderPreset> = ProviderPresets.ALL,
    val expandedProviderId: String? = null,
    val busyProviderId: String? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

/**
 * Provider management.
 *
 * The flow this VM encodes is the one users actually follow: pick a template, paste a key, watch it get
 * verified, then optionally poke at the model list. Everything else (custom protocols, header overrides,
 * raw templates) is reachable but never in the way.
 */
@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val providerRepository: ProviderRepository,
    private val manageProvider: ManageProviderUseCase,
) : ViewModel() {

    private val _ui = MutableStateFlow(ProvidersUiState())
    val ui: StateFlow<ProvidersUiState> = _ui.asStateFlow()

    val providers: StateFlow<List<ProviderConfig>> = providerRepository.observeProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _storedKeyNames = MutableStateFlow<Set<String>>(emptySet())

    /** Which vault entries exist, so the UI can say "key stored" instead of showing a blank field. */
    val storedKeyNames: StateFlow<Set<String>> = _storedKeyNames.asStateFlow()

    fun refreshVault() {
        viewModelScope.launch {
            val names = providers.value.mapNotNull { it.auth.vaultKey }
            val present = names.filter { providerRepository.hasApiKey(it) }.toSet()
            _storedKeyNames.value = present
        }
    }

    // --- preset flow --------------------------------------------------------------------------------

    fun connectPreset(preset: ProviderPreset, apiKey: String?, preferredModelId: String? = null) {
        _ui.update { it.copy(busyProviderId = preset.id, errorMessage = null, statusMessage = "Connecting to ${preset.displayName}\u2026") }
        viewModelScope.launch {
            when (val result = manageProvider.connectPreset(preset, apiKey, preferredModelId)) {
                is NexusResult.Success -> {
                    val alive = result.data.models.count { it.isAlive }
                    _ui.update {
                        it.copy(
                            busyProviderId = null,
                            statusMessage = "${preset.displayName}: ${result.data.models.size} models " +
                                "($alive verified)",
                        )
                    }
                    refreshVault()
                }
                is NexusResult.Failure -> _ui.update {
                    it.copy(busyProviderId = null, statusMessage = null, errorMessage = result.error.displayMessage)
                }
            }
        }
    }

    // --- custom flow --------------------------------------------------------------------------------

    fun saveCustom(config: ProviderConfig, apiKey: String?) {
        _ui.update { it.copy(busyProviderId = config.id, errorMessage = null) }
        viewModelScope.launch {
            when (val result = manageProvider.saveCustom(config, apiKey)) {
                is NexusResult.Success -> {
                    _ui.update { it.copy(busyProviderId = null, statusMessage = "${config.displayName} saved") }
                    refreshVault()
                    fetchModels(config.id)
                }
                is NexusResult.Failure -> _ui.update {
                    it.copy(busyProviderId = null, errorMessage = result.error.displayMessage)
                }
            }
        }
    }

    fun update(config: ProviderConfig, apiKey: String? = null) {
        viewModelScope.launch {
            when (val result = manageProvider.update(config, apiKey)) {
                is NexusResult.Success -> {
                    _ui.update { it.copy(statusMessage = "${config.displayName} updated") }
                    refreshVault()
                }
                is NexusResult.Failure -> _ui.update { it.copy(errorMessage = result.error.displayMessage) }
            }
        }
    }

    // --- operations ---------------------------------------------------------------------------------

    fun fetchModels(providerId: String) {
        _ui.update { it.copy(busyProviderId = providerId, statusMessage = "Fetching models\u2026") }
        viewModelScope.launch {
            when (val result = manageProvider.fetchModels(providerId)) {
                is NexusResult.Success ->
                    _ui.update { it.copy(busyProviderId = null, statusMessage = "${result.data.size} models found") }
                is NexusResult.Failure ->
                    _ui.update { it.copy(busyProviderId = null, statusMessage = null, errorMessage = result.error.displayMessage) }
            }
        }
    }

    /**
     * Latency test for every model on a provider, run with a small parallelism budget by the core health
     * service so a provider with 200 models does not open 200 sockets at once.
     */
    fun pingModels(providerId: String) {
        _ui.update { it.copy(busyProviderId = providerId, statusMessage = "Testing models\u2026") }
        viewModelScope.launch {
            val config = providerRepository.provider(providerId) ?: return@launch
            var alive = 0
            config.models.take(12).forEach { model ->
                if (manageProvider.pingModel(providerId, model.id) is NexusResult.Success) alive++
            }
            _ui.update { it.copy(busyProviderId = null, statusMessage = "$alive of ${config.models.size} responded") }
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        viewModelScope.launch { providerRepository.selectModel(providerId, modelId) }
    }

    fun addManualModel(config: ProviderConfig, modelId: String) {
        viewModelScope.launch {
            when (val result = manageProvider.addManualModel(config, modelId)) {
                is NexusResult.Success -> _ui.update { it.copy(statusMessage = "Added $modelId") }
                is NexusResult.Failure -> _ui.update { it.copy(errorMessage = result.error.displayMessage) }
            }
        }
    }

    fun delete(providerId: String) {
        viewModelScope.launch {
            manageProvider.delete(providerId)
            refreshVault()
        }
    }

    fun clearKey(vaultKey: String) {
        viewModelScope.launch {
            manageProvider.clearApiKey(vaultKey)
            refreshVault()
        }
    }

    fun dismissMessages() = _ui.update { it.copy(statusMessage = null, errorMessage = null) }

    /** Draft for the custom-provider builder, seeded with protocol defaults. */
    fun draft(protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE) = manageProvider.draftConfig(protocol)

    fun validate(config: ProviderConfig): String? = manageProvider.validate(config)

    fun authHint(scheme: AuthScheme): String = when (scheme) {
        AuthScheme.NONE -> "No key needed"
        AuthScheme.BEARER -> "Sent as: Authorization: Bearer <key>"
        AuthScheme.X_API_KEY -> "Sent as: x-api-key: <key>"
        AuthScheme.X_GOOG_API_KEY -> "Sent as: x-goog-api-key: <key>"
        AuthScheme.QUERY_PARAM -> "Appended to the URL as a query parameter"
        AuthScheme.CUSTOM_HEADER -> "Sent in a header whose name you choose"
    }

    fun defaultAuth(scheme: AuthScheme, headerName: String? = null) =
        AuthConfig(scheme = scheme, vaultKey = null, headerName = headerName)
}
