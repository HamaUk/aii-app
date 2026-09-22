package com.nexus.aichat.core.ai.tools

import com.nexus.aichat.core.ai.agent.SafeAgentTool
import com.nexus.aichat.core.ai.agent.ToolArgs
import com.nexus.aichat.core.ai.agent.ToolContext
import com.nexus.aichat.core.model.ApprovalRequirement
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolCitation
import com.nexus.aichat.core.model.ToolResult
import com.nexus.aichat.core.model.ToolSpec
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import java.net.URI

/**
 * Fetches a URL, strips it down to readable Markdown and returns it as a citation-bearing
 * observation.
 *
 * Security posture (an on-device agent that can fetch arbitrary URLs is a real SSRF surface):
 *  - only `http`/`https` are accepted;
 *  - private / loopback / link-local hosts are refused by default, so a prompt-injected page cannot
 *    turn the agent into a LAN scanner against the user's router or a cloud metadata endpoint
 *    (`169.254.169.254`) - set [allowPrivateHosts] when the user is deliberately working with a
 *    local service;
 *  - response bodies are capped before parsing and again after conversion.
 */
class WebFetchTool(
    private val userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36 Nexus/1.0",
    private val timeoutMs: Int = 20_000,
    private val maxBytes: Int = 3_000_000,
    /**
     * Live policy hook: the app passes a lambda reading user settings, so toggling "allow local
     * network" takes effect on the next run without rebuilding the tool registry.
     */
    private val allowPrivateHosts: () -> Boolean = { false },
) : SafeAgentTool() {

    override val spec = ToolSpec(
        name = "web_fetch",
        displayName = "Read a web page",
        description = "Fetch a URL and return its readable content as Markdown. Use it whenever the " +
            "answer depends on a specific page, docs page, article, changelog or API reference. " +
            "Returns the page title, canonical URL and the main body text with links preserved.",
        parametersJsonSchema = """
            {
              "type": "object",
              "properties": {
                "url": { "type": "string", "description": "Absolute http(s) URL to read." },
                "max_chars": {
                  "type": "integer",
                  "description": "Maximum characters of Markdown to return (default 20000).",
                  "minimum": 1000,
                  "maximum": 120000
                },
                "instructions": {
                  "type": "string",
                  "description": "What you are looking for on the page. Used to note relevance; does not change the fetch."
                }
              },
              "required": ["url"]
            }
        """.trimIndent(),
        category = ToolCategory.WEB,
        requiresNetwork = true,
        defaultApproval = ApprovalRequirement.AUTO,
        iconKey = "globe",
        maxOutputChars = 30_000,
    )

    override suspend fun executeChecked(arguments: JsonObject, context: ToolContext, startedAtMs: Long): ToolResult {
        val url = ToolArgs.string(arguments, "url") ?: return ToolResult.error(
            callId = "web_fetch",
            toolName = spec.name,
            message = "Missing required argument 'url'.",
        )

        val uri = runCatching { URI(url) }.getOrNull() ?: return ToolResult.error(
            callId = url, toolName = spec.name, message = "'$url' is not a valid URL.",
        )
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            return ToolResult.error(url, spec.name, "Only http and https URLs are supported (got '${uri.scheme}').")
        }
        if (!allowPrivateHosts() && isPrivateHost(uri.host)) {
            return ToolResult.error(
                url,
                spec.name,
                "Refusing to fetch a private/loopback address (`${uri.host}`). This guard exists so a " +
                    "web page cannot make the agent probe your local network.",
            )
        }

        context.reportProgress("Fetching ${uri.host}...")

        val response = fetchFollowingGuardedRedirects(url) { hop ->
            context.reportProgress("Redirected to ${hop}...")
        }

        val contentType = response.contentType().orEmpty().lowercase()
        val raw = response.body()
        val maxChars = ToolArgs.int(arguments, "max_chars", 20_000).coerceIn(1_000, 120_000)
        val finalUri = response.url().toString()

        val markdown = when {
            contentType.contains("html") -> HtmlToMarkdown.convert(raw, finalUri, maxChars)
            contentType.contains("json") -> "```json\n${raw.take(maxChars)}\n```"
            else -> raw.take(maxChars)
        }

        val metadata = if (contentType.contains("html")) HtmlToMarkdown.metadata(raw, finalUri) else null
        val header = buildString {
            appendLine("<web_page url=\"$finalUri\">")
            metadata?.title?.let { appendLine("title: $it") }
            metadata?.siteName?.let { appendLine("site: $it") }
            metadata?.publishedAt?.let { appendLine("published: $it") }
            appendLine("content_type: ${contentType.ifBlank { "unknown" }}")
            appendLine("</web_page>")
            appendLine()
        }

        val content = header + markdown
        val (clamped, truncated) = ToolArgs.clamp(content, spec.maxOutputChars)

        return ToolResult(
            callId = url,
            toolName = spec.name,
            content = clamped,
            preview = metadata?.title?.let { "Fetched \"$it\"" } ?: "Fetched ${uri.host}",
            durationMs = context.timeProvider.elapsedMillis() - startedAtMs,
            citations = listOf(
                ToolCitation(
                    url = metadata?.canonicalUrl ?: finalUri,
                    title = metadata?.title ?: finalUri,
                    snippet = metadata?.description,
                ),
            ),
            truncated = truncated,
        )
    }

    /**
     * Follows redirects one hop at a time, re-applying the host policy to *every* hop.
     *
     * Letting jsoup follow redirects blindly is an SSRF hole: `https://evil.example/x` is a public
     * host, so the initial check passes, and a `302` to `http://169.254.169.254/latest/meta-data/` is
     * then followed without ever being inspected. Each hop is therefore validated exactly like the
     * original URL.
     */
    private suspend fun fetchFollowingGuardedRedirects(
        url: String,
        onHop: suspend (String) -> Unit,
    ): org.jsoup.Connection.Response {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            val response = Jsoup.connect(current)
                .userAgent(userAgent)
                .timeout(timeoutMs)
                .maxBodySize(maxBytes)
                .followRedirects(false)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .execute()

            val location = response.header("Location")
            if (response.statusCode() !in REDIRECT_CODES || location.isNullOrBlank()) return response

            // Relative Location headers are legal; resolve against the URL we just called.
            val next = runCatching { URI(current).resolve(location).toString() }.getOrNull()
                ?: throw WebFetchBlocked("The server redirected to an unparseable location: $location")

            val nextUri = runCatching { URI(next) }.getOrNull()
                ?: throw WebFetchBlocked("The server redirected to an invalid URL: $next")
            if (nextUri.scheme?.lowercase() !in setOf("http", "https")) {
                throw WebFetchBlocked("Refusing to follow a redirect to '${nextUri.scheme}:'.")
            }
            if (!allowPrivateHosts() && isPrivateHost(nextUri.host)) {
                throw WebFetchBlocked(
                    "Refusing to follow a redirect to a private/loopback address (`${nextUri.host}`).",
                )
            }
            onHop(next)
            current = next
        }
        throw WebFetchBlocked("Too many redirects (limit $MAX_REDIRECTS).")
    }

    /** Internal, not private, so the guard can be tested without opening a socket. */
    internal fun isPrivateHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val lower = host.lowercase().trim('[', ']')
        if (lower == "localhost" || lower.endsWith(".localhost") ||
            lower.endsWith(".local") || lower.endsWith(".internal")
        ) {
            return true
        }
        // IPv6: ::1 (loopback), fe80::/10 (link-local), fc00::/7 (unique-local).
        if (lower == "::1" || lower == "0:0:0:0:0:0:0:1") return true
        if (lower.startsWith("fe8") || lower.startsWith("fe9") || lower.startsWith("fea") || lower.startsWith("feb")) {
            return true
        }
        // The unique-local prefix is `fc00::/7`, i.e. the first *byte* is 0xfc or 0xfd - not "any
        // host whose name starts with fc", which used to flag legitimate hosts like `fcorp.example.com`.
        if (Regex("""^f[cd][0-9a-f]{0,2}:""").containsMatchIn(lower)) return true

        val parts = lower.split('.')
        if (parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) {
            val (a, b) = parts[0].toInt() to parts[1].toInt()
            return a == 127 || a == 10 || a == 0 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254) ||
                (a == 100 && b in 64..127)   // CGNAT
        }
        return false
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/** Raised when the fetch policy refuses a URL or a redirect target. Surfaced as a tool error. */
class WebFetchBlocked(message: String) : Exception(message)
