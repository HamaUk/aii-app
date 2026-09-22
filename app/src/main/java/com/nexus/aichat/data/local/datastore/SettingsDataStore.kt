package com.nexus.aichat.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.AppearanceSettings
import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.ReasoningVisibility
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset
import com.nexus.aichat.core.model.ToolApprovalPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "nexus_settings")

/** Everything the app persists that is not a conversation, a provider or a secret. */
data class NexusSettings(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val agent: AgentDefaults = AgentDefaults(),
    val tools: ToolSettings = ToolSettings(),
)

/** Defaults applied to every *new* conversation (existing chats keep their own copy). */
data class AgentDefaults(
    val mode: AgentMode = AgentMode.REACT,
    val maxSteps: Int = 8,
    val approvalPolicy: ToolApprovalPolicy = ToolApprovalPolicy.AUTO_APPROVE_SAFE,
    val showReasoning: ReasoningVisibility = ReasoningVisibility.EXPANDED_WHILE_STREAMING,
    val autoEnableReasoning: Boolean = true,
    val allowClarificationPauses: Boolean = true,
) {
    fun toOptions() = AgentOptions(
        mode = mode,
        maxSteps = maxSteps,
        approvalPolicy = approvalPolicy,
        showReasoning = showReasoning,
        autoEnableReasoning = autoEnableReasoning,
        allowClarificationPauses = allowClarificationPauses,
    )
}

data class ToolSettings(
    val webFetchEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val documentReaderEnabled: Boolean = true,
    val dateTimeEnabled: Boolean = true,
    /** Off by default: an agent that can reach your LAN is an SSRF surface (see WebFetchTool). */
    val allowPrivateHosts: Boolean = false,
    val disabledToolNames: Set<String> = emptySet(),
)

/**
 * Single source of truth for settings, in Preferences DataStore.
 *
 * Two access styles, both needed:
 *  - [settings] is the reactive flow the UI collects;
 *  - [current] is a **synchronous snapshot** for code that cannot suspend - DI graph construction and
 *    tool instantiation. It is kept fresh by an internal collector, which is why this class owns a
 *    scope. That is a deliberate trade: tools need a policy value at call time, and making every tool
 *    suspend-read its settings would leak the store into `:core:ai`.
 */
class SettingsDataStore(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private object Keys {
        val preset = stringPreferencesKey("theme_preset")
        val brightness = stringPreferencesKey("theme_brightness")
        val dynamicColor = booleanPreferencesKey("theme_dynamic_color")
        val pureBlack = booleanPreferencesKey("theme_pure_black")
        val cornerStyle = stringPreferencesKey("theme_corner_style")
        val haptics = booleanPreferencesKey("haptics_enabled")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
        val agentMode = stringPreferencesKey("agent_mode")
        val agentMaxSteps = intPreferencesKey("agent_max_steps")
        val approvalPolicy = stringPreferencesKey("agent_approval_policy")
        val reasoningVisibility = stringPreferencesKey("agent_reasoning_visibility")
        val autoReasoning = booleanPreferencesKey("agent_auto_reasoning")
        val clarify = booleanPreferencesKey("agent_allow_clarification")
        val toolWebFetch = booleanPreferencesKey("tool_web_fetch")
        val toolWebSearch = booleanPreferencesKey("tool_web_search")
        val toolDocuments = booleanPreferencesKey("tool_documents")
        val toolDateTime = booleanPreferencesKey("tool_datetime")
        val allowPrivateHosts = booleanPreferencesKey("tool_allow_private_hosts")
    }

    val settings: Flow<NexusSettings> = context.settingsDataStore.data.map { prefs -> prefs.toSettings() }

    /** Cached snapshot for non-suspending call sites. Updated by [scope]. */
    @Volatile
    var current: NexusSettings = NexusSettings()
        private set

    init {
        scope.launch { settings.collect { current = it } }
    }

    private fun Preferences.toSettings(): NexusSettings {
        val appearanceDefaults = AppearanceSettings()
        val appearance = AppearanceSettings(
            preset = ThemePreset.fromId(this[Keys.preset]),
            brightnessMode = this[Keys.brightness]?.let { runCatching { ThemeBrightnessMode.valueOf(it) }.getOrNull() }
                ?: appearanceDefaults.brightnessMode,
            useDynamicColor = this[Keys.dynamicColor] ?: appearanceDefaults.useDynamicColor,
            pureBlackInDark = this[Keys.pureBlack] ?: appearanceDefaults.pureBlackInDark,
            cornerStyle = this[Keys.cornerStyle]?.let { runCatching { CornerStyle.valueOf(it) }.getOrNull() }
                ?: appearanceDefaults.cornerStyle,
            hapticsEnabled = this[Keys.haptics] ?: appearanceDefaults.hapticsEnabled,
            reducedMotion = this[Keys.reducedMotion] ?: appearanceDefaults.reducedMotion,
        )
        val agentDefaults = AgentDefaults()
        val agent = AgentDefaults(
            mode = this[Keys.agentMode]?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() } ?: agentDefaults.mode,
            maxSteps = this[Keys.agentMaxSteps] ?: agentDefaults.maxSteps,
            approvalPolicy = this[Keys.approvalPolicy]?.let { runCatching { ToolApprovalPolicy.valueOf(it) }.getOrNull() }
                ?: agentDefaults.approvalPolicy,
            showReasoning = this[Keys.reasoningVisibility]
                ?.let { runCatching { ReasoningVisibility.valueOf(it) }.getOrNull() }
                ?: agentDefaults.showReasoning,
            autoEnableReasoning = this[Keys.autoReasoning] ?: agentDefaults.autoEnableReasoning,
            allowClarificationPauses = this[Keys.clarify] ?: agentDefaults.allowClarificationPauses,
        )
        val toolDefaults = ToolSettings()
        val tools = ToolSettings(
            webFetchEnabled = this[Keys.toolWebFetch] ?: toolDefaults.webFetchEnabled,
            webSearchEnabled = this[Keys.toolWebSearch] ?: toolDefaults.webSearchEnabled,
            documentReaderEnabled = this[Keys.toolDocuments] ?: toolDefaults.documentReaderEnabled,
            dateTimeEnabled = this[Keys.toolDateTime] ?: toolDefaults.dateTimeEnabled,
            allowPrivateHosts = this[Keys.allowPrivateHosts] ?: toolDefaults.allowPrivateHosts,
        )
        return NexusSettings(appearance = appearance, agent = agent, tools = tools)
    }

    // --- writes ---------------------------------------------------------------------------------

    suspend fun setPreset(preset: ThemePreset) = edit { it[Keys.preset] = preset.id }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.dynamicColor] = enabled }
    suspend fun setBrightnessMode(mode: ThemeBrightnessMode) = edit { it[Keys.brightness] = mode.name }
    suspend fun setPureBlack(enabled: Boolean) = edit { it[Keys.pureBlack] = enabled }
    suspend fun setCornerStyle(style: CornerStyle) = edit { it[Keys.cornerStyle] = style.name }
    suspend fun setHaptics(enabled: Boolean) = edit { it[Keys.haptics] = enabled }
    suspend fun setReducedMotion(enabled: Boolean) = edit { it[Keys.reducedMotion] = enabled }

    suspend fun setAgentMode(mode: AgentMode) = edit { it[Keys.agentMode] = mode.name }
    suspend fun setAgentMaxSteps(steps: Int) = edit { it[Keys.agentMaxSteps] = steps.coerceIn(1, 24) }
    suspend fun setApprovalPolicy(policy: ToolApprovalPolicy) = edit { it[Keys.approvalPolicy] = policy.name }
    suspend fun setReasoningVisibility(visibility: ReasoningVisibility) =
        edit { it[Keys.reasoningVisibility] = visibility.name }
    suspend fun setAutoReasoning(enabled: Boolean) = edit { it[Keys.autoReasoning] = enabled }
    suspend fun setClarificationPauses(enabled: Boolean) = edit { it[Keys.clarify] = enabled }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) = edit { prefs ->
        when (toolName) {
            "web_fetch" -> prefs[Keys.toolWebFetch] = enabled
            "web_search" -> prefs[Keys.toolWebSearch] = enabled
            "read_document" -> prefs[Keys.toolDocuments] = enabled
            "get_datetime" -> prefs[Keys.toolDateTime] = enabled
            else -> Unit
        }
    }

    suspend fun setAllowPrivateHosts(enabled: Boolean) = edit { it[Keys.allowPrivateHosts] = enabled }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }
}
