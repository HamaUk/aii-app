package com.nexus.aichat.domain.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Build
import androidx.compose.ui.graphics.vector.ImageVector
import com.nexus.aichat.core.ai.agent.AgentTool
import com.nexus.aichat.core.model.ToolCategory
import com.nexus.aichat.core.model.ToolDescriptor

/**
 * App-side view of a tool plugin.
 *
 * `:core:ai` owns the execution contract ([AgentTool]) and the schema sent to models. This file adds
 * what only the app can know: how a tool is presented, which settings gate it, and whether its output
 * is worth showing in the audit screen.
 */
data class ToolPlugin(
    val name: String,
    val displayName: String,
    val blurb: String,
    val category: ToolCategory,
    val icon: ImageVector,
    val requiresNetwork: Boolean,
    val isDestructive: Boolean,
) {
    companion object {
        fun from(descriptor: ToolDescriptor): ToolPlugin = ToolPlugin(
            name = descriptor.name,
            displayName = descriptor.displayName,
            blurb = descriptor.description.lineSequence().first().take(120),
            category = descriptor.category,
            icon = iconFor(descriptor.iconKey),
            requiresNetwork = descriptor.requiresNetwork,
            isDestructive = descriptor.isDestructive,
        )

        fun iconFor(iconKey: String): ImageVector = when (iconKey) {
            "globe" -> Icons.Rounded.Language
            "magnifying-glass" -> Icons.Rounded.Search
            "file-text" -> Icons.Rounded.Article
            "clock" -> Icons.Rounded.Schedule
            "image" -> Icons.Rounded.Image
            else -> Icons.Rounded.Build
        }
    }
}

/**
 * The tools the app registers, in settings-screen order.
 * Kept as a static catalog so Settings can render the list before the DI graph is exercised.
 */
object ToolCatalog {
    val ORDER = listOf("web_fetch", "web_search", "read_document", "inspect_image", "get_datetime")

    fun sorted(descriptors: List<ToolDescriptor>): List<ToolDescriptor> =
        descriptors.sortedBy { ORDER.indexOf(it.name).takeIf { index -> index >= 0 } ?: ORDER.size }
}
