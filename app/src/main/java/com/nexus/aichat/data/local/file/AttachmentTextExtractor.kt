package com.nexus.aichat.data.local.file

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.nexus.aichat.core.ai.spi.BinaryResolver
import com.nexus.aichat.core.ai.spi.DocumentTextExtractor
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.AttachmentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Text extraction for attached documents.
 *
 * - text formats are read directly (no parsing, no surprises);
 * - PDFs go through PDFBox-Android, one page at a time so a 400-page document cannot OOM a chat;
 * - images report a warning telling the user (and the agent) to attach them for a vision model
 *   instead - silently returning "" would look like a bug to both.
 */
class AttachmentTextExtractor @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val binaryResolver: BinaryResolver,
    private val logger: NexusLogger,
) : DocumentTextExtractor {

    override suspend fun extract(attachment: Attachment, maxChars: Int): DocumentTextExtractor.Extraction =
        withContext(Dispatchers.IO) {
            when {
                attachment.isTextual || attachment.mimeType?.startsWith("text/") == true ->
                    extractPlainText(attachment, maxChars)

                attachment.kind == AttachmentKind.PDF || attachment.mimeType == "application/pdf" ->
                    extractPdf(attachment, maxChars)

                attachment.isImage -> DocumentTextExtractor.Extraction(
                    text = "",
                    warning = "This is an image. Attach it as an image so a vision-capable model can read it.",
                )

                else -> DocumentTextExtractor.Extraction(
                    text = "",
                    warning = "Unsupported file type (${attachment.mimeType ?: "unknown"}). Supported: PDF, txt, md, json, csv and images.",
                )
            }
        }

    private suspend fun extractPlainText(attachment: Attachment, maxChars: Int): DocumentTextExtractor.Extraction {
        val bytes = runCatching { binaryResolver.bytesFor(attachment.uri) }.getOrNull()
            ?: return DocumentTextExtractor.Extraction("", warning = "Could not read `${attachment.displayName}`.")
        val text = bytes.toString(Charsets.UTF_8)
        val truncated = text.length > maxChars
        return DocumentTextExtractor.Extraction(
            text = if (truncated) text.take(maxChars) else text,
            wasTruncated = truncated,
            warning = if (truncated) "Document truncated to $maxChars characters." else null,
        )
    }

    private fun extractPdf(attachment: Attachment, maxChars: Int): DocumentTextExtractor.Extraction {
        return try {
            // Resources (glyph maps, CMaps) must be initialised once per process.
            PDFBoxResourceLoader.init(context.applicationContext)

            context.contentResolver.openInputStream(android.net.Uri.parse(attachment.uri))?.use { stream ->
                PDDocument.load(stream).use { document ->
                    val pageCount = document.numberOfPages
                    val stripper = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = 1
                        endPage = pageCount
                    }
                    val full = stripper.getText(document)
                    val truncated = full.length > maxChars
                    DocumentTextExtractor.Extraction(
                        text = if (truncated) full.take(maxChars) else full,
                        pageCount = pageCount,
                        wasTruncated = truncated,
                        warning = when {
                            truncated -> "PDF truncated to $maxChars characters (of ${full.length})."
                            full.isBlank() -> "This PDF appears to be scanned images; no text layer found."
                            else -> null
                        },
                    )
                }
            } ?: DocumentTextExtractor.Extraction("", warning = "Could not open `${attachment.displayName}`.")
        } catch (t: Throwable) {
            logger.e(TAG, "PDF extraction failed for ${attachment.displayName}", t)
            DocumentTextExtractor.Extraction("", warning = "PDF parsing failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "PdfExtractor"
    }
}
