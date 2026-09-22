package com.nexus.aichat.core.util

/**
 * App-wide constants that are *not* domain vocabulary (those live in `:core:model`).
 *
 * Keeping them here means a build-config change (timeout, vault key, cap) is a one-line diff with an
 * obvious blast radius, instead of a magic number buried in a repository.
 */
object Constants {

    /** Vault keys for secrets held in [com.nexus.aichat.core.security.SecureKeyStore]. */
    object Vault {
        const val BRAVE_SEARCH = "search.brave"
        const val TAVILY_SEARCH = "search.tavily"
        const val SERPER_SEARCH = "search.serper"
        const val HTTP_PROXY_AUTH = "net.proxy"
    }

    object Limits {
        /** Inline base64 images above this are downscaled/rejected before hitting a provider. */
        const val MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024
        /** Vision models gain nothing above ~1536px on the long edge; base64 cost does grow. */
        const val IMAGE_MAX_EDGE_PX = 1536
        const val IMAGE_JPEG_QUALITY = 85
        /** Composer grows to this many lines, then scrolls. */
        const val COMPOSER_MAX_LINES = 6
        /** Hard ceiling on how much tool output is folded back into the context window. */
        const val TOOL_OUTPUT_MAX_CHARS = 32_000
        const val DOCUMENT_MAX_CHARS = 240_000
        /** Attachments per message. */
        const val MAX_ATTACHMENTS_PER_MESSAGE = 8
    }

    object Timeouts {
        const val CONNECT_MS = 20_000L
        const val SOCKET_MS = 120_000L
        /** Infinite (0) for streams: reasoning models can be silent for a minute before token one. */
        const val STREAM_READ_MS = 0L
        const val BUFFERED_READ_MS = 120_000L
        const val MODEL_DISCOVERY_MS = 30_000L
        const val PING_MS = 45_000L
    }

    object History {
        /** Messages replayed into a provider call. Older turns are summarised or dropped. */
        const val CONTEXT_MESSAGE_WINDOW = 60
        const val CONTEXT_CHAR_BUDGET = 240_000
    }

    object Telemetry {
        const val USAGE_RETENTION_DAYS = 180L
    }
}
