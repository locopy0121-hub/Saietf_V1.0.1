package tw.saietf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.max

class IntradayTrendView(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private var points: List<Long> = emptyList()

    fun setPoints(values: List<Long>) {
        points = values
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (180 * resources.displayMetrics.density).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        canvas.drawRect(left, top, right, bottom, axisPaint)
        if (points.size < 2) return

        val minValue = points.minOrNull()?.toDouble() ?: return
        val maxValue = points.maxOrNull()?.toDouble() ?: return
        val range = max(1.0, maxValue - minValue)
        val stepX = (right - left) / (points.size - 1).toFloat()

        val path = android.graphics.Path()
        points.forEachIndexed { index, value ->
            val x = left + index * stepX
            val normalized = (value.toDouble() - minValue) / range
            val y = bottom - (normalized * (bottom - top)).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
