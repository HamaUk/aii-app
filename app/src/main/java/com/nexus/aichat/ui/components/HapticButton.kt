package com.nexus.aichat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.nexus.aichat.core.model.AppearanceSettings

/**
 * Haptics, applied where they carry information.
 *
 * Polished chat apps feel *physical*: send, stop, approve, reject and long-press all tick. The rule
 * this file enforces is that haptics are a user preference, not an implementation detail - every entry
 * point funnels through [rememberHaptics], so switching them off in Settings actually switches them off
 * everywhere instead of in the one composable that remembered to check.
 *
 * Intensity maps to meaning: [tick] for routine taps, [thud] for destructive or terminal actions.
 */
class NexusHaptics(
    private val feedback: HapticFeedback,
    private val enabled: Boolean,
) {
    fun tick() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun commit() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun thud() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

@Composable
fun rememberHaptics(appearance: AppearanceSettings): NexusHaptics {
    val feedback = LocalHapticFeedback.current
    val enabled = appearance.hapticsEnabled && !appearance.reducedMotion
    return remember(feedback, enabled) { NexusHaptics(feedback, enabled) }
}

/** Icon button that ticks on press - the default for every primary control in the chat chrome. */
@Composable
fun HapticIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    haptics: NexusHaptics,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = LocalContentColor.current)
    }
}

/** Long-press surface used by message bubbles to raise the floating action bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HapticPressSurface(
    onLongPress: () -> Unit,
    haptics: NexusHaptics,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier.combinedClickable(
            onClick = {
                haptics.tick()
                onClick?.invoke()
            },
            onLongClick = {
                haptics.commit()
                onLongPress()
            },
        ),
    ) {
        content()
    }
}
