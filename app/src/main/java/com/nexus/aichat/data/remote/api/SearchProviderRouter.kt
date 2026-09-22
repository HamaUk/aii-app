package com.nexus.aichat.data.remote.api

import com.nexus.aichat.core.ai.spi.SecretProvider
import com.nexus.aichat.core.security.SecureKeyStore
import com.nexus.aichat.core.ai.spi.WebSearchProvider
import com.nexus.aichat.core.ai.transport.ChatTransport
import com.nexus.aichat.core.ai.transport.HttpRequestSpec
import com.nexus.aichat.core.ai.transport.HttpVerb
import com.nexus.aichat.core.ai.util.NexusJson
import com.nexus.aichat.core.ai.util.JsonX
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Search backends.
 *
 * Nexus has no server, so search goes straight from the device to whichever provider the user has a
 * key for. The router picks the first configured backend and degrades to
 * [com.nexus.aichat.core.ai.spi.UnconfiguredSearchProvider] otherwise, which produces an actionable message
 * in the agent's context instead of a silent no-op.
 */
class SearchProviderRouter @javax.inject.Inject constructor(
    private val transport: ChatTransport,
    private val secrets: SecretProvider,
) : WebSearchProvider {

    private val _active = MutableStateFlow<String?>(null)

    /** Null when no backend is configured; the settings screen shows this state. */
    val activeProvider: StateFlow<String?> = _active.asStateFlow()

    override val id: String get() = "auto"
    override val displayName: String get() = "Automatic (first configured backend)"
    override val requiresApiKey: Boolean get() = false

    /** First configured backend wins. Both providers are direct device-to-API, no proxy. */
    suspend fun resolve(): WebSearchProvider = when {
        secrets.hasSecret(SecureKeyStore.KEY_BRAVE_SEARCH) -> BraveSearchProvider(transport, secrets)
            .also { _active.value = it.id }
        secrets.hasSecret(SecureKeyStore.KEY_TAVILY_SEARCH) -> TavilySearchProvider(transport, secrets)
            .also { _active.value = it.id }
        else -> com.nexus.aichat.core.ai.spi.UnconfiguredSearchProvider.also { _active.value = null }
    }

    override suspend fun search(query: String, maxResults: Int, freshnessDays: Int?): WebSearchProvider.Response =
        resolve().search(query, maxResults, freshnessDays)
}

/** Brave Search API: GET /res/v1/web/search with an X-Subscription-Token header. */
class BraveSearchProvider(
    private val transport: ChatTransport,
    private val secrets: SecretProvider,
) : WebSearchProvider {

    override val id = "brave"
    override val displayName = "Brave Search"
    override val requiresApiKey = true

    override suspend fun search(query: String, maxResults: Int, freshnessDays: Int?): WebSearchProvider.Response {
        val apiKey = secrets.secret(SecureKeyStore.KEY_BRAVE_SEARCH)
            ?: throw WebSearchProvider.NotConfigured("Brave Search key missing. Add it in Settings > Tools.")

        val url = buildString {
            append("https://api.search.brave.com/res/v1/web/search?q=")
            append(java.net.URLEncoder.encode(query, "UTF-8"))
            append("&count=").append(maxResults.coerceIn(1, 20))
            append("&safesearch=moderate")
            freshnessDays?.let { append("&freshness=").append("${it}d") }
        }

        val response = transport.execute(
            HttpRequestSpec(
                verb = HttpVerb.GET,
                url = url,
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-Subscription-Token" to apiKey,
                ),
                label = "search.brave",
            ),
            readTimeoutMs = 20_000,
        )
        if (!response.isSuccess) {
            throw IllegalStateException("Brave Search returned HTTP ${response.status}")
        }

        val root = runCatching { NexusJson.instance.parseToJsonElement(response.body) }.getOrNull()
        val results = JsonX.array(JsonX.obj(JsonX.obj(root)?.get("web"))?.get("results")).orEmpty().mapNotNull { element ->
            val url = JsonX.str(element, "url") ?: return@mapNotNull null
            WebSearchProvider.Result(
                title = JsonX.str(element, "title") ?: url,
                url = url,
                snippet = JsonX.str(element, "description"),
                publishedAt = JsonX.str(element, "age"),
            )
        }
        return WebSearchProvider.Response(results = results.take(maxResults), query = query, provider = displayName)
    }
}

/** Tavily: POST /search with the key in the JSON body; returns an LLM-ready `answer` too. */
class TavilySearchProvider(
    private val transport: ChatTransport,
    private val secrets: SecretProvider,
) : WebSearchProvider {

    override val id = "tavily"
    override val displayName = "Tavily"
    override val requiresApiKey = true

    override suspend fun search(query: String, maxResults: Int, freshnessDays: Int?): WebSearchProvider.Response {
        val apiKey = secrets.secret(SecureKeyStore.KEY_TAVILY_SEARCH)
            ?: throw WebSearchProvider.NotConfigured("Tavily key missing. Add it in Settings > Tools.")

        val body = buildString {
            append("{\"api_key\":\"").append(apiKey.replace("\"", "")).append("\",")
            append("\"query\":\"").append(query.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",")
            append("\"search_depth\":\"basic\",")
            append("\"max_results\":").append(maxResults.coerceIn(1, 20))
            freshnessDays?.let { append(",\"days\":").append(it) }
            append("}")
        }

        val response = transport.execute(
            HttpRequestSpec(
                verb = HttpVerb.POST,
                url = "https://api.tavily.com/search",
                headers = mapOf("Accept" to "application/json"),
                body = body,
                label = "search.tavily",
            ),
            readTimeoutMs = 25_000,
        )
        if (!response.isSuccess) {
            throw IllegalStateException("Tavily returned HTTP ${response.status}")
        }

        val root = runCatching { NexusJson.instance.parseToJsonElement(response.body) }.getOrNull()
        val results = JsonX.array(JsonX.obj(root)?.get("results")).orEmpty().mapNotNull { element ->
            val url = JsonX.str(element, "url") ?: return@mapNotNull null
            WebSearchProvider.Result(
                title = JsonX.str(element, "title") ?: url,
                url = url,
                snippet = JsonX.str(element, "content"),
                publishedAt = JsonX.str(element, "published_date"),
            )
        }
        return WebSearchProvider.Response(results = results.take(maxResults), query = query, provider = displayName)
    }
}
