package tw.saietf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

data class PortfolioTrendPoint(
    val epochMillis: Long,
    val value: Long,
)

class PortfolioTrendView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SaiTheme.BRAND
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SaiTheme.GRID
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SaiTheme.MUTED
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private var points: List<PortfolioTrendPoint> = emptyList()
    private var startEpochMillis: Long = 0L
    private var endEpochMillis: Long = 1L
    private var startLabel: String = ""
    private var endLabel: String = ""

    fun setSeries(
        values: List<PortfolioTrendPoint>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        startLabel: String,
        endLabel: String,
    ) {
        points = values
            .filter { it.epochMillis in startEpochMillis..endEpochMillis }
            .sortedBy { it.epochMillis }
        this.startEpochMillis = startEpochMillis
        this.endEpochMillis = max(startEpochMillis + 1L, endEpochMillis)
        this.startLabel = startLabel
        this.endLabel = endLabel
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (220 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val labelHeight = textPaint.textSize * 1.8f
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat() - labelHeight
        if (right <= left || bottom <= top) return

        canvas.drawRect(left, top, right, bottom, gridPaint)
        for (index in 1..3) {
            val x = left + (right - left) * index / 4f
            canvas.drawLine(x, top, x, bottom, gridPaint)
        }

        if (startLabel.isNotBlank()) {
            canvas.drawText(startLabel, left, height - paddingBottom.toFloat(), textPaint)
        }
        if (endLabel.isNotBlank()) {
            val width = textPaint.measureText(endLabel)
            canvas.drawText(
                endLabel,
                right - width,
                height - paddingBottom.toFloat(),
                textPaint,
            )
        }

        if (points.isEmpty()) return

        val minValue = points.minOf { it.value }.toDouble()
        val maxValue = points.maxOf { it.value }.toDouble()
        val rawRange = maxValue - minValue
        val padding = if (rawRange > 0.0) rawRange * 0.08 else max(1.0, maxValue * 0.005)
        val yMin = minValue - padding
        val yMax = maxValue + padding
        val yRange = max(1.0, yMax - yMin)
        val xRange = max(1L, endEpochMillis - startEpochMillis).toDouble()

        fun xOf(epochMillis: Long): Float {
            val fraction = (epochMillis - startEpochMillis).toDouble() / xRange
            return left + ((right - left) * fraction.coerceIn(0.0, 1.0)).toFloat()
        }

        fun yOf(value: Long): Float {
            val normalized = (value.toDouble() - yMin) / yRange
            return bottom - ((bottom - top) * normalized.coerceIn(0.0, 1.0)).toFloat()
        }

        if (points.size == 1) {
            canvas.drawCircle(xOf(points[0].epochMillis), yOf(points[0].value), 5f, linePaint)
            return
        }

        val path = Path()
        points.forEachIndexed { index, point ->
            val x = xOf(point.epochMillis)
            val y = yOf(point.value)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
