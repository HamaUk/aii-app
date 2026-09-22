package com.nexus.aichat.domain.repository

import com.nexus.aichat.core.model.SystemRule
import com.nexus.aichat.data.local.datastore.AgentDefaults
import com.nexus.aichat.data.local.datastore.NexusSettings
import com.nexus.aichat.data.local.datastore.ToolSettings
import com.nexus.aichat.core.model.AppearanceSettings
import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset
import kotlinx.coroutines.flow.Flow

/**
 * Settings + custom instructions (personas).
 *
 * Personas live here rather than in the chat repository because they are *global* configuration: they
 * apply to every conversation unless a chat overrides them, exactly like the theme does.
 */
interface SettingsRepository {

    val settings: Flow<NexusSettings>

    // --- appearance ------------------------------------------------------------------------------
    suspend fun setThemePreset(preset: ThemePreset)
    suspend fun setBrightnessMode(mode: ThemeBrightnessMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setPureBlack(enabled: Boolean)
    suspend fun setCornerStyle(style: CornerStyle)
    suspend fun setHaptics(enabled: Boolean)
    suspend fun setReducedMotion(enabled: Boolean)

    // --- agent defaults --------------------------------------------------------------------------
    suspend fun updateAgentDefaults(transform: (AgentDefaults) -> AgentDefaults)

    // --- tools -----------------------------------------------------------------------------------
    suspend fun updateToolSettings(transform: (ToolSettings) -> ToolSettings)

    // --- custom rules / personas -------------------------------------------------------------------
    fun observeRules(): Flow<List<SystemRule>>
    suspend fun upsertRule(rule: SystemRule)
    suspend fun deleteRule(ruleId: String)
    suspend fun setRuleEnabled(ruleId: String, enabled: Boolean)

    /** Rules that should apply to a specific conversation: global ones plus that chat's own. */
    suspend fun effectiveRules(conversationRuleIds: List<String>): List<SystemRule>
}
