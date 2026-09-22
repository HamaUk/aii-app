package com.nexus.aichat.core.ai.agent

import com.nexus.aichat.core.ai.protocol.ProviderContentPart
import com.nexus.aichat.core.ai.protocol.ProviderMessage
import com.nexus.aichat.core.ai.protocol.ProviderRole
import com.nexus.aichat.core.ai.spi.BinaryResolver
import com.nexus.aichat.core.ai.spi.ImageScaler
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.model.AgentMode
import com.nexus.aichat.core.model.AgentOptions
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.Message
import com.nexus.aichat.core.model.MessagePart
import com.nexus.aichat.core.model.MessageRole
import com.nexus.aichat.core.model.ModelInfo
import com.nexus.aichat.core.model.SystemPromptComposer
import com.nexus.aichat.core.model.ToolSpec
import java.util.Base64

/**
 * Builds everything the model sees: the system contract, the tool instructions and the replayed
 * transcript (including images and prior tool results).
 *
 * Two rules drive the design:
 *  1. The transcript sent to a provider must be *protocol-valid*: an assistant turn with tool calls
 *     must be immediately followed by matching tool results, or Anthropic/OpenAI reject the request.
 *  2. Prompt content should be stable across turns wherever possible, so provider-side prompt
 *     caching (Anthropic, OpenAI, Gemini implicit caching) keeps hitting.
 */
class AgentPromptBuilder(
    private val binaryResolver: BinaryResolver,
    private val imageScaler: ImageScaler,
    private val logger: NexusLogger,
) {

    /**
     * Assembles the full system prompt.
     *
     * [userPersona] arrives already composed by the repository layer (global rules -> per-chat rules
     * -> per-chat override, via [SystemPromptComposer]), so instruction precedence is decided in
     * exactly one place. The harness contract and tool rules are prepended here because they are a
     * property of the *run*, not of the conversation.
     */
    fun buildSystemPrompt(
        userPersona: String?,
        tools: List<ToolSpec>,
        options: AgentOptions,
        model: ModelInfo?,
        currentDateTime: String,
        userLocale: String,
    ): String = SystemPromptComposer.compose(
        SystemPromptComposer.Sources(
            harnessContract = harnessContract(options, tools, model, currentDateTime, userLocale),
            globalRules = emptyList(),
            conversationRules = emptyList(),
            conversationOverride = userPersona,
            toolInstructions = if (tools.isEmpty()) null else toolInstructions(tools),
        ),
    )

    /** The behavioural contract. Explicit stop conditions are what keep a ReAct loop from rambling. */
    private fun harnessContract(
        options: AgentOptions,
        tools: List<ToolSpec>,
        model: ModelInfo?,
        currentDateTime: String,
        userLocale: String,
    ): String = buildString {
        appendLine("You are Nexus, an agentic assistant running entirely on the user's Android device.")
        appendLine("Current date/time: $currentDateTime (locale $userLocale).")
        model?.let { appendLine("You are being served by `${it.displayName}`.") }
        appendLine()
        appendLine("## How you work")
        if (options.mode == AgentMode.SINGLE_SHOT) {
            appendLine("Answer directly in one response. Do not call tools.")
        } else {
            appendLine("Work in short steps: understand the request -> decide what you still need -> call a tool")
            appendLine("-> read the observation -> verify it answers the request -> answer. Repeat only while a")
            appendLine("step is genuinely needed. Stop the moment the user's request is satisfied.")
        }
        appendLine()
        if (options.mode == AgentMode.PLAN_THEN_EXECUTE) {
            appendLine("## Plan first")
            appendLine("Open with a short numbered plan inside <plan></plan> tags (max 6 items), then execute it.")
            appendLine("Revise the plan only if an observation invalidates it.")
            appendLine()
        }
        if (tools.isNotEmpty()) {
            appendLine("## Tool rules")
            appendLine("- Call tools through the native tool interface, never by describing a call in prose.")
            appendLine("- Every argument object must be valid JSON matching the tool's schema.")
            appendLine("- Never invent a tool result, a URL, a file path, a number or a quotation. If a tool")
            appendLine("  did not return it, it does not exist as far as you are concerned.")
            appendLine("- If a tool errors, read the error text, fix the arguments and try once more. If it fails")
            appendLine("  again, say plainly that it failed and continue without it.")
            appendLine("- Never repeat an identical call with identical arguments: the result will be identical.")
            appendLine("- Prefer one well-specified call over several speculative ones.")
            appendLine()
        }
        if (options.allowClarificationPauses && tools.any { it.name == ASK_USER_TOOL }) {
            appendLine("## Asking the user")
            appendLine("Use `${ASK_USER_TOOL}` only when a required detail is genuinely ambiguous, private, or")
            appendLine("unverifiable - never as a substitute for doing the work. One question, with options.")
            appendLine()
        }
        appendLine("## Answer format")
        appendLine("- GitHub-flavoured Markdown. Fenced code blocks with a language tag. Tables for comparisons.")
        appendLine("- Inline math in \$...\$, display math in \$\$...\$\$.")
        appendLine("- Cite web sources as inline markdown links on the sentence they support.")
        appendLine("- Lead with the result. No preamble, no restating the question, no filler closing.")
        appendLine()
        appendLine("## Budget")
        appendLine("At most ${options.maxSteps} reasoning steps and ${options.maxTotalToolCalls} tool calls for this")
        appendLine("request. If you approach the limit, answer with what you have and state what is missing.")
    }

    private fun toolInstructions(tools: List<ToolSpec>): String = buildString {
        appendLine("## Available tools")
        tools.forEach { spec ->
            appendLine("- `${spec.name}` - ${spec.description}")
        }
    }

    /**
     * Replays prior turns. Assistant turns that used tools are re-expanded into the assistant
     * tool-call turn plus its tool results, because that is the only shape every provider accepts.
     */
    suspend fun buildTranscript(
        history: List<Message>,
        userMessage: Message,
        includeReasoning: Boolean = false,
        extraContextPrefix: String? = null,
    ): List<ProviderMessage> {
        val result = mutableListOf<ProviderMessage>()
        extraContextPrefix?.takeIf { it.isNotBlank() }?.let {
            result += ProviderMessage(
                role = ProviderRole.USER,
                content = listOf(ProviderContentPart.Text(it)),
                name = "context",
            )
        }
        history.forEach { message -> result += toProviderMessages(message, includeReasoning) }
        result += toProviderMessages(userMessage, includeReasoning)
        return result
    }

    suspend fun toProviderMessages(message: Message, includeReasoning: Boolean = false): List<ProviderMessage> {
        val images = message.attachments.filter { it.isImage }.mapNotNull { encodeImage(it) }
        val documents = message.attachments.filter { it.isTextual || it.kind == com.nexus.aichat.core.model.AttachmentKind.PDF }
        val textParts = mutableListOf<String>()

        message.parts.forEach { part ->
            when (part) {
                is MessagePart.Text -> if (part.value.isNotBlank()) textParts += part.value
                is MessagePart.Reasoning -> if (includeReasoning && part.text.isNotBlank()) {
                    textParts += "<previous_reasoning>\n${part.text}\n</previous_reasoning>"
                }
                is MessagePart.Citation -> Unit
                is MessagePart.Image, is MessagePart.Document -> Unit   // handled via attachments
                is MessagePart.ClarificationRequest -> part.answer?.let { textParts += it }
                is MessagePart.ToolCall -> Unit                          // replayed below
            }
        }

        // Documents ride along as inlined text: the model sees the content, the UI sees a badge.
        documents.forEach { attachment ->
            val extracted = attachment.extractedText
            textParts += if (extracted.isNullOrBlank()) {
                "<document name=\"${attachment.displayName}\" note=\"extraction failed or empty\"/>"
            } else {
                "<document name=\"${attachment.displayName}\" type=\"${attachment.kind}\">\n$extracted\n</document>"
            }
        }

        val content = buildList {
            textParts.filter { it.isNotBlank() }.forEach { add(ProviderContentPart.Text(it)) }
            images.forEach { add(it) }
        }

        val role = when (message.role) {
            MessageRole.USER -> ProviderRole.USER
            MessageRole.ASSISTANT -> ProviderRole.ASSISTANT
            MessageRole.SYSTEM -> ProviderRole.SYSTEM
            MessageRole.TOOL -> ProviderRole.TOOL
        }
        val toolCalls = message.toolCalls.map {
            com.nexus.aichat.core.model.ToolCallRequest(it.callId, it.toolName, it.argumentsJson)
        }

        val assistantTurn = ProviderMessage(role = role, content = content, toolCalls = toolCalls)

        // Tool observations become their own protocol-level messages, right after the assistant turn.
        val toolResults = message.toolCalls
            .filter { it.status == com.nexus.aichat.core.model.ToolCallStatus.SUCCEEDED || it.status == com.nexus.aichat.core.model.ToolCallStatus.FAILED }
            .map { call ->
                ProviderMessage(
                    role = ProviderRole.TOOL,
                    content = listOf(
                        ProviderContentPart.ToolResultBlock(
                            callId = call.callId,
                            content = call.resultContent ?: call.resultPreview.orEmpty(),
                            isError = call.isError,
                        ),
                    ),
                    name = call.toolName,
                )
            }

        return buildList {
            add(assistantTurn)
            addAll(toolResults)
        }
    }

    private suspend fun encodeImage(attachment: Attachment): ProviderContentPart.ImageBase64? {
        val raw = imageScaler.scaleToJpeg(attachment.uri)
            ?: binaryResolver.bytesFor(attachment.uri)
            ?: run {
                logger.w(TAG, "image ${attachment.displayName} could not be read; omitting from payload")
                return null
            }
        if (raw.size > BinaryResolver.MAX_INLINE_IMAGE_BYTES) {
            logger.w(TAG, "image ${attachment.displayName} is ${raw.size} bytes; omitting")
            return null
        }
        return ProviderContentPart.ImageBase64(
            mimeType = "image/jpeg",
            base64 = Base64.getEncoder().encodeToString(raw),
        )
    }

    companion object {
        const val ASK_USER_TOOL = "ask_user"
        private const val TAG = "AgentPromptBuilder"

        val ASK_USER_SPEC = ToolSpec(
            name = ASK_USER_TOOL,
            displayName = "Ask the user",
            description = "Pause and ask the user a single clarifying question when a required detail is " +
                "ambiguous or unavailable. Returns the user's answer as text.",
            parametersJsonSchema = """
                {
                  "type": "object",
                  "properties": {
                    "question": { "type": "string", "description": "One specific question." },
                    "options": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "Optional short answers the user can tap."
                    }
                  },
                  "required": ["question"]
                }
            """.trimIndent(),
            category = com.nexus.aichat.core.model.ToolCategory.UTILITY,
            defaultApproval = com.nexus.aichat.core.model.ApprovalRequirement.AUTO,
            iconKey = "chat-circle",
            maxOutputChars = 4_000,
        )
    }
}
