package tw.saietf.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import tw.saietf.core.database.entity.EtfComponentEntity

class EtfComponentDonutView(context: Context) : View(context) {
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SaiTheme.TEXT_SECONDARY
        textAlign = Paint.Align.CENTER
    }
    private var rows: List<EtfComponentEntity> = emptyList()

    fun setRows(value: List<EtfComponentEntity>) {
        val latestPeriod = value.maxOfOrNull { it.period }
        rows = value
            .filter { latestPeriod == null || it.period == latestPeriod }
            .filter { (it.weightPct ?: 0.0) > 0.0 }
            .sortedByDescending { it.weightPct }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val size = minOf(width, height).toFloat()
        val stroke = 30f * density
        val radius = (size * 0.31f).coerceAtLeast(stroke)
        val cx = width / 2f
        val cy = height / 2f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        arcPaint.strokeWidth = stroke

        val weights = rows.mapNotNull { it.weightPct }.filter { it > 0.0 }
        val total = weights.sum()
        if (rows.isEmpty() || total <= 0.0) {
            textPaint.textSize = 14f * density
            canvas.drawText("成分權重待資料", cx, cy, textPaint)
            return
        }

        val palette = intArrayOf(
            Color.rgb(37, 99, 235),
            Color.rgb(124, 58, 237),
            Color.rgb(14, 165, 233),
            Color.rgb(16, 185, 129),
            Color.rgb(245, 158, 11),
            Color.rgb(239, 68, 68),
            Color.rgb(99, 102, 241),
            Color.rgb(20, 184, 166),
        )
        var start = -90f
        rows.take(12).forEachIndexed { index, row ->
            val value = row.weightPct ?: return@forEachIndexed
            val sweep = (value / total * 360.0).toFloat()
            arcPaint.color = palette[index % palette.size]
            canvas.drawArc(oval, start, sweep, false, arcPaint)
            start += sweep
        }
        if (rows.size > 12 && start < 270f) {
            arcPaint.color = SaiTheme.BORDER
            canvas.drawArc(oval, start, 270f - start, false, arcPaint)
        }

        textPaint.textSize = 15f * density
        textPaint.color = SaiTheme.TEXT
        canvas.drawText("ETF 成分", cx, cy - 2f * density, textPaint)
        textPaint.textSize = 12f * density
        textPaint.color = SaiTheme.MUTED
        canvas.drawText("${rows.size} 檔", cx, cy + 17f * density, textPaint)
    }
}
