package com.nexus.aichat.core.ai.spi

/**
 * Resolves a content URI (or file path) into raw bytes so vision-capable models can be sent images
 * as base64 without the engine ever touching `ContentResolver`.
 */
interface BinaryResolver {

    suspend fun bytesFor(uri: String): ByteArray?

    /** Falls back to extension sniffing when the authority reports nothing useful. */
    fun mimeTypeOf(uri: String): String?

    companion object {
        const val MAX_INLINE_IMAGE_BYTES = 8 * 1024 * 1024
    }
}

/**
 * Downscales images before they are base64-encoded. A 12 MP phone photo is ~4 MB -> ~5.3 MB of base64,
 * which blows past several providers' inline limits and wastes context. Vision models gain nothing
 * above ~1536px on the long edge.
 */
interface ImageScaler {
    suspend fun scaleToJpeg(uri: String, maxEdgePx: Int = 1536, quality: Int = 85): ByteArray?
}
