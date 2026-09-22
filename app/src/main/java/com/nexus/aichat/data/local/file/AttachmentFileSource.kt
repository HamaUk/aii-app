package com.nexus.aichat.data.local.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.nexus.aichat.core.ai.spi.BinaryResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reads attachment bytes from content://, file:// or a raw path.
 *
 * Everything goes through `ContentResolver` so SAF, the photo picker, the camera-capture FileProvider
 * and a plain file path all behave identically - the engine never needs to know which one it got.
 */
class AttachmentFileSource @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : BinaryResolver {

    override suspend fun bytesFor(uri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(uri)
            when {
                parsed.scheme == null -> File(uri).takeIf { it.exists() }?.readBytes()
                parsed.scheme == "file" -> File(parsed.path ?: return@runCatching null).takeIf { it.exists() }?.readBytes()
                else -> context.contentResolver.openInputStream(parsed)?.use { stream -> stream.readBytes() }
            }
        }.getOrNull()
    }

    override fun mimeTypeOf(uri: String): String? {
        val parsed = Uri.parse(uri)
        if (parsed.scheme != null && parsed.scheme != "file") {
            context.contentResolver.getType(parsed)?.let { return it }
        }
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    /** Display name resolution, with a graceful fallback for providers that expose nothing. */
    fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "file"
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    }

    fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) return cursor.getLong(index)
            }
        }
        -1L
    }.getOrDefault(-1L)
}
