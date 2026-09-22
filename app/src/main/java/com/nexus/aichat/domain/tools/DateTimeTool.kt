package com.nexus.aichat.domain.tools

import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.tools.DateTimeTool as CoreDateTimeTool

/**
 * Factory for the `get_datetime` plugin - the anchor that stops a model reasoning about "last week"
 * using its training cut-off.
 */
object DateTimeTool {
    fun create(): AgentTool = CoreDateTimeTool()
}
