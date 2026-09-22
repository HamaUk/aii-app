package com.nexus.aichat.domain.agent

import com.nexus.aichat.core.ai.agent.ToolRegistry as CoreToolRegistry
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ToolDescriptor
import com.nexus.aichat.core.model.ToolSpec
import com.nexus.aichat.data.local.datastore.SettingsDataStore
import com.nexus.aichat.domain.tools.ToolCatalog
import com.nexus.aichat.domain.tools.ToolPlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-side view of the tool set: what exists, what is switched on, and what this model can actually use.
 *
 * Two filters, and they are different problems:
 *  1. **User settings** decide whether a tool is available at all (persistent, applies to every run);
 *  2. **Model capability + per-run options** decide what is offered *this* run. Sending a tool list to a
 *     model that cannot call tools wastes context and, on Anthropic/Gemini, fails the request outright.
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val coreRegistry: CoreToolRegistry,
    private val settings: SettingsDataStore,
) {

    /** Everything registered, for the Settings > Tools screen. */
    fun catalog(): List<ToolPlugin> =
        ToolCatalog.sorted(coreRegistry.descriptors()).map { ToolPlugin.from(it) }

    fun descriptors(): List<ToolDescriptor> = ToolCatalog.sorted(coreRegistry.descriptors())

    fun isEnabledBySettings(toolName: String): Boolean {
        val tools = settings.current.tools
        return when (toolName) {
            "web_fetch" -> tools.webFetchEnabled
            "web_search" -> tools.webSearchEnabled
            "read_document" -> tools.documentReaderEnabled
            "get_datetime" -> tools.dateTimeEnabled
            "inspect_image" -> true
            else -> true
        }
    }

    /**
     * Options for a specific run: settings-level disables are folded into [AgentOptions.disabledToolNames],
     * so the harness - not the UI - owns the final decision about what the model may call.
     */
    fun optionsFor(model: ModelInfo, base: AgentOptions): AgentOptions {
        val disabledBySettings = descriptors()
            .map { it.name }
            .filterNot { isEnabledBySettings(it) }
            .toSet()
        val visionUnavailable = !model.supportsVision
        return base.copy(
            disabledToolNames = base.disabledToolNames + disabledBySettings,
            enabledToolNames = base.enabledToolNames,
            // A model with no vision still gets `inspect_image`: it is how it explains what it cannot see.
            forceToolsOnFirstStep = base.forceToolsOnFirstStep && visionUnavailable.not(),
        )
    }

    fun specsFor(model: ModelInfo, options: AgentOptions): List<ToolSpec> =
        coreRegistry.specsFor(optionsFor(model, options), model)

    fun supportsTools(model: ModelInfo): Boolean = CoreToolRegistry.supportsTools(model)
}
