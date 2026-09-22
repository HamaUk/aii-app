package com.nexus.aichat.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.nexus.aichat.core.designsystem.theme.LocalNexusAppearance
import com.nexus.aichat.core.designsystem.theme.NexusShapes

/**
 * Bubble and card shapes.
 *
 * Bubbles are asymmetric on purpose: the corner nearest the avatar stays tight, which is what makes a
 * chat read as a conversation rather than as a list of cards. The design system decides the radii
 * (organic / rounded / sharp); this file only decides *which corner* gets the treatment.
 */
object NexusShapes {

    @Composable
    fun userBubble(): Shape = NexusShapes.userBubble(LocalNexusAppearance.current.cornerStyle)

    @Composable
    fun assistantBubble(): Shape = NexusShapes.assistantBubble(LocalNexusAppearance.current.cornerStyle)

    @Composable
    fun codeBlock(): Shape = NexusShapes.codeBlock(LocalNexusAppearance.current.cornerStyle)

    @Composable
    fun composer(): Shape = MaterialTheme.shapes.extraLarge

    /** Tool milestone card inside the thought tree. */
    @Composable
    fun toolCard(): Shape = RoundedCornerShape(14.dp)

    /** Attachment and citation chips. */
    @Composable
    fun chip(): Shape = RoundedCornerShape(999.dp)

    @Composable
    fun sheet(): Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}
