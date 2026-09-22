package com.nexus.aichat.core.ai.tools

import com.nexus.aichat.core.ai.agent.SafeAgentTool
import com.nexus.aichat.core.ai.agent.ToolArgs
import com.nexus.aichat.core.ai.agent.ToolContext
import com.nexus.aichat.core.ai.spi.AttachmentProvider
import com.nexus.aichat.core.ai.spi.DocumentTextExtractor
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * Reads the documents the user attached (PDF, txt, md, json, csv) - and, crucially, reads them
 * *selectively*.
 *
 * Dumping a 200-page PDF into the context window is both expensive and unhelpful. This tool exposes
 * three modes so the agent can behave like a person with a document rather than a pipe:
 *  - `outline`  - size, page count and section headings (cheap reconnaissance);
 *  - `search`   - context windows around matches for a query (targeted evidence);
 *  - `read`     - a paged slice with line numbers (when the model needs the exact text).
 */
class DocumentReaderTool(
    private val attachments: AttachmentProvider,
    private val extractor: DocumentTextExtractor,
) : SafeAgentTool() {

    override val spec = ToolSpec(
        name = "read_document",
        displayName = "Read an attached document",
        description = "Read text from a file the user attached to this conversation (PDF, txt, md, json, csv). " +
            "Use mode=outline first, then mode=search to find the relevant passage, then mode=read for exact text.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "attachment_id": { "type": "string", "description": "Attachment id (preferred)." },
                "name": { "type": "string", "description": "Partial filename match if the id is unknown." },
                "mode": {
                  "type": "string",
                  "enum": ["outline", "search", "read"],
                  "description": "outline = structure and size, search = find a query, read = exact lines."
                },
                "query": { "type": "string", "description": "Required for mode=search." },
                "offset": { "type": "integer", "description": "For mode=read: character offset to start at. Default 0." },
                "limit": { "type": "integer", "description": "For mode=read: characters to return. Default 6000, max 30000." },
                "max_hits": { "type": "integer", "description": "For mode=search: number of matches. Default 6, max 20." }
              }
            }
        """.trimIndent(),
        category = ToolCategory.DOCUMENT,
        requiresNetwork = false,
        defaultApproval = ApprovalRequirement.AUTO,
        iconKey = "file-text",
        maxOutputChars = 32_000,
    )

    override suspend fun executeChecked(arguments: JsonObject, context: ToolContext, startedAtMs: Long): ToolResult {
        val attachment = resolveAttachment(arguments, context)
            ?: return ToolResult.error(
                callId = ToolArgs.string(arguments, "attachment_id") ?: "read_document",
                toolName = spec.name,
                message = "No matching attachment. Available in this chat: " +
                    attachments.attachmentsForConversation(context.conversationId)
                        .joinToString { "${it.displayName} (${it.id})" }
                        .ifBlank { "none" },
            )

        val text = attachment.extractedText ?: run {
            context.reportProgress("Extracting text from ${attachment.displayName}...")
            val extraction = extractor.extract(attachment)
            attachments.cacheExtraction(attachment.id, extraction.text, extraction.pageCount, extraction.warning)
            extraction.text
        }

        if (text.isBlank()) {
            return ToolResult.error(
                attachment.id,
                spec.name,
                "No text could be extracted from `${attachment.displayName}`. It may be a scanned image; " +
                    "attach it as an image instead so a vision model can read it.",
            )
        }

        val mode = ToolArgs.string(arguments, "mode") ?: "outline"
        val body = when (mode) {
            "search" -> searchMode(text, arguments, attachment)
            "read" -> readMode(text, arguments, attachment)
            else -> outlineMode(text, attachment)
        }
        val (clamped, truncated) = ToolArgs.clamp(body, spec.maxOutputChars)

        return ToolResult(
            callId = attachment.id,
            toolName = spec.name,
            content = clamped,
            preview = "${attachment.displayName}: ${body.take(120).replace('\n', ' ')}",
            durationMs = context.timeProvider.elapsedMillis() - startedAtMs,
            truncated = truncated,
        )
    }

    private suspend fun resolveAttachment(arguments: JsonObject, context: ToolContext): Attachment? {
        Tools.stringOrNull(arguments, "attachment_id")?.let { id ->
            attachments.attachmentById(id)?.let { return it }
        }
        val cached = attachments.attachmentsForConversation(context.conversationId)
        val nameQuery = Tools.stringOrNull(arguments, "name")?.lowercase()
        if (nameQuery != null) {
            cached.firstOrNull { it.displayName.lowercase().contains(nameQuery) }?.let { return it }
        }
        // Single-document conversations are unambiguous: let the model omit both identifiers.
        return cached.singleOrNull()
    }

    private fun outlineMode(text: String, attachment: Attachment): String = buildString {
        appendLine("<document_outline id=\"${attachment.id}\" name=\"${attachment.displayName}\">")
        appendLine("kind: ${attachment.kind}")
        appendLine("size: ${attachment.humanSize}${attachment.pageCount?.let { ", pages: $it" } ?: ""}")
        appendLine("characters: ${text.length}, lines: ${text.lineSequence().count()}, approx_tokens: ${text.length / 4}")
        appendLine()
        appendLine("headings / first lines:")
        text.lineSequence()
            .filter { it.isNotBlank() }
            .filter { line ->
                val trimmed = line.trim()
                trimmed.startsWith("#") ||
                    (trimmed.length in 3..90 && trimmed == trimmed.uppercase() && trimmed.any { it.isLetter() }) ||
                    trimmed.matches(Regex("""^\d+(\.\d+)*\s+\S.*"""))
            }
            .take(40)
            .forEach { appendLine("  ${it.trim()}") }
        appendLine()
        appendLine("preview (first 600 chars):")
        appendLine(text.take(600))
        append("</document_outline>")
    }

    private fun searchMode(text: String, arguments: JsonObject, attachment: Attachment): String {
        val query = Tools.stringOrNull(arguments, "query")
            ?: return ToolResult.error(attachment.id, spec.name, "mode=search requires 'query'.").content
        val maxHits = ToolArgs.int(arguments, "max_hits", 6).coerceIn(1, 20)
        val window = 320
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()

        val hits = mutableListOf<Pair<Int, String>>()
        var cursor = 0
        while (hits.size < maxHits) {
            val found = lowerText.indexOf(lowerQuery, cursor)
            if (found < 0) break
            val start = (found - window).coerceAtLeast(0)
            val end = (found + query.length + window).coerceAtMost(text.length)
            hits += found to text.substring(start, end).replace(Regex("\\s+"), " ").trim()
            cursor = found + query.length
        }

        return buildString {
            appendLine("<document_search name=\"${attachment.displayName}\" query=\"$query\" hits=\"${hits.size}\">")
            if (hits.isEmpty()) {
                appendLine("No matches. Try a shorter or differently-worded query, or read the outline.")
            } else {
                hits.forEachIndexed { index, (position, snippet) ->
                    appendLine("[${index + 1}] at char $position: ...$snippet...")
                    appendLine()
                }
            }
            append("</document_search>")
        }
    }

    private fun readMode(text: String, arguments: JsonObject, attachment: Attachment): String {
        val offset = ToolArgs.int(arguments, "offset", 0).coerceIn(0, text.length)
        val limit = ToolArgs.int(arguments, "limit", 6_000).coerceIn(500, 30_000)
        val slice = text.substring(offset, (offset + limit).coerceAtMost(text.length))
        val firstLineNumber = text.take(offset).count { it == '\n' } + 1
        val numbered = slice.lineSequence()
            .mapIndexed { index, line -> "${firstLineNumber + index}: $line" }
            .joinToString("\n")
        return buildString {
            appendLine("<document_read name=\"${attachment.displayName}\" offset=\"$offset\" limit=\"$limit\" total=\"${text.length}\">")
            appendLine(numbered)
            append("</document_read>")
        }
    }

    private object Tools {
        fun stringOrNull(arguments: JsonObject, key: String): String? =
            (arguments[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }
}
