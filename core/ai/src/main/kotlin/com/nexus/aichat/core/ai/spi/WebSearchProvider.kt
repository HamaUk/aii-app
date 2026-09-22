package com.nexus.aichat.core.ai.spi

/**
 * Search backend for the `web_search` tool.
 *
 * Nexus ships no search index of its own: the user plugs in a key for Brave, Tavily, Exa, SearxNG or
 * their own gateway. With no key configured the tool degrades to `web_fetch` on URLs the user supplies.
 */
interface WebSearchProvider {

    val id: String
    val displayName: String
    val requiresApiKey: Boolean

    data class Result(
        val title: String,
        val url: String,
        val snippet: String? = null,
        val publishedAt: String? = null,
    )

    data class Response(val results: List<Result>, val query: String, val provider: String)

    suspend fun search(query: String, maxResults: Int = 6, freshnessDays: Int? = null): Response

    /** Throw to signal a misconfiguration (missing key) so the tool can explain it in-band. */
    class NotConfigured(message: String) : Exception(message)
}

/** Used when the user has not connected a search backend. */
object UnconfiguredSearchProvider : WebSearchProvider {
    override val id = "none"
    override val displayName = "Not configured"
    override val requiresApiKey = true
    override suspend fun search(query: String, maxResults: Int, freshnessDays: Int?): WebSearchProvider.Response =
        throw WebSearchProvider.NotConfigured(
            "Web search is not configured. Add a Brave/Tavily/SearxNG key in Settings > Tools, " +
                "or paste a URL and I will fetch it directly.",
        )
}
