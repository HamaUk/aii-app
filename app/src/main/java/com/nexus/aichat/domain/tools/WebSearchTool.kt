package com.nexus.aichat.domain.tools

import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.spi.WebSearchProvider
import com.nexus.aichat.core.ai.tools.WebSearchTool as CoreWebSearchTool

/**
 * Factory for the `web_search` plugin.
 *
 * The provider is a router over whatever search backend the user has connected (Brave, Tavily, a
 * private gateway). With none configured, the tool returns an actionable message rather than failing
 * silently, and the agent can still fall back to `web_fetch` on a URL the user supplied.
 */
object WebSearchTool {

    fun create(searchProvider: WebSearchProvider): AgentTool = CoreWebSearchTool(searchProvider)
}
