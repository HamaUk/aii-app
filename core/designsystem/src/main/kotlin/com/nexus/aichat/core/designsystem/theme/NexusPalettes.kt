package com.nexus.aichat.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.nexus.aichat.core.model.ThemePreset

/**
 * The six hand-tuned palettes.
 *
 * Each preset defines a full M3 role set rather than just two accents, because the app renders
 * assistant bubbles, user bubbles, glass chrome, code surfaces and status chips that must stay
 * legible against each other at AMOLED black. The third colour of each triple is the on-colour.
 */
object NexusPalettes {

    // --- AMOLED pure black -------------------------------------------------------------------
    private val amoledDark = darkColorScheme(
        primary = Color(0xFF8AB4F8),
        onPrimary = Color(0xFF062018),
        primaryContainer = Color(0xFF12342C),
        onPrimaryContainer = Color(0xFFB9F0DC),
        secondary = Color(0xFF7FD1B9),
        onSecondary = Color(0xFF04201A),
        secondaryContainer = Color(0xFF10201C),
        onSecondaryContainer = Color(0xFF9FE3CE),
        tertiary = Color(0xFFC6C0FF),
        onTertiary = Color(0xFF1B1440),
        background = Color(0xFF000000),
        onBackground = Color(0xFFE6E8EA),
        surface = Color(0xFF000000),
        onSurface = Color(0xFFE6E8EA),
        surfaceVariant = Color(0xFF111417),
        onSurfaceVariant = Color(0xFFB4BAC0),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0C0E),
        surfaceContainer = Color(0xFF0E1113),
        surfaceContainerHigh = Color(0xFF141719),
        surfaceContainerHighest = Color(0xFF1A1E21),
        outline = Color(0xFF3A4045),
        outlineVariant = Color(0xFF23282C),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF3B0A06),
        errorContainer = Color(0xFF3B1A16),
        onErrorContainer = Color(0xFFFFDAD5),
        inverseSurface = Color(0xFFE6E8EA),
        inverseOnSurface = Color(0xFF101315),
        scrim = Color(0xCC000000),
    )

    // --- Gemini deep blue --------------------------------------------------------------------
    private val geminiDark = darkColorScheme(
        primary = Color(0xFFA8C7FA),
        onPrimary = Color(0xFF062E6F),
        primaryContainer = Color(0xFF16325C),
        onPrimaryContainer = Color(0xFFD3E3FD),
        secondary = Color(0xFF9AC7FF),
        onSecondary = Color(0xFF06264D),
        secondaryContainer = Color(0xFF10294A),
        onSecondaryContainer = Color(0xFFC2E7FF),
        tertiary = Color(0xFFD0BCFF),
        onTertiary = Color(0xFF26124D),
        background = Color(0xFF060B18),
        onBackground = Color(0xFFE1E2E8),
        surface = Color(0xFF060B18),
        onSurface = Color(0xFFE1E2E8),
        surfaceVariant = Color(0xFF121B2E),
        onSurfaceVariant = Color(0xFFAEB6CC),
        surfaceContainerLowest = Color(0xFF04070F),
        surfaceContainerLow = Color(0xFF0A1120),
        surfaceContainer = Color(0xFF0D1526),
        surfaceContainerHigh = Color(0xFF121B2E),
        surfaceContainerHighest = Color(0xFF18223A),
        outline = Color(0xFF39445E),
        outlineVariant = Color(0xFF212B44),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF3B0A06),
        errorContainer = Color(0xFF3B1A16),
        onErrorContainer = Color(0xFFFFDAD5),
        inverseSurface = Color(0xFFE1E2E8),
        inverseOnSurface = Color(0xFF0A1120),
        scrim = Color(0xCC04070F),
    )

    // --- Claude warm sepia -------------------------------------------------------------------
    private val sepiaDark = darkColorScheme(
        primary = Color(0xFFD97757),
        onPrimary = Color(0xFF2B1408),
        primaryContainer = Color(0xFF43220F),
        onPrimaryContainer = Color(0xFFFFD9C7),
        secondary = Color(0xFFE0B48F),
        onSecondary = Color(0xFF3A2213),
        secondaryContainer = Color(0xFF3A2A1C),
        onSecondaryContainer = Color(0xFFF6DCC2),
        tertiary = Color(0xFFC9B79A),
        onTertiary = Color(0xFF322417),
        background = Color(0xFF14100C),
        onBackground = Color(0xFFF0E6DA),
        surface = Color(0xFF14100C),
        onSurface = Color(0xFFF0E6DA),
        surfaceVariant = Color(0xFF241D16),
        onSurfaceVariant = Color(0xFFD3C3B2),
        surfaceContainerLowest = Color(0xFF0F0C09),
        surfaceContainerLow = Color(0xFF1A1511),
        surfaceContainer = Color(0xFF1F1913),
        surfaceContainerHigh = Color(0xFF251E17),
        surfaceContainerHighest = Color(0xFF2D251C),
        outline = Color(0xFF6A5B49),
        outlineVariant = Color(0xFF423729),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF3B0A06),
        errorContainer = Color(0xFF3B1A16),
        onErrorContainer = Color(0xFFFFDAD5),
        inverseSurface = Color(0xFFF0E6DA),
        inverseOnSurface = Color(0xFF1F1913),
        scrim = Color(0xCC0F0C09),
    )

    // --- Cyberpunk ---------------------------------------------------------------------------
    private val cyberpunkDark = darkColorScheme(
        primary = Color(0xFFFF2E97),
        onPrimary = Color(0xFF33001A),
        primaryContainer = Color(0xFF4A0030),
        onPrimaryContainer = Color(0xFFFFD9EC),
        secondary = Color(0xFF00E5FF),
        onSecondary = Color(0xFF00272E),
        secondaryContainer = Color(0xFF00303A),
        onSecondaryContainer = Color(0xFFB6F7FF),
        tertiary = Color(0xFFB0FF3C),
        onTertiary = Color(0xFF16260A),
        background = Color(0xFF08040F),
        onBackground = Color(0xFFE9E2F5),
        surface = Color(0xFF08040F),
        onSurface = Color(0xFFE9E2F5),
        surfaceVariant = Color(0xFF1A1230),
        onSurfaceVariant = Color(0xFFC0B2E0),
        surfaceContainerLowest = Color(0xFF050209),
        surfaceContainerLow = Color(0xFF0D0718),
        surfaceContainer = Color(0xFF120A21),
        surfaceContainerHigh = Color(0xFF1A1230),
        surfaceContainerHighest = Color(0xFF241840),
        outline = Color(0xFF6C4FA8),
        outlineVariant = Color(0xFF2E1F52),
        error = Color(0xFFFF5C5C),
        onError = Color(0xFF38000A),
        errorContainer = Color(0xFF4D0018),
        onErrorContainer = Color(0xFFFFDADA),
        inverseSurface = Color(0xFFE9E2F5),
        inverseOnSurface = Color(0xFF120A21),
        scrim = Color(0xE6050209),
    )

    // --- Light fallback (System light / Material You light) ----------------------------------
    private val neutralLight = lightColorScheme(
        primary = Color(0xFF2F5D8C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD3E3FD),
        onPrimaryContainer = Color(0xFF062E6F),
        secondary = Color(0xFF3B6360),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF5B4E86),
        onTertiary = Color(0xFFFFFFFF),
        background = Color(0xFFFAF9FC),
        onBackground = Color(0xFF1A1C1E),
        surface = Color(0xFFFAF9FC),
        onSurface = Color(0xFF1A1C1E),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474C),
        outline = Color(0xFF74777D),
        outlineVariant = Color(0xFFC4C6D0),
    )

    fun colorScheme(preset: ThemePreset, dark: Boolean, pureBlack: Boolean = true) = when {
        !dark -> neutralLight
        !pureBlack && preset == ThemePreset.AMOLED_PURE_BLACK -> amoledDark
        else -> when (preset) {
            ThemePreset.AMOLED_PURE_BLACK -> amoledDark
            ThemePreset.GEMINI_DEEP_BLUE -> geminiDark
            ThemePreset.CLAUDE_WARM_SEPIA -> sepiaDark
            ThemePreset.CYBERPUNK -> cyberpunkDark
            ThemePreset.MATERIAL_YOU -> geminiDark   // replaced by dynamicColorScheme at runtime
            ThemePreset.SYSTEM -> geminiDark
        }
    }
}
