package tw.saietf.app

data class TaiwanTechnicalSnapshot(
    val sma5: Double?,
    val sma20: Double?,
    val sma60: Double?,
    val rsi14: Double?,
    val averageVolume20: Long?,
)

object TaiwanTechnicalIndicators {
    fun from(bars: List<TaiwanDailyBar>): TaiwanTechnicalSnapshot {
        val sorted = bars.sortedBy { it.epochMillis }
        val closes = sorted.map { it.close }
        val volumes = sorted.map { it.volume }

        return TaiwanTechnicalSnapshot(
            sma5 = averageLast(closes, 5),
            sma20 = averageLast(closes, 20),
            sma60 = averageLast(closes, 60),
            rsi14 = rsi(closes, 14),
            averageVolume20 = volumes
                .takeIf { it.size >= 20 }
                ?.takeLast(20)
                ?.average()
                ?.toLong(),
        )
    }

    private fun averageLast(values: List<Double>, count: Int): Double? =
        values.takeIf { it.size >= count }?.takeLast(count)?.average()

    private fun rsi(closes: List<Double>, period: Int): Double? {
        if (closes.size <= period) return null
        val recent = closes.takeLast(period + 1)
        var gains = 0.0
        var losses = 0.0
        for (index in 1 until recent.size) {
            val delta = recent[index] - recent[index - 1]
            if (delta > 0.0) gains += delta else losses += -delta
        }
        val averageGain = gains / period
        val averageLoss = losses / period
        if (averageGain == 0.0 && averageLoss == 0.0) return 50.0
        if (averageLoss == 0.0) return 100.0
        val rs = averageGain / averageLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
