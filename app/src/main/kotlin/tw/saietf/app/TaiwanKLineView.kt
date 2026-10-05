package tw.saietf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max

class TaiwanKLineView(context: Context) : View(context) {
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(71, 85, 105)
        strokeWidth = 1f
    }
    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(220, 38, 38)
        strokeWidth = 2f
    }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(22, 163, 74)
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 116, 139)
        textSize = 10f * resources.displayMetrics.scaledDensity
    }

    private var bars: List<TaiwanDailyBar> = emptyList()

    fun setBars(values: List<TaiwanDailyBar>) {
        bars = values.takeLast(60)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (250 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + 2f
        val right = (width - paddingRight).toFloat() - 2f
        val top = paddingTop.toFloat() + 4f
        val bottom = (height - paddingBottom).toFloat() - textPaint.textSize * 1.8f
        if (right <= left || bottom <= top) return

        canvas.drawRect(left, top, right, bottom, gridPaint)
        for (index in 1..3) {
            val y = top + (bottom - top) * index / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (bars.isEmpty()) {
            canvas.drawText("K 線資料載入中", left + 8f, top + textPaint.textSize * 2f, textPaint)
            return
        }

        val minPrice = bars.minOf { it.low }
        val maxPrice = bars.maxOf { it.high }
        val rawRange = maxPrice - minPrice
        val pricePadding = if (rawRange > 0.0) rawRange * 0.06 else max(0.01, maxPrice * 0.005)
        val yMin = minPrice - pricePadding
        val yMax = maxPrice + pricePadding
        val yRange = max(0.01, yMax - yMin)
        val slot = (right - left) / bars.size.coerceAtLeast(1)
        val bodyWidth = max(2f, slot * 0.56f)

        fun yOf(value: Double): Float {
            val fraction = ((value - yMin) / yRange).coerceIn(0.0, 1.0)
            return bottom - ((bottom - top) * fraction).toFloat()
        }

        bars.forEachIndexed { index, bar ->
            val centerX = left + slot * (index + 0.5f)
            val paint = if (bar.close >= bar.open) upPaint else downPaint
            canvas.drawLine(centerX, yOf(bar.high), centerX, yOf(bar.low), paint)
            val openY = yOf(bar.open)
            val closeY = yOf(bar.close)
            val bodyTop = minOf(openY, closeY)
            val bodyBottom = maxOf(openY, closeY)
            if (bodyBottom - bodyTop < 2f) {
                canvas.drawLine(
                    centerX - bodyWidth / 2f,
                    bodyTop,
                    centerX + bodyWidth / 2f,
                    bodyTop,
                    paint,
                )
            } else {
                canvas.drawRect(
                    centerX - bodyWidth / 2f,
                    bodyTop,
                    centerX + bodyWidth / 2f,
                    bodyBottom,
                    paint,
                )
            }
        }

        canvas.drawText("%.2f".format(maxPrice), left + 4f, top + textPaint.textSize, textPaint)
        canvas.drawText("%.2f".format(minPrice), left + 4f, bottom - 4f, textPaint)
    }
}
