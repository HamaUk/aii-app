package com.nexus.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.designsystem.components.GlassSurface as CoreGlassSurface

/**
 * Glass surfaces, as the chat UI uses them.
 *
 * `:core:designsystem` owns the material - tint, lit edge, soft shadow. What this wrapper adds is the
 * *layering rule* the chat needs and a generic component cannot know: glass must sit on a gradient, or
 * over flat black the tint reads as a flat rectangle and the depth is lost. So a barely-there vertical
 * veil is painted underneath, which is what makes the top bar, the composer and the action bar look
 * like one material rather than three unrelated panels.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val veil = Brush.verticalGradient(
        listOf(
            scheme.surfaceContainer.copy(alpha = 0.45f),
            scheme.surfaceContainerLow.copy(alpha = 0.25f),
        ),
    )
    CoreGlassSurface(
        modifier = modifier.clip(shape).background(veil),
        shape = shape,
        elevation = elevation,
    ) {
        Box(Modifier.padding(contentPadding), content = content)
    }
}

/** The composer / action-bar variant: heavier tint, hairline outline, tight padding. */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit,
) = GlassSurface(
    modifier = modifier,
    shape = shape,
    elevation = 14.dp,
    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    content = content,
)

/** Opaque card for sheets and dialogs, where glass would fight the scrim behind it. */
@Composable
fun NexusCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable BoxScope.() -> Unit,
) = Box(
    modifier = modifier
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), shape)
        .padding(16.dp),
    content = content,
)
