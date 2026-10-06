package tw.saietf.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable

object SaiTheme {
    val PAGE_BACKGROUND: Int = Color.rgb(242, 247, 255)
    val CARD: Int = Color.WHITE
    val CARD_SOFT: Int = Color.rgb(237, 244, 255)
    val BRAND: Int = Color.rgb(37, 99, 235)
    val BRAND_SOFT: Int = Color.rgb(229, 237, 255)
    val ACCENT: Int = Color.rgb(124, 58, 237)
    val ACCENT_SOFT: Int = Color.rgb(242, 236, 255)
    val TEXT: Int = Color.rgb(15, 23, 42)
    val TEXT_SECONDARY: Int = Color.rgb(51, 65, 85)
    val MUTED: Int = Color.rgb(100, 116, 139)
    val BORDER: Int = Color.rgb(213, 226, 242)
    val INPUT: Int = Color.rgb(239, 245, 255)
    val GAIN: Int = Color.rgb(220, 38, 38)
    val LOSS: Int = Color.rgb(22, 163, 74)
    val FLAT: Int = MUTED
    val PNL_FLAT: Int = Color.rgb(202, 138, 4)

    // V1.1.15 UI Recovery tokens: one visual language for every runtime page.
    val HERO: Int = Color.rgb(235, 242, 255)
    val SECTION: Int = Color.rgb(231, 239, 255)
    val SURFACE_ALT: Int = Color.rgb(248, 250, 255)
    val DIVIDER: Int = Color.rgb(226, 232, 240)
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
