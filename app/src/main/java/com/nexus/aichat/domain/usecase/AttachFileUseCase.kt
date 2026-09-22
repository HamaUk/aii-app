package com.nexus.aichat.domain.usecase

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.nexus.aichat.core.common.logging.NexusLogger
import com.nexus.aichat.core.common.time.TimeProvider
import com.nexus.aichat.core.model.Attachment
import com.nexus.aichat.core.model.AttachmentKind
import com.nexus.aichat.core.util.Constants
import com.nexus.aichat.data.local.file.AttachmentFileSource
import com.nexus.aichat.data.local.file.AttachmentTextExtractor
import com.nexus.aichat.data.local.file.ImageProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject

/**
 * Turns a picked URI into an [Attachment] the agent can actually use.
 *
 * The work this does that a plain `content://` string does not:
 *  - resolves the display name and size through the ContentResolver (providers lie about both);
 *  - classifies by MIME *and* extension, because `.md` is frequently reported as `text/plain` and a
 *    markdown file reads very differently from a text file;
 *  - for images, downscales once at ingest ([Constants.Limits.IMAGE_MAX_EDGE_PX]) so the base64 payload
 *    never balloons - a 12 MP phone photo is ~9 MB, which is both slow and pointless for a vision model;
 *  - for text-like files and PDFs, extracts the text **now**, so "attach and read" costs nothing at
 *    send time and a 200-page PDF is parsed on the IO thread while the user is still typing.
 *
 * Extraction failures are recorded on the attachment, never thrown: an unsupported file should attach
 * with a badge, not vanish silently.
 */
class AttachFileUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fileSource: AttachmentFileSource,
    private val imageProcessor: ImageProcessor,
    private val textExtractor: AttachmentTextExtractor,
    private val time: TimeProvider,
    private val logger: NexusLogger,
) {

    suspend operator fun invoke(uri: Uri): Attachment {
        val mime = fileSource.mimeTypeOf(uri.toString()) ?: guessMime(uri)
        val name = displayName(uri)
        val kind = classify(mime, name)
        val size = sizeOf(uri)

        val base = Attachment(
            id = UUID.randomUUID().toString(),
            kind = kind,
            uri = uri.toString(),
            displayName = name,
            mimeType = mime,
            sizeBytes = size,
            createdAtEpochMs = time.nowEpochMillis(),
        )

        return when (kind) {
            AttachmentKind.IMAGE -> enrichImage(base)
            AttachmentKind.PDF, AttachmentKind.TEXT, AttachmentKind.MARKDOWN, AttachmentKind.JSON, AttachmentKind.CSV ->
                enrichText(base)
            else -> base
        }
    }

    private suspend fun enrichImage(attachment: Attachment): Attachment {
        val scaled = imageProcessor.scaleToJpeg(
            uri = attachment.uri,
            maxEdgePx = Constants.Limits.IMAGE_MAX_EDGE_PX,
            quality = Constants.Limits.IMAGE_JPEG_QUALITY,
        )
        if (scaled == null) {
            logger.w(TAG, "could not decode ${attachment.displayName}; attaching the original URI")
            return attachment.copy(extractionError = "This image could not be decoded on this device")
        }
        return attachment.copy(sizeBytes = scaled.size.toLong())
    }

    private suspend fun enrichText(attachment: Attachment): Attachment = runCatching {
        val result = textExtractor.extract(attachment, Constants.Limits.DOCUMENT_MAX_CHARS)
        attachment.copy(
            extractedText = result.text.ifBlank { null },
            pageCount = result.pageCount,
            extractionError = result.warning,
        )
    }.getOrElse { error ->
        logger.w(TAG, "extraction failed for ${attachment.displayName}: ${error.message}")
        attachment.copy(extractionError = error.message ?: "Could not read this file")
    }

    private fun displayName(uri: Uri): String = runCatching {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        name
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"

    private fun sizeOf(uri: Uri): Long = runCatching {
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) size = cursor.getLong(0)
        }
        size
    }.getOrDefault(0L)

    private fun guessMime(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        else -> null
    }

    private fun classify(mime: String?, name: String): AttachmentKind {
        val lower = name.lowercase()
        return when {
            mime?.startsWith("image/") == true -> AttachmentKind.IMAGE
            mime == "application/pdf" || lower.endsWith(".pdf") -> AttachmentKind.PDF
            mime == "application/json" || lower.endsWith(".json") -> AttachmentKind.JSON
            mime == "text/csv" || lower.endsWith(".csv") -> AttachmentKind.CSV
            lower.endsWith(".md") || lower.endsWith(".markdown") -> AttachmentKind.MARKDOWN
            mime?.startsWith("text/") == true || lower.endsWith(".txt") -> AttachmentKind.TEXT
            mime?.startsWith("audio/") == true -> AttachmentKind.AUDIO
            else -> AttachmentKind.OTHER
        }
    }

    private companion object {
        const val TAG = "AttachFile"
    }
}
