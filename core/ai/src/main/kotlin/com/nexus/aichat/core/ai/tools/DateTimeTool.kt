package com.nexus.aichat.core.ai.tools

import com.nexus.aichat.core.ai.agent.SafeAgentTool
import com.nexus.aichat.core.ai.agent.ToolArgs
import com.nexus.aichat.core.ai.agent.ToolContext
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Anchor for the model's sense of "now". Small but high-leverage: without it, models confidently
 * reason about "last week" using their training cut-off.
 */
class DateTimeTool : SafeAgentTool() {

    override val spec = ToolSpec(
        name = "get_datetime",
        displayName = "Current date and time",
        description = "Get the current date and time, optionally in another timezone, plus a relative " +
            "offset (e.g. 7 days ago). Use it before answering anything time-sensitive.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "timezone": { "type": "string", "description": "IANA zone, e.g. Europe/London. Defaults to the device zone." },
                "offset_days": { "type": "integer", "description": "Shift the result by N days (negative for the past)." }
              }
            }
        """.trimIndent(),
        category = ToolCategory.UTILITY,
        defaultApproval = ApprovalRequirement.AUTO,
        iconKey = "clock",
        maxOutputChars = 2_000,
    )

    override suspend fun executeChecked(arguments: JsonObject, context: ToolContext, startedAtMs: Long): ToolResult {
        val zoneId = ToolArgs.string(arguments, "timezone")
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
        val offsetDays = ToolArgs.int(arguments, "offset_days", 0).toLong()

        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(context.timeProvider.nowEpochMillis()), zoneId)
            .plus(offsetDays, ChronoUnit.DAYS)

        val content = buildString {
            appendLine("<current_datetime>")
            appendLine("iso: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            appendLine("human: ${now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm"))} (${now.zone.id})")
            appendLine("utc: ${now.withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_INSTANT)}")
            appendLine("weekday: ${now.dayOfWeek}, day_of_year: ${now.dayOfYear}, week: ${now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}")
            appendLine("unix_ms: ${now.toInstant().toEpochMilli()}")
            if (offsetDays != 0L) appendLine("offset_applied_days: $offsetDays")
            append("</current_datetime>")
        }

        return ToolResult(
            callId = "get_datetime",
            toolName = spec.name,
            content = content,
            preview = now.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")),
            durationMs = context.timeProvider.elapsedMillis() - startedAtMs,
        )
    }
}
