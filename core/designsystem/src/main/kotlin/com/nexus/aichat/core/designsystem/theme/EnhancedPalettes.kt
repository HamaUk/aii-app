package com.nexus.aichat.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.nexus.aichat.core.model.ThemePreset

/**
 * Enhanced palette system with improved color harmony and accessibility.
 *
 * Each palette has been refined for:
 * - Better WCAG contrast ratios (AA/AAA compliance)
 * - Improved readability on AMOLED displays
 * - Smoother gradients and transitions
 * - Enhanced visual depth and hierarchy
 */
object EnhancedNexusPalettes {

    // --- AMOLED Pure Black (Enhanced) -----------------------------------------------------------
    val amoledDarkEnhanced = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF062018),
        primaryContainer = Color(0xFF1A3A32),
        onPrimaryContainer = Color(0xFFB9F0DC),
        secondary = Color(0xFF7FD1B9),
        onSecondary = Color(0xFF04201A),
        secondaryContainer = Color(0xFF142822),
        onSecondaryContainer = Color(0xFF9FE3CE),
        tertiary = Color(0xFFC6C0FF),
        onTertiary = Color(0xFF1B1440),
        tertiaryContainer = Color(0xFF2D2050),
        onTertiaryContainer = Color(0xFFE0DBFF),
        background = Color(0xFF000000),
        onBackground = Color(0xFFE8EAEC),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFE8EAEC),
        surfaceVariant = Color(0xFF131619),
        onSurfaceVariant = Color(0xFFB8BEC4),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0C0E10),
        surfaceContainer = Color(0xFF101316),
        surfaceContainerHigh = Color(0xFF161A1D),
        surfaceContainerHighest = Color(0xFF1E2125),
        outline = Color(0xFF3E4449),
        outlineVariant = Color(0xFF262B30),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF3B0A06),
        errorContainer = Color(0xFF4A1E1A),
        onErrorContainer = Color(0xFFFFDAD5),
        inverseSurface = Color(0xFFE8EAEC),
        inverseOnSurface = Color(0xFF101316),
        scrim = Color(0xD9000000),
    )

    // --- Gemini Deep Blue (Enhanced) ------------------------------------------------------------
    val geminiDarkEnhanced = darkColorScheme(
        primary = Color(0xFFAAC9FA),
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF1A3A68),
        onPrimaryContainer = Color(0xFFD5E4FD),
        secondary = Color(0xFF9DC9FF),
        onSecondary = Color(0xFF06264D),
        secondaryContainer = Color(0xFF143052),
        onSecondaryContainer = Color(0xFFC4E8FF),
        tertiary = Color(0xFFD2BEFF),
        onTertiary = Color(0xFF26124D),
        tertiaryContainer = Color(0xFF3A2060),
        onTertiaryContainer = Color(0xFFE8DCFF),
        background = Color(0xFF060C1A),
        onBackground = Color(0xFFE3E4EA),
        surface = Color(0xFF060C1A),
        onSurface = Color(0xFFE3E4EA),
        surfaceVariant = Color(0xFF141D32),
        onSurfaceVariant = Color(0xFFB2BACE),
        surfaceContainerLowest = Color(0xFF040810),
        surfaceContainerLow = Color(0xFF0C1324),
        surfaceContainer = Color(0xFF0F1728),
        surfaceContainerHigh = Color(0xFF141D32),
        surfaceContainerHighest = Color(0xFF1A243E),
        outline = Color(0xFF3D4862),
        outlineVariant = Color(0xFF242D48),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF3B0A06),
        errorContainer = Color(0xFF4A1E1A),
        onErrorContainer = Color(0xFFFFDAD5),
        inverseSurface = Color(0xFFE3E4EA),
        inverseOnSurface = Color(0xFF0C1324),
        scrim = Color(0xD9040810),
    )

    // --- Claude Warm Sepia (Enhanced) -----------------------------------------------------------
    val sepiaDarkEnhanced = darkColorScheme(
        primary = Color(0xFFDA7A59),
        onPrimary = Color(0xFF2B1408),
        primaryContainer = Color(0xFF4A2814),
        onPrimaryContainer = Color(0xFFFFDDC9),
        secondary = Color(0xFFE3B793),
        onSecondary = Color(0xFF3A2213),
        secondaryContainer = Color(0xFF422E1E),
        onSecondaryContainer = Color(0xFFF8DEC4),
        tertiary = Color(0xFFCDB99C),
        onTertiary = Color(0xFF322417),
        tertiaryContainer = Color(0xFF3E3024),
        onTertiaryContainer = Color(0xFFE6D4B8),
        background = Color(0xFF16120E),
        onBackground = Color(0xFFF2E8DC),
        surface = Color(0xFF16120E),
        onSurface = Color(0xFFF2E8DC),
        surfaceVariant = Color(0xFF261F18),
        onSurfaceVariant = Color(0xFFD5C5B4),
        surfaceContainerLowest = Color(0xFF110E0A),
        surfaceContainerLow = Color(0xFF1C1713),
        surfaceContainer = Color(0xFF211B15),
        surfaceContainerHigh = Color(0xFF272019),
        surfaceContainerHighest = Color(0xFF2F271E),
        outline = Color(0xFF6E5F4D),
        outlineVariant = Color(0xFF463B2D),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF3B0A06),
        errorContainer = Color(0xFF4A1E1A),
        onErrorContainer = Color(0xFFFFDAD5),
        inverseSurface = Color(0xFFF2E8DC),
        inverseOnSurface = Color(0xFF211B15),
        scrim = Color(0xD9110E0A),
    )

    // --- Cyberpunk (Enhanced) -------------------------------------------------------------------
    val cyberpunkDarkEnhanced = darkColorScheme(
        primary = Color(0xFFFF3399),
        onPrimary = Color(0xFF33001A),
        primaryContainer = Color(0xFF520034),
        onPrimaryContainer = Color(0xFFFFDBEC),
        secondary = Color(0xFF00E7FF),
        onSecondary = Color(0xFF00272E),
        secondaryContainer = Color(0xFF00363E),
        onSecondaryContainer = Color(0xFFB8F8FF),
        tertiary = Color(0xFFB3FF3F),
        onTertiary = Color(0xFF16260A),
        tertiaryContainer = Color(0xFF243414),
        onTertiaryContainer = Color(0xFFDCFFB8),
        background = Color(0xFF0A0510),
        onBackground = Color(0xFFEBE4F7),
        surface = Color(0xFF0A0510),
        onSurface = Color(0xFFEBE4F7),
        surfaceVariant = Color(0xFF1C1434),
        onSurfaceVariant = Color(0xFFC4B4E2),
        surfaceContainerLowest = Color(0xFF06020A),
        surfaceContainerLow = Color(0xFF0F091C),
        surfaceContainer = Color(0xFF140C25),
        surfaceContainerHigh = Color(0xFF1C1434),
        surfaceContainerHighest = Color(0xFF261A44),
        outline = Color(0xFF7053AC),
        outlineVariant = Color(0xFF322156),
        error = Color(0xFFFF6060),
        onError = Color(0xFF38000A),
        errorContainer = Color(0xFF520018),
        onErrorContainer = Color(0xFFFFDCDC),
        inverseSurface = Color(0xFFEBE4F7),
        inverseOnSurface = Color(0xFF140C25),
        scrim = Color(0xE606020A),
    )

    // --- Monokai Pro (NEW) ----------------------------------------------------------------------
    val monokaiProDark = darkColorScheme(
        primary = Color(0xFFFC9867),
        onPrimary = Color(0xFF2B1408),
        primaryContainer = Color(0xFF4A2814),
        onPrimaryContainer = Color(0xFFFFDDC9),
        secondary = Color(0xFFA9DC76),
        onSecondary = Color(0xFF0F2608),
        secondaryContainer = Color(0xFF1A3410),
        onSecondaryContainer = Color(0xFFD4FFBA),
        tertiary = Color(0xFFAB9DF2),
        onTertiary = Color(0xFF1B0F40),
        tertiaryContainer = Color(0xFF2D1F58),
        onTertiaryContainer = Color(0xFFE4D9FF),
        background = Color(0xFF221F22),
        onBackground = Color(0xFFFCFCFA),
        surface = Color(0xFF221F22),
        onSurface = Color(0xFFFCFCFA),
        surfaceVariant = Color(0xFF2D2A2E),
        onSurfaceVariant = Color(0xFFD4D1D6),
        surfaceContainerLowest = Color(0xFF19171C),
        surfaceContainerLow = Color(0xFF282528),
        surfaceContainer = Color(0xFF2D2A2E),
        surfaceContainerHigh = Color(0xFF353238),
        surfaceContainerHighest = Color(0xFF3E3B41),
        outline = Color(0xFF5B595E),
        outlineVariant = Color(0xFF403E41),
        error = Color(0xFFFF6188),
        onError = Color(0xFF33000A),
        errorContainer = Color(0xFF4D0014),
        onErrorContainer = Color(0xFFFFD9DF),
        inverseSurface = Color(0xFFFCFCFA),
        inverseOnSurface = Color(0xFF2D2A2E),
        scrim = Color(0xD919171C),
    )

    // --- Nord Theme (NEW) -----------------------------------------------------------------------
    val nordDark = darkColorScheme(
        primary = Color(0xFF88C0D0),
        onPrimary = Color(0xFF0A2630),
        primaryContainer = Color(0xFF143844),
        onPrimaryContainer = Color(0xFFD8EFFF),
        secondary = Color(0xFF81A1C1),
        onSecondary = Color(0xFF0D2030),
        secondaryContainer = Color(0xFF1A3444),
        onSecondaryContainer = Color(0xFFD8E8FF),
        tertiary = Color(0xFFB48EAD),
        onTertiary = Color(0xFF261030),
        tertiaryContainer = Color(0xFF3B1E48),
        onTertiaryContainer = Color(0xFFF0DDFF),
        background = Color(0xFF2E3440),
        onBackground = Color(0xFFECEFF4),
        surface = Color(0xFF2E3440),
        onSurface = Color(0xFFECEFF4),
        surfaceVariant = Color(0xFF3B4252),
        onSurfaceVariant = Color(0xFFD8DEE9),
        surfaceContainerLowest = Color(0xFF242933),
        surfaceContainerLow = Color(0xFF333A47),
        surfaceContainer = Color(0xFF3B4252),
        surfaceContainerHigh = Color(0xFF434C5E),
        surfaceContainerHighest = Color(0xFF4C566A),
        outline = Color(0xFF5E6879),
        outlineVariant = Color(0xFF4A5363),
        error = Color(0xFFBF616A),
        onError = Color(0xFF2E0A0C),
        errorContainer = Color(0xFF4A1418),
        onErrorContainer = Color(0xFFFFD9DC),
        inverseSurface = Color(0xFFECEFF4),
        inverseOnSurface = Color(0xFF3B4252),
        scrim = Color(0xD9242933),
    )

    // --- Light fallback (Enhanced) --------------------------------------------------------------
    val neutralLightEnhanced = lightColorScheme(
        primary = Color(0xFF2F5D8C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD5E3FD),
        onPrimaryContainer = Color(0xFF062E6F),
        secondary = Color(0xFF3B6360),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFBFEDEA),
        onSecondaryContainer = Color(0xFF00201E),
        tertiary = Color(0xFF5B4E86),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE0DBFF),
        onTertiaryContainer = Color(0xFF1B0F40),
        background = Color(0xFFFBFAFD),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFFBFAFD),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFE2E4EC),
        onSurfaceVariant = Color(0xFF44474C),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF5F4F7),
        surfaceContainer = Color(0xFFEFEEF1),
        surfaceContainerHigh = Color(0xFFE9E8EB),
        surfaceContainerHighest = Color(0xFFE3E2E5),
        outline = Color(0xFF74777D),
        outlineVariant = Color(0xFFC6C8D0),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        inverseSurface = Color(0xFF2F3033),
        inverseOnSurface = Color(0xFFF1F0F3),
        scrim = Color(0x33000000),
    )

    fun colorScheme(preset: ThemePreset, dark: Boolean, pureBlack: Boolean = true) = when {
        !dark -> neutralLightEnhanced
        !pureBlack && preset == ThemePreset.AMOLED_PURE_BLACK -> amoledDarkEnhanced
        else -> when (preset) {
            ThemePreset.AMOLED_PURE_BLACK -> amoledDarkEnhanced
            ThemePreset.GEMINI_DEEP_BLUE -> geminiDarkEnhanced
            ThemePreset.CLAUDE_WARM_SEPIA -> sepiaDarkEnhanced
            ThemePreset.CYBERPUNK -> cyberpunkDarkEnhanced
            ThemePreset.MATERIAL_YOU -> geminiDarkEnhanced // Replaced by dynamic at runtime
            ThemePreset.SYSTEM -> geminiDarkEnhanced
        }
    }
}
