package tw.saietf.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object SaiTheme {
    enum class Mode(val key: String) {
        SYSTEM("system"),
        LIGHT("light"),
        DARK("dark");

        companion object {
            fun fromKey(value: String?): Mode =
                entries.firstOrNull { it.key == value } ?: SYSTEM
        }
    }

    private var darkMode: Boolean = false

    fun applyDarkMode(enabled: Boolean) {
        darkMode = enabled
    }

    fun isDarkMode(): Boolean = darkMode

    val PAGE_BACKGROUND: Int get() =
        if (darkMode) Color.rgb(8, 17, 31) else Color.rgb(242, 247, 255)
    val CARD: Int get() =
        if (darkMode) Color.rgb(15, 27, 44) else Color.WHITE
    val CARD_SOFT: Int get() =
        if (darkMode) Color.rgb(20, 35, 55) else Color.rgb(237, 244, 255)
    val BRAND: Int get() =
        if (darkMode) Color.rgb(77, 166, 255) else Color.rgb(37, 99, 235)
    val BRAND_SOFT: Int get() =
        if (darkMode) Color.rgb(17, 49, 80) else Color.rgb(229, 237, 255)
    val ACCENT: Int get() =
        if (darkMode) Color.rgb(167, 139, 250) else Color.rgb(124, 58, 237)
    val ACCENT_SOFT: Int get() =
        if (darkMode) Color.rgb(48, 37, 73) else Color.rgb(242, 236, 255)
    val TEXT: Int get() =
        if (darkMode) Color.rgb(241, 245, 249) else Color.rgb(15, 23, 42)
    val TEXT_SECONDARY: Int get() =
        if (darkMode) Color.rgb(203, 213, 225) else Color.rgb(51, 65, 85)
    val MUTED: Int get() =
        if (darkMode) Color.rgb(148, 163, 184) else Color.rgb(100, 116, 139)
    val BORDER: Int get() =
        if (darkMode) Color.rgb(51, 65, 85) else Color.rgb(213, 226, 242)
    val INPUT: Int get() =
        if (darkMode) Color.rgb(18, 31, 49) else Color.rgb(239, 245, 255)
    val GAIN: Int get() =
        if (darkMode) Color.rgb(248, 113, 113) else Color.rgb(220, 38, 38)
    val LOSS: Int get() =
        if (darkMode) Color.rgb(74, 222, 128) else Color.rgb(22, 163, 74)
    val FLAT: Int get() = MUTED
    val PNL_FLAT: Int get() =
        if (darkMode) Color.rgb(250, 204, 21) else Color.rgb(202, 138, 4)

    val HERO: Int get() =
        if (darkMode) Color.rgb(12, 31, 51) else Color.rgb(235, 242, 255)
    val SECTION: Int get() =
        if (darkMode) Color.rgb(17, 43, 69) else Color.rgb(231, 239, 255)
    val SURFACE_ALT: Int get() =
        if (darkMode) Color.rgb(10, 22, 36) else Color.rgb(248, 250, 255)
    val DIVIDER: Int get() =
        if (darkMode) Color.rgb(43, 57, 76) else Color.rgb(226, 232, 240)

    const val CARD_RADIUS_DP: Float = 20f
    const val SECTION_RADIUS_DP: Float = 14f
    const val HERO_RADIUS_DP: Float = 24f

    fun rounded(
        fill: Int,
        radiusDp: Float,
        density: Float,
        strokeColor: Int? = null,
        strokeDp: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radiusDp * density
        if (strokeColor != null && strokeDp > 0) {
            setStroke((strokeDp * density).toInt().coerceAtLeast(1), strokeColor)
        }
    }

    fun card(density: Float): GradientDrawable =
        rounded(CARD, CARD_RADIUS_DP, density, BORDER, 1)

    fun softCard(density: Float): GradientDrawable =
        rounded(CARD_SOFT, 16f, density, BORDER, 1)

    fun heroCard(density: Float): GradientDrawable =
        rounded(HERO, HERO_RADIUS_DP, density, BORDER, 1)

    fun sectionSurface(density: Float): GradientDrawable =
        rounded(SECTION, SECTION_RADIUS_DP, density, BORDER, 1)
}
