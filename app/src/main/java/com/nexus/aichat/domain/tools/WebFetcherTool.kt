package com.nexus.aichat.domain.tools

import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.tools.WebFetchTool as CoreWebFetchTool
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.data.local.datastore.SettingsDataStore

/**
 * Factory for the `web_fetch` plugin.
 *
 * The fetch/convert/parse logic lives in `:core:ai` (device-free and unit-tested); this factory only
 * injects the app's policy. Note the lambda: `allowPrivateHosts` is read at *call* time, so flipping
 * the switch in Settings changes the very next tool call instead of requiring a restart.
 */
object WebFetcherTool {

    fun create(settings: SettingsDataStore, logger: NexusLogger): AgentTool = CoreWebFetchTool(
        userAgent = USER_AGENT,
        timeoutMs = 20_000,
        maxBytes = 3_000_000,
        allowPrivateHosts = { settings.current.tools.allowPrivateHosts },
    ).also { tool ->
        logger.d(TAG, "registered ${tool.spec.name}")
    }

    /** Identifies Nexus to servers that block unknown agents; some docs sites 403 on bare clients. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125 Mobile Safari/537.36 Nexus/1.0"

    private const val TAG = "WebFetcherTool"
}
