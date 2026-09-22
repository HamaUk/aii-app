package com.nexus.aichat.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.ReasoningVisibility
import com.nexus.aichat.core.model.SystemRule
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset
import com.nexus.aichat.core.model.ToolApprovalPolicy
import com.nexus.aichat.core.model.ToolDescriptor
import com.nexus.aichat.core.security.SecureKeyStore
import com.nexus.aichat.data.local.datastore.AgentDefaults
import com.nexus.aichat.data.local.datastore.NexusSettings
import com.nexus.aichat.data.local.datastore.ToolSettings
import com.nexus.aichat.domain.agent.ToolRegistry
import com.nexus.aichat.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * One ViewModel for the whole settings hub.
 *
 * Settings screens are leaves off a single branch, they all read the same two flows, and none of them has
 * state worth surviving navigation on its own. Splitting this into five ViewModels would mean five
 * subscriptions to the same settings flow and five places to update when a preference is added.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry,
    private val keyStore: SecureKeyStore,
) : ViewModel() {

    val settings: StateFlow<NexusSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NexusSettings())

    val rules: StateFlow<List<SystemRule>> = settingsRepository.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Tools with their live enabled state resolved from settings. */
    val tools: StateFlow<List<ToolDescriptor>> = settingsRepository.settings
        .map { current ->
            toolRegistry.descriptors().map { descriptor ->
                descriptor.copy(isEnabled = toolRegistry.isEnabledBySettings(descriptor.name))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _keystoreHealthy = MutableStateFlow<Boolean?>(null)
    val keystoreHealthy: StateFlow<Boolean?> = _keystoreHealthy.asStateFlow()

    // --- appearance --------------------------------------------------------------------------------

    fun setPreset(preset: ThemePreset) = launch { settingsRepository.setThemePreset(preset) }
    fun setBrightness(mode: ThemeBrightnessMode) = launch { settingsRepository.setBrightnessMode(mode) }
    fun setDynamicColor(enabled: Boolean) = launch { settingsRepository.setDynamicColor(enabled) }
    fun setPureBlack(enabled: Boolean) = launch { settingsRepository.setPureBlack(enabled) }
    fun setCornerStyle(style: CornerStyle) = launch { settingsRepository.setCornerStyle(style) }
    fun setHaptics(enabled: Boolean) = launch { settingsRepository.setHaptics(enabled) }
    fun setReducedMotion(enabled: Boolean) = launch { settingsRepository.setReducedMotion(enabled) }

    // --- agent -------------------------------------------------------------------------------------

    fun setAgentMode(mode: AgentMode) = updateAgent { it.copy(mode = mode) }
    fun setMaxSteps(steps: Int) = updateAgent { it.copy(maxSteps = steps) }
    fun setApprovalPolicy(policy: ToolApprovalPolicy) = updateAgent { it.copy(approvalPolicy = policy) }
    fun setReasoningVisibility(visibility: ReasoningVisibility) = updateAgent { it.copy(showReasoning = visibility) }
    fun setAutoReasoning(enabled: Boolean) = updateAgent { it.copy(autoEnableReasoning = enabled) }
    fun setClarificationPauses(enabled: Boolean) = updateAgent { it.copy(allowClarificationPauses = enabled) }

    /** Changing the brightness mode from the appearance screen also has to move the *preset* off AUTO. */
    fun setBrightnessExplicit(mode: ThemeBrightnessMode) = launch {
        settingsRepository.setBrightnessMode(mode)
    }

    // --- tools -------------------------------------------------------------------------------------

    fun setToolEnabled(toolName: String, enabled: Boolean) = launch {
        settingsRepository.updateToolSettings { current ->
            when (toolName) {
                "web_fetch" -> current.copy(webFetchEnabled = enabled)
                "web_search" -> current.copy(webSearchEnabled = enabled)
                "read_document" -> current.copy(documentReaderEnabled = enabled)
                "get_datetime" -> current.copy(dateTimeEnabled = enabled)
                else -> current
            }
        }
    }

    fun setAllowPrivateHosts(enabled: Boolean) = launch {
        settingsRepository.updateToolSettings { it.copy(allowPrivateHosts = enabled) }
    }

    // --- rules -------------------------------------------------------------------------------------

    fun createRule(title: String, body: String, global: Boolean) = launch {
        settingsRepository.upsertRule(
            SystemRule(
                id = UUID.randomUUID().toString(),
                title = title,
                body = body,
                appliesGlobally = global,
            ),
        )
    }

    fun updateRule(rule: SystemRule) = launch { settingsRepository.upsertRule(rule) }
    fun deleteRule(ruleId: String) = launch { settingsRepository.deleteRule(ruleId) }
    fun setRuleEnabled(ruleId: String, enabled: Boolean) = launch {
        settingsRepository.setRuleEnabled(ruleId, enabled)
    }

    // --- security ----------------------------------------------------------------------------------

    /** Runs the vault self-test on demand: "is my key storage actually working on this device?" */
    fun runKeystoreSelfTest() {
        viewModelScope.launch { _keystoreHealthy.value = keyStore.selfTest() }
    }

    private fun updateAgent(transform: (AgentDefaults) -> AgentDefaults) =
        launch { settingsRepository.updateAgentDefaults(transform) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
