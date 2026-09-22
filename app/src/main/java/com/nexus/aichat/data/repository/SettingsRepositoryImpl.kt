package com.nexus.aichat.data.repository

import com.nexus.aichat.core.model.CornerStyle
import com.nexus.aichat.core.model.SystemRule
import com.nexus.aichat.core.model.ThemeBrightnessMode
import com.nexus.aichat.core.model.ThemePreset
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.data.local.datastore.AgentDefaults
import com.nexus.aichat.data.local.datastore.NexusSettings
import com.nexus.aichat.data.local.datastore.SettingsDataStore
import com.nexus.aichat.data.local.datastore.ToolSettings
import com.nexus.aichat.data.local.db.dao.SystemRuleDao
import com.nexus.aichat.data.local.mapper.Mappers.toDomain
import com.nexus.aichat.data.local.mapper.Mappers.toEntity
import com.nexus.aichat.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Settings and custom instructions.
 *
 * The rules API is the interesting half: a rule is a named block of instructions that is either global
 * (applies to every chat) or attachable per conversation. Precedence and composition live in
 * `SystemPromptComposer` in `:core:model`, so this class only has to answer "which rules are in play?"
 * - which is a database question, and that is exactly what it answers.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val systemRuleDao: SystemRuleDao,
    private val time: TimeProvider,
) : SettingsRepository {

    override val settings: Flow<NexusSettings> = settingsDataStore.settings

    override suspend fun setThemePreset(preset: ThemePreset) = settingsDataStore.setPreset(preset)

    override suspend fun setBrightnessMode(mode: ThemeBrightnessMode) = settingsDataStore.setBrightnessMode(mode)

    override suspend fun setDynamicColor(enabled: Boolean) = settingsDataStore.setDynamicColor(enabled)

    override suspend fun setPureBlack(enabled: Boolean) = settingsDataStore.setPureBlack(enabled)

    override suspend fun setCornerStyle(style: CornerStyle) = settingsDataStore.setCornerStyle(style)

    override suspend fun setHaptics(enabled: Boolean) = settingsDataStore.setHaptics(enabled)

    override suspend fun setReducedMotion(enabled: Boolean) = settingsDataStore.setReducedMotion(enabled)

    override suspend fun updateAgentDefaults(transform: (AgentDefaults) -> AgentDefaults) {
        val current = settingsDataStore.current.agent
        val next = transform(current)
        if (next.mode != current.mode) settingsDataStore.setAgentMode(next.mode)
        if (next.maxSteps != current.maxSteps) settingsDataStore.setAgentMaxSteps(next.maxSteps)
        if (next.approvalPolicy != current.approvalPolicy) settingsDataStore.setApprovalPolicy(next.approvalPolicy)
        if (next.showReasoning != current.showReasoning) settingsDataStore.setReasoningVisibility(next.showReasoning)
        if (next.autoEnableReasoning != current.autoEnableReasoning) settingsDataStore.setAutoReasoning(next.autoEnableReasoning)
        if (next.allowClarificationPauses != current.allowClarificationPauses) {
            settingsDataStore.setClarificationPauses(next.allowClarificationPauses)
        }
    }

    override suspend fun updateToolSettings(transform: (ToolSettings) -> ToolSettings) {
        val current = settingsDataStore.current.tools
        val next = transform(current)
        if (next.webFetchEnabled != current.webFetchEnabled) settingsDataStore.setToolEnabled("web_fetch", next.webFetchEnabled)
        if (next.webSearchEnabled != current.webSearchEnabled) settingsDataStore.setToolEnabled("web_search", next.webSearchEnabled)
        if (next.documentReaderEnabled != current.documentReaderEnabled) settingsDataStore.setToolEnabled("read_document", next.documentReaderEnabled)
        if (next.dateTimeEnabled != current.dateTimeEnabled) settingsDataStore.setToolEnabled("get_datetime", next.dateTimeEnabled)
        if (next.allowPrivateHosts != current.allowPrivateHosts) settingsDataStore.setAllowPrivateHosts(next.allowPrivateHosts)
    }

    override fun observeRules(): Flow<List<SystemRule>> =
        systemRuleDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsertRule(rule: SystemRule) {
        val now = time.nowEpochMillis()
        val stamped = rule.copy(
            createdAtEpochMs = rule.createdAtEpochMs.takeIf { it > 0 } ?: now,
            updatedAtEpochMs = now,
        )
        systemRuleDao.upsert(stamped.toEntity())
    }

    override suspend fun deleteRule(ruleId: String) = systemRuleDao.delete(ruleId)

    override suspend fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        val existing = systemRuleDao.byIds(listOf(ruleId)).firstOrNull()?.toDomain() ?: return
        systemRuleDao.upsert(existing.copy(isEnabled = enabled, updatedAtEpochMs = time.nowEpochMillis()).toEntity())
    }

    override suspend fun effectiveRules(conversationRuleIds: List<String>): List<SystemRule> {
        val global = systemRuleDao.enabledGlobal().map { it.toDomain() }
        if (conversationRuleIds.isEmpty()) return global
        val perChat = systemRuleDao.byIds(conversationRuleIds).map { it.toDomain() }
        // Global first, then chat-specific: later rules win in SystemPromptComposer.
        return (global + perChat).distinctBy { it.id }
    }
}
