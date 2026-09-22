package com.nexus.aichat.core.model

import kotlinx.serialization.Serializable

/**
 * Declarative description of a tool. This is the *only* thing sent to the model provider, which is
 * why it must stay provider-agnostic: each protocol adapter translates it into its own dialect
 * (OpenAI `tools[].function`, Anthropic `tools[]`, Gemini `functionDeclarations[]`).
 */
@Serializable
data class ToolSpec(
    val name: String,
    val displayName: String,
    val description: String,
    /** JSON Schema (draft-07 subset) for the arguments object. */
    val parametersJsonSchema: String,
    val category: ToolCategory = ToolCategory.UTILITY,
    val requiresNetwork: Boolean = false,
    val isDestructive: Boolean = false,
    val defaultApproval: ApprovalRequirement = ApprovalRequirement.AUTO,
    /** Hard cap on how much tool output is fed back into the context window. */
    val maxOutputChars: Int = 24_000,
    val iconKey: String = "wrench",
)

@Serializable
enum class ToolCategory { WEB, DOCUMENT, VISION, COMPUTE, MEMORY, UTILITY }

@Serializable
enum class ApprovalRequirement { AUTO, ASK_ONCE_PER_RUN, ASK_EVERY_TIME, DISABLED }

@Serializable
enum class ToolApprovalPolicy(val label: String) {
    AUTO_APPROVE_SAFE("Auto-approve read-only tools"),
    ASK_FIRST_TIME("Ask the first time each tool runs"),
    ASK_EVERY_TIME("Ask before every tool call"),
    DENY_ALL("Never run tools without asking"),
}

/** What the model asked for. */
@Serializable
data class ToolCallRequest(
    val callId: String,
    val toolName: String,
    val argumentsJson: String,
)

/** What the harness produced. [content] is what gets appended to the context window. */
@Serializable
data class ToolResult(
    val callId: String,
    val toolName: String,
    val isError: Boolean = false,
    val content: String,
    val preview: String = content.take(240),
    val durationMs: Long = 0,
    val citations: List<ToolCitation> = emptyList(),
    val artifacts: List<Attachment> = emptyList(),
    val truncated: Boolean = false,
) {
    companion object {
        fun error(callId: String, toolName: String, message: String, cause: Throwable? = null) = ToolResult(
            callId = callId,
            toolName = toolName,
            isError = true,
            content = buildString {
                append("<tool_error tool=\"").append(toolName).append("\">")
                append(message)
                cause?.message?.takeIf { it != message }?.let { append(" (").append(it).append(")") }
                append("</tool_error>")
            },
        )
    }
}

@Serializable
data class ToolCitation(val url: String, val title: String? = null, val snippet: String? = null)

/** Serializable view of the tool registry for the "Tools" settings screen. */
@Serializable
data class ToolDescriptor(
    val name: String,
    val displayName: String,
    val description: String,
    val category: ToolCategory,
    val requiresNetwork: Boolean,
    val isDestructive: Boolean,
    val isEnabled: Boolean = true,
    val defaultApproval: ApprovalRequirement = ApprovalRequirement.AUTO,
    val iconKey: String = "wrench",
)
