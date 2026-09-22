package com.nexus.aichat.core.ai.agent

import com.nexus.aichat.core.common.error.AppError
import com.nexus.aichat.core.common.error.AppException
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.ModelCapability
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.ToolDescriptor
import com.nexus.aichat.core.model.ToolSpec

/**
 * The set of tools the harness can offer a model, filtered per run.
 *
 * Filtering matters: sending vision or search tools to a model that cannot call them wastes context
 * and, on some providers (Gemini, Anthropic), makes the request fail outright.
 */
class ToolRegistry(tools: Set<AgentTool>) {

    private val byName: Map<String, AgentTool> = tools.associateBy { it.spec.name }

    val all: List<AgentTool> get() = byName.values.toList()

    fun byName(name: String): AgentTool? = byName[name]

    /**
     * Tools eligible for a specific run, in a stable order (spec order matters for prompt caching:
     * a stable tool list keeps Anthropic's prompt cache warm across turns).
     */
    fun enabledFor(options: AgentOptions, model: ModelInfo?): List<AgentTool> = all
        .filter { tool ->
            val spec = tool.spec
            if (spec.name in options.disabledToolNames) return@filter false
            if (spec.defaultApproval == ApprovalRequirement.DISABLED) return@filter false
            if (options.enabledToolNames.isNotEmpty() && spec.name !in options.enabledToolNames) return@filter false
            true
        }
        .sortedBy { it.spec.category.ordinal * 100 + it.spec.name.hashCode().mod(99) }

    fun specsFor(options: AgentOptions, model: ModelInfo?): List<ToolSpec> {
        if (options.mode == com.nexus.aichat.core.model.AgentMode.SINGLE_SHOT) return emptyList()
        // A model with no tool-calling capability still gets the prompt-level contract, but no tools:
        // the harness then simply answers, which is the honest behaviour.
        if (model != null && !model.supportsTools) return emptyList()
        return enabledFor(options, model).map { it.spec }
    }

    fun descriptors(): List<ToolDescriptor> = all.map { tool ->
        ToolDescriptor(
            name = tool.spec.name,
            displayName = tool.spec.displayName,
            description = tool.spec.description,
            category = tool.spec.category,
            requiresNetwork = tool.spec.requiresNetwork,
            isDestructive = tool.spec.isDestructive,
            defaultApproval = tool.spec.defaultApproval,
            iconKey = tool.spec.iconKey,
        )
    }

    class UnknownTool(name: String) : Exception("No tool registered under the name '$name'")

    fun require(name: String): AgentTool = byName[name] ?: throw AppError.ToolExecution("Unknown tool '$name'", name).let(::AppException)

    companion object {
        /** Capability gate used by the UI as well, to show/hide the tools section. */
        fun supportsTools(model: ModelInfo): Boolean = ModelCapability.TOOL_CALLING in model.capabilities
    }
}
