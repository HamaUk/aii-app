package com.nexus.aichat.core.ai.spi

import com.nexus.aichat.core.model.Attachment

/**
 * Extracts plain text from an attachment: raw reads for text formats, PDFBox for PDFs, and OCR
 * (optional) for images.
 *
 * The extracted text is cached on the [Attachment] itself, so a document attached once is never
 * re-parsed - important both for battery and for the user's data plan.
 */
interface DocumentTextExtractor {

    data class Extraction(
        val text: String,
        val pageCount: Int? = null,
        val wasTruncated: Boolean = false,
        val warning: String? = null,
    )

    suspend fun extract(attachment: Attachment, maxChars: Int = DEFAULT_MAX_CHARS): Extraction

    companion object {
        /** ~60k tokens of text: a sensible default cap for a single document in a chat context. */
        const val DEFAULT_MAX_CHARS = 240_000
    }
}
