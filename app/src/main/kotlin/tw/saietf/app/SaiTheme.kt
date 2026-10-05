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
        rounded(CARD, 20f, density, BORDER, 1)

    fun softCard(density: Float): GradientDrawable =
        rounded(CARD_SOFT, 16f, density, BORDER, 1)
}
