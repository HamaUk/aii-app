package com.nexus.aichat.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * Typography.
 *
 * Deliberate choices:
 *  - **Sans-serif for chrome, monospace for code.** The system sans (Roboto / Google Sans on Pixel)
 *    is what makes the app feel native rather than themed; nothing is bundled, so there is no
 *    startup font-loading cost and no licensing surface.
 *  - **Generous line height on body styles.** Chat covers long-form prose; 1.45-1.5x leading is the
 *    difference between "reads like a doc" and "reads like a form".
 *  - **`LineHeightStyle.Trim`** removes the extra first/last-line padding that otherwise makes
 *    message bubbles look vertically off-centre on Android.
 */
object NexusTypography {

    private val trimBoth = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    )

    val Default: Typography = Typography().let { base ->
        base.copy(
            displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
            headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
            bodyLarge = base.bodyLarge.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp,
                lineHeightStyle = trimBoth,
            ),
            bodyMedium = base.bodyMedium.copy(
                fontSize = 14.5.sp,
                lineHeight = 21.sp,
                lineHeightStyle = trimBoth,
            ),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
            labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp),
        )
    }

    /** Code blocks, token counters, latency chips, JSON previews. */
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp,
    )

    /** User bubbles right-align their text; the symmetry matters more than it sounds. */
    val UserBubbleAlign = TextAlign.Start
}
