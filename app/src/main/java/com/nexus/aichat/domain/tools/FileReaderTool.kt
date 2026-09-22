package com.nexus.aichat.domain.tools

import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.ai.spi.AttachmentProvider
import com.nexus.aichat.core.ai.spi.DocumentTextExtractor
import com.nexus.aichat.core.ai.tools.DocumentReaderTool as CoreDocumentReaderTool

/**
 * Factory for the `read_document` plugin.
 *
 * Wires the engine's chunked document reader to the app's attachment table and text extractor, so the
 * agent can page through a 200-page PDF with `outline` -> `search` -> `read` instead of dumping it
 * into the context window.
 */
object FileReaderTool {

    fun create(attachments: AttachmentProvider, extractor: DocumentTextExtractor): AgentTool =
        CoreDocumentReaderTool(attachments = attachments, extractor = extractor)
}
