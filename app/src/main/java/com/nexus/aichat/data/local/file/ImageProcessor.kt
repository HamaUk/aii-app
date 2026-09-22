package com.nexus.aichat.data.local.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import com.nexus.aichat.core.ai.spi.ImageScaler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Normalises photos before they are sent to a vision model.
 *
 * Why this exists: a modern phone photo is 3-6 MB; base64 inflates that by ~33%; several providers
 * cap inline images at 5 MB and *all* of them charge vision tokens by resolution. Downscaling to
 * 1536px on the long edge and re-encoding at quality 85 cuts the payload by ~90% with no measurable
 * loss of model accuracy, and it keeps a two-photo message inside every provider's limit.
 */
class ImageProcessor @javax.inject.Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : ImageScaler {

    override suspend fun scaleToJpeg(uri: String, maxEdgePx: Int, quality: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val parsed = Uri.parse(uri)
                val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // ImageDecoder honours EXIF orientation for free, which saves a rotated-photo bug.
                    val source = ImageDecoder.createSource(context.contentResolver, parsed)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        decoder.isMutableRequired = false
                        val (width, height) = info.size.width to info.size.height
                        val longest = max(width, height)
                        if (longest > maxEdgePx) {
                            val ratio = maxEdgePx.toFloat() / longest
                            decoder.setTargetSize((width * ratio).toInt(), (height * ratio).toInt())
                        }
                    }
                } else {
                    decodeLegacy(parsed, maxEdgePx)
                } ?: return@runCatching null

                ByteArrayOutputStream().use { output ->
                    decoded.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    decoded.recycle()
                    output.toByteArray()
                }
            }.getOrNull()
        }

    private fun decodeLegacy(uri: Uri, maxEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sampleSize = 1
        while (longest / sampleSize > maxEdgePx * 2) sampleSize *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null

        val currentLongest = max(bitmap.width, bitmap.height)
        if (currentLongest <= maxEdgePx) return bitmap
        val ratio = maxEdgePx.toFloat() / currentLongest
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    /** Helper for the preview layer: a data-URI the Compose image loader can consume directly. */
    fun toDataUri(bytes: ByteArray, mimeType: String = "image/jpeg"): String =
        "data:$mimeType;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}
