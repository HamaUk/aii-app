package com.nexus.aichat.core.designsystem.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke vectors for the brand mark and for the ideas the Material set renders poorly: "reasoning",
 * "tool milestone" and "branching". Everything generic (send, copy, retry, share, settings) uses
 * `material-icons-extended`, so the icon set stays one dependency instead of five.
 *
 * Stroke-based, 24dp / 24pt viewport, tint applied by the caller - matching the Lucide/Phosphor
 * visual weight the design calls for.
 */
object NexusIcons {

    /** Interlocking double nexus: the product mark. */
    val Mark: ImageVector by lazy {
        ImageVector.Builder(
            name = "NexusMark",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(8f, 3f)
                curveTo(8f, 8f, 16f, 10f, 16f, 12f)
                curveTo(16f, 14f, 8f, 16f, 8f, 21f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(16f, 3f)
                curveTo(16f, 8f, 8f, 10f, 8f, 12f)
                curveTo(8f, 14f, 16f, 16f, 16f, 21f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(9.6f, 7.5f)
                lineTo(14.4f, 7.5f)
                moveTo(9.6f, 16.5f)
                lineTo(14.4f, 16.5f)
            }
        }.build()
    }

    /** Reasoning / thinking token stream. */
    val Reasoning: ImageVector by lazy {
        ImageVector.Builder(
            name = "NexusReasoning",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 3.5f)
                curveTo(8.4f, 3.5f, 5.8f, 6f, 5.8f, 9.2f)
                curveTo(5.8f, 11f, 6.6f, 12.2f, 7.4f, 13.2f)
                curveTo(7.9f, 13.9f, 8.2f, 14.6f, 8.2f, 15.4f)
                lineTo(8.2f, 16.5f)
                lineTo(15.8f, 16.5f)
                lineTo(15.8f, 15.4f)
                curveTo(15.8f, 14.6f, 16.1f, 13.9f, 16.6f, 13.2f)
                curveTo(17.4f, 12.2f, 18.2f, 11f, 18.2f, 9.2f)
                curveTo(18.2f, 6f, 15.6f, 3.5f, 12f, 3.5f)
                close()
                moveTo(9.6f, 19.2f)
                lineTo(14.4f, 19.2f)
                moveTo(10.6f, 21f)
                lineTo(13.4f, 21f)
            }
        }.build()
    }

    /** A tool invocation milestone in the thought tree. */
    val ToolNode: ImageVector by lazy {
        ImageVector.Builder(
            name = "NexusToolNode",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(5f, 7.5f)
                lineTo(19f, 7.5f)
                moveTo(5f, 12f)
                lineTo(15.5f, 12f)
                moveTo(5f, 16.5f)
                lineTo(12f, 16.5f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(18.2f, 14.2f)
                lineTo(18.2f, 19.2f)
                moveTo(15.7f, 16.7f)
                lineTo(20.7f, 16.7f)
            }
        }.build()
    }

    /** Branch / regenerate-as-sibling affordance on the message action bar. */
    val Branch: ImageVector by lazy {
        ImageVector.Builder(
            name = "NexusBranch",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(7f, 4.5f)
                lineTo(7f, 15f)
                curveTo(7f, 16.7f, 8.3f, 18f, 10f, 18f)
                lineTo(17f, 18f)
                moveTo(14.5f, 15.5f)
                lineTo(17.2f, 18f)
                lineTo(14.5f, 20.5f)
                moveTo(12f, 4.5f)
                lineTo(12f, 13f)
            }
        }.build()
    }
}
