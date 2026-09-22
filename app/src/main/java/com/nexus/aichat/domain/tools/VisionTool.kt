package com.nexus.aichat.domain.tools

import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.agent.ToolArgs
import com.nexus.aichat.core.ai.agent.ToolContext
import com.nexus.aichat.core.ai.spi.AttachmentProvider
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import com.nexus.aichat.core.util.toHumanBytes
import kotlinx.serialization.json.JsonObject

/**
 * `inspect_image` - what the agent can do with an attached image *when the chosen model cannot see*.
 *
 * The failure this exists to prevent: the user attaches a photo, the selected model has no vision
 * capability, and the agent either invents a description or silently ignores the attachment. This tool
 * reports what is attached (format, dimensions, size), states plainly that the current model cannot
 * read it, and names the remedy - switch to a vision model, or ask the user to describe it.
 *
 * When the model *is* vision-capable the image is already sent inline by the prompt builder, so the
 * tool answers "you can see it already", which also stops pointless re-inspection loops.
 */
class VisionTool(
    private val attachments: AttachmentProvider,
    private val modelSupportsVision: () -> Boolean,
) : AgentTool {

    override val spec = ToolSpec(
        name = "inspect_image",
        displayName = "Inspect an attached image",
        description = "Report metadata about an image attached to this conversation (format, dimensions, " +
            "size) and how to proceed if the current model cannot process images. Does not perform OCR.",
        parametersJsonSchema = SCHEMA,
        category = ToolCategory.VISION,
        requiresNetwork = false,
        defaultApproval = ApprovalRequirement.AUTO,
        iconKey = "image",
        maxOutputChars = 4_000,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val images = attachments.attachmentsForConversation(context.conversationId).filter { it.isImage }

        if (images.isEmpty()) {
            return ToolResult(
                callId = "inspect_image",
                toolName = spec.name,
                content = "<images>No images are attached to this conversation.</images>",
                preview = "No images attached",
            )
        }

        val target = ToolArgs.string(arguments, "attachment_id")
            ?.let { id -> images.firstOrNull { it.id == id } }
            ?: images.first()
        val visionCapable = modelSupportsVision()

        val body = buildString {
            appendLine("<image id=\"${target.id}\" name=\"${target.displayName}\">")
            appendLine("format: ${target.mimeType ?: "unknown"}")
            appendLine("size: ${target.sizeBytes.toHumanBytes()}")
            target.width?.let { width -> appendLine("dimensions: $width x ${target.height ?: 0}") }
            appendLine(
                if (visionCapable) {
                    "status: this model accepts images and the attachment is already inlined in the " +
                        "conversation. Reason about it directly; do not call this tool again for it."
                } else {
                    "status: THE CURRENT MODEL CANNOT READ IMAGES. Say so plainly and suggest switching " +
                        "to a vision-capable model (a Gemini, GPT-5-class or Claude Sonnet model) for this chat."
                },
            )
            if (images.size > 1) {
                appendLine("other_images: " + images.filter { it.id != target.id }.joinToString { it.displayName })
            }
            append("</image>")
        }

        return ToolResult(
            callId = target.id,
            toolName = spec.name,
            content = body,
            preview = if (visionCapable) "Model can see ${target.displayName}" else "Model cannot see images",
            artifacts = listOf(target),
        )
    }

    companion object {
        val SCHEMA = """
            {
              "type": "object",
              "properties": {
                "attachment_id": {
                  "type": "string",
                  "description": "Optional: id of a specific attached image."
                }
              }
            }
        """.trimIndent()
    }
}
