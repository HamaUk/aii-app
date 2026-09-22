package com.nexus.aichat.core.ai.tools

import com.nexus.aichat.core.ai.agent.SafeAgentTool
import com.nexus.aichat.core.ai.agent.ToolArgs
import com.nexus.aichat.core.ai.agent.ToolContext
import com.nexus.aichat.core.ai.spi.WebSearchProvider
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolCitation
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Web search through the user's own search backend (Brave, Tavily, Exa, SearxNG, a private gateway).
 *
 * Nexus ships no index and no proxy: search credentials and queries go straight from the device to
 * the provider the user chose. With nothing configured the tool returns an actionable message
 * instead of silently doing nothing, and the agent can fall back to `web_fetch` on a known URL.
 */
class WebSearchTool(
    private val searchProvider: WebSearchProvider,
) : SafeAgentTool() {

    override val spec = ToolSpec(
        name = "web_search",
        displayName = "Web search",
        description = "Search the web and return titles, URLs and snippets. Use it for current events, " +
            "version numbers, prices, documentation you do not have a URL for, or anything that " +
            "post-dates your training data. Follow up with web_fetch on the most promising result.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "Search query. Be specific and keyword-rich." },
                "max_results": { "type": "integer", "minimum": 1, "maximum": 10, "description": "Default 6." },
                "freshness_days": {
                  "type": "integer",
                  "minimum": 1,
                  "description": "Optional: only results newer than N days."
                }
              },
              "required": ["query"]
            }
        """.trimIndent(),
        category = ToolCategory.WEB,
        requiresNetwork = true,
        defaultApproval = ApprovalRequirement.AUTO,
        iconKey = "magnifying-glass",
        maxOutputChars = 12_000,
    )

    override suspend fun executeChecked(arguments: JsonObject, context: ToolContext, startedAtMs: Long): ToolResult {
        val query = ToolArgs.string(arguments, "query")
            ?: return ToolResult.error("web_search", spec.name, "Missing required argument 'query'.")
        val maxResults = ToolArgs.int(arguments, "max_results", 6).coerceIn(1, 10)
        val freshness = arguments["freshness_days"]
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }

        context.reportProgress("Searching \"$query\"...")

        val response = try {
            searchProvider.search(query, maxResults, freshness)
        } catch (notConfigured: WebSearchProvider.NotConfigured) {
            return ToolResult.error("web_search", spec.name, notConfigured.message.orEmpty())
        }

        if (response.results.isEmpty()) {
            return ToolResult(
                callId = query,
                toolName = spec.name,
                content = "<search_results query=\"$query\" provider=\"${response.provider}\">\n" +
                    "No results. Try different keywords, or ask the user for a URL.\n</search_results>",
                preview = "No results for \"$query\"",
                durationMs = context.timeProvider.elapsedMillis() - startedAtMs,
            )
        }

        val body = buildString {
            appendLine("<search_results query=\"$query\" provider=\"${response.provider}\">")
            response.results.forEachIndexed { index, result ->
                appendLine("[${index + 1}] ${result.title}")
                appendLine("    url: ${result.url}")
                result.publishedAt?.let { appendLine("    published: $it") }
                result.snippet?.let { appendLine("    snippet: ${it.replace("\n", " ")}") }
            }
            appendLine("</search_results>")
        }
        val (clamped, truncated) = ToolArgs.clamp(body, spec.maxOutputChars)

        return ToolResult(
            callId = query,
            toolName = spec.name,
            content = clamped,
            preview = "${response.results.size} results for \"$query\"",
            durationMs = context.timeProvider.elapsedMillis() - startedAtMs,
            citations = response.results.map { ToolCitation(it.url, it.title, it.snippet) },
            truncated = truncated,
        )
    }
}
