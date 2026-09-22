package com.nexus.aichat.core.util

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Small, dependency-free helpers used across the UI and data layers.
 * Anything longer than a few lines belongs in a dedicated file, not here.
 */

// --- formatting -------------------------------------------------------------------------------

/** "1.4 MB" / "812 KB" - never a raw byte count in a UI string. */
fun Long.toHumanBytes(): String = when {
    this >= 1_073_741_824 -> "%.1f GB".format(this / 1_073_741_824.0)
    this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
    this >= 1_024 -> "%d KB".format(this / 1_024)
    else -> "$this B"
}

/** "128k" / "1M" - context windows are read, not measured. */
fun Int.toContextLabel(): String = when {
    this >= 1_000_000 -> "${this / 1_000_000}M"
    this >= 1_000 -> "${this / 1_000}k"
    else -> toString()
}

/** Compact relative time for the conversation drawer: "now", "14m", "3h", "6d", "12 Mar". */
fun Long.toRelativeTime(nowMs: Long = System.currentTimeMillis()): String {
    val delta = nowMs - this
    val minutes = delta / 60_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1_440 -> "${minutes / 60}h"
        minutes < 10_080 -> "${minutes / 1_440}d"
        else -> Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
    }
}

/** Clock time for a message footer: "14:32". */
fun Long.toClockTime(zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(this).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

/** Truncates on a word boundary - used for conversation titles and tool previews. */
fun String.truncateWords(maxChars: Int): String {
    if (length <= maxChars) return this
    val cut = lastIndexOf(' ', maxChars.coerceAtMost(length - 1)).takeIf { it > maxChars / 2 } ?: maxChars
    return take(cut).trimEnd() + "\u2026"
}

/** Derives a conversation title from the first user message when the model has not named it. */
fun String.toConversationTitle(maxChars: Int = 48): String =
    trim().replace(Regex("\\s+"), " ").truncateWords(maxChars).ifBlank { "New chat" }

// --- android -----------------------------------------------------------------------------------

/** Display name for any content URI, with a safe fallback for providers that expose nothing. */
fun Context.displayNameOf(uri: Uri): String =
    runCatching {
        var name: String? = null
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) }
        name
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"

// --- compose -----------------------------------------------------------------------------------

/**
 * Lifecycle-aware flow collection with an explicit initial value, so screens never render a
 * half-initialised state on first frame.
 */
@Composable
fun <T> Flow<T>.collectAsStateSafe(initial: T): State<T> =
    collectAsStateWithLifecycle(initialValue = initial)
