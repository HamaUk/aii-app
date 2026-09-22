package com.nexus.aichat.domain.model

import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.model.ToolCallRequest as CoreToolCallRequest
import com.nexus.aichat.core.model.ToolCallStatus
import com.nexus.aichat.core.model.ToolDescriptor as CoreToolDescriptor
import com.nexus.aichat.core.model.ToolResult as CoreToolResult
import com.nexus.aichat.core.model.ToolSpec as CoreToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

typealias ToolCallRequest = CoreToolCallRequest
typealias ToolResult = CoreToolResult
typealias ToolSpec = CoreToolSpec
typealias ToolDescriptor = CoreToolDescriptor

/**
 * Presentation helpers for tool milestones.
 *
 * Tool *contracts* live in `:core:model` and tool *implementations* in `:core:ai`; this file only
 * decides how a milestone reads to a human, which is why it is allowed to know about JSON at all.
 */
object ToolPresentation {

    fun statusLabel(status: ToolCallStatus): String = when (status) {
        ToolCallStatus.REQUESTED -> "Queued"
        ToolCallStatus.AWAITING_APPROVAL -> "Waiting for you"
        ToolCallStatus.RUNNING -> "Running"
        ToolCallStatus.SUCCEEDED -> "Done"
        ToolCallStatus.FAILED -> "Failed"
        ToolCallStatus.REJECTED -> "Skipped"
    }

    /** Renders `{"url":"https://…","max_chars":20000}` as `url=https://… · max_chars=20000`. */
    fun prettyArguments(argumentsJson: String, maxChars: Int = 140): String {
        val rendered = runCatching {
            val obj = NexusJson.instance.parseToJsonElement(argumentsJson) as? JsonObject
                ?: return@runCatching argumentsJson
            obj.entries.take(3).joinToString(" · ") { (key, value) ->
                val content = (value as? JsonPrimitive)?.content ?: value.toString()
                "$key=${content.take(60)}"
            }
        }.getOrDefault(argumentsJson)
        return rendered.take(maxChars)
    }

    /** Whether the milestone card should offer "view full output". */
    fun isInspectable(descriptor: ToolDescriptor): Boolean =
        descriptor.category.name in setOf("WEB", "DOCUMENT")

    /** Search, fetch and document tools produce citations worth surfacing under the answer. */
    fun producesCitations(toolName: String): Boolean =
        toolName in setOf("web_fetch", "web_search", "read_document")
}
