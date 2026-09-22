package com.nexus.aichat.core.model

import kotlinx.serialization.Serializable

/**
 * The theme selector exposed in Settings.
 *
 * Persisted as a stable [id] string, so adding palettes later never invalidates stored preferences.
 */
@Serializable
enum class ThemePreset(val id: String, val label: String, val blurb: String) {
    SYSTEM("system", "System", "Follow the device light/dark setting."),
    AMOLED_PURE_BLACK("amoled", "AMOLED Pure Black", "True #000000 surfaces. Maximum battery saving on OLED."),
    GEMINI_DEEP_BLUE("gemini", "Gemini Deep Blue", "Deep navy gradients with Google-blue accents."),
    CLAUDE_WARM_SEPIA("sepia", "Claude Warm Sepia", "Warm paper tones, terracotta accent, low blue light."),
    CYBERPUNK("cyberpunk", "Cyberpunk", "Near-black violet with neon magenta and cyan."),
    MATERIAL_YOU("monet", "Material You", "Wallpaper-derived dynamic colour (Android 12+)."),
    ;

    companion object {
        val DEFAULT = AMOLED_PURE_BLACK
        fun fromId(id: String?): ThemePreset = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

@Serializable
enum class ThemeBrightnessMode { FOLLOW_SYSTEM, ALWAYS_DARK, ALWAYS_LIGHT }

/**
 * A single stored appearance setting. Kept tiny and immutable so it can flow straight into the
 * theme composable with no mapping layer.
 */
@Serializable
data class AppearanceSettings(
    val preset: ThemePreset = ThemePreset.DEFAULT,
    val brightnessMode: ThemeBrightnessMode = ThemeBrightnessMode.ALWAYS_DARK,
    val useDynamicColor: Boolean = false,
    val pureBlackInDark: Boolean = true,
    val cornerStyle: CornerStyle = CornerStyle.ORGANIC,
    val hapticsEnabled: Boolean = true,
    val reducedMotion: Boolean = false,
)

@Serializable
enum class CornerStyle(val label: String, val blurb: String) {
    ORGANIC("Organic", "Large, soft radii that morph with the surface they sit on."),
    ROUNDED("Rounded", "Classic Material 3 rounded corners."),
    SHARP("Sharp", "Minimal radii, maximum information density."),
}
