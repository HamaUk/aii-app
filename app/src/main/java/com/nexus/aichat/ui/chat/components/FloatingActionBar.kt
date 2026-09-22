package com.nexus.aichat.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nexus.aichat.ui.components.GlassBar

/**
 * The contextual action bar for a selected message.
 *
 * Behaviour matches the apps this one is measured against: it appears on long-press, floats above the
 * composer rather than pushing the feed, and offers only actions that make sense for *that* message -
 * an assistant answer can be retried and branched, a user turn can be edited, and both can be copied or
 * shared. Offering "retry" on your own message is the kind of detail that makes an app feel assembled
 * rather than designed.
 */
@Composable
fun FloatingActionBar(
    visible: Boolean,
    isUserMessage: Boolean,
    isPinned: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + slideInVertically(tween(160)) { it / 2 },
        exit = fadeOut(tween(90)) + slideOutVertically(tween(120)) { it / 2 },
        modifier = modifier,
    ) {
        Box {
            GlassBar(shape = RoundedCornerShape(22.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ActionChip(Icons.Rounded.ContentCopy, "Copy", onCopy)
                    ActionChip(Icons.Rounded.Share, "Share", onShare)
                    if (isUserMessage) {
                        ActionChip(Icons.Rounded.Edit, "Edit", onEdit)
                    } else {
                        ActionChip(Icons.Rounded.Refresh, "Retry", onRetry)
                    }
                    ActionChip(Icons.Rounded.Star, if (isPinned) "Unpin" else "Pin", onPin)
                    TextButton(onClick = onDismiss) {
                        Text("Done", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
        modifier = Modifier.padding(2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
