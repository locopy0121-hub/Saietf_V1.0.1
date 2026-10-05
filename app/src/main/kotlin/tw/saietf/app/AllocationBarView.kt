package tw.saietf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class AllocationBarView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var weights: List<Float> = emptyList()

    fun setWeights(values: List<Float>) {
        weights = values.filter { it > 0f }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (42 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (weights.isEmpty()) return
        val total = weights.sum().takeIf { it > 0f } ?: return
        var left = paddingLeft.toFloat()
        val usableWidth = (width - paddingLeft - paddingRight).toFloat()
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()

        weights.forEachIndexed { index, weight ->
            val segmentWidth = usableWidth * (weight / total)
            paint.color = Color.HSVToColor(
                floatArrayOf((index * 61f) % 360f, 0.48f, 0.82f),
            )
            canvas.drawRect(left, top, left + segmentWidth, bottom, paint)
            left += segmentWidth
        }
    }
}
