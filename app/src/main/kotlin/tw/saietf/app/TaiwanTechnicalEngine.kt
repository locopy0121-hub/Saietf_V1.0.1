package tw.saietf.app

import kotlin.math.max

data class IndicatorPoint(
    val epochMillis: Long,
    val value: Double,
)

data class MacdSeries(
    val macd: List<IndicatorPoint>,
    val signal: List<IndicatorPoint>,
    val histogram: List<IndicatorPoint>,
)

object TaiwanTechnicalEngine {
    fun sma(bars: List<TaiwanDailyBar>, period: Int): List<IndicatorPoint> {
        require(period > 0)
        if (bars.size < period) return emptyList()
        val out = ArrayList<IndicatorPoint>(bars.size - period + 1)
        var sum = 0.0
        bars.forEachIndexed { index, bar ->
            sum += bar.close
            if (index >= period) sum -= bars[index - period].close
            if (index >= period - 1) {
                out += IndicatorPoint(bar.epochMillis, sum / period)
            }
        }
        return out
    }

    fun ema(bars: List<TaiwanDailyBar>, period: Int): List<IndicatorPoint> {
        require(period > 0)
        if (bars.size < period) return emptyList()
        val out = ArrayList<IndicatorPoint>(bars.size - period + 1)
        var seed = 0.0
        for (index in 0 until period) seed += bars[index].close
        var previous = seed / period
        out += IndicatorPoint(bars[period - 1].epochMillis, previous)
        val alpha = 2.0 / (period + 1.0)
        for (index in period until bars.size) {
            previous = bars[index].close * alpha + previous * (1.0 - alpha)
            out += IndicatorPoint(bars[index].epochMillis, previous)
        }
        return out
    }

    fun rsi(bars: List<TaiwanDailyBar>, period: Int = 14): List<IndicatorPoint> {
        require(period > 0)
        if (bars.size <= period) return emptyList()
        var gain = 0.0
        var loss = 0.0
        for (index in 1..period) {
            val delta = bars[index].close - bars[index - 1].close
            gain += max(delta, 0.0)
            loss += max(-delta, 0.0)
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        val out = ArrayList<IndicatorPoint>()
        out += IndicatorPoint(bars[period].epochMillis, rsiValue(avgGain, avgLoss))
        for (index in period + 1 until bars.size) {
            val delta = bars[index].close - bars[index - 1].close
            val nextGain = max(delta, 0.0)
            val nextLoss = max(-delta, 0.0)
            avgGain = (avgGain * (period - 1) + nextGain) / period
            avgLoss = (avgLoss * (period - 1) + nextLoss) / period
            out += IndicatorPoint(bars[index].epochMillis, rsiValue(avgGain, avgLoss))
        }
        return out
    }

    fun macd(
        bars: List<TaiwanDailyBar>,
        fastPeriod: Int = 12,
        slowPeriod: Int = 26,
        signalPeriod: Int = 9,
    ): MacdSeries {
        require(fastPeriod > 0 && slowPeriod > fastPeriod && signalPeriod > 0)
        val fast = ema(bars, fastPeriod).associateBy { it.epochMillis }
        val slow = ema(bars, slowPeriod)
        val macd = slow.mapNotNull { slowPoint ->
            fast[slowPoint.epochMillis]?.let { fastPoint ->
                IndicatorPoint(slowPoint.epochMillis, fastPoint.value - slowPoint.value)
            }
        }
        if (macd.size < signalPeriod) return MacdSeries(macd, emptyList(), emptyList())

        val signal = emaPoints(macd, signalPeriod)
        val signalByTime = signal.associateBy { it.epochMillis }
        val histogram = macd.mapNotNull { point ->
            signalByTime[point.epochMillis]?.let { sig ->
                IndicatorPoint(point.epochMillis, point.value - sig.value)
            }
        }
        return MacdSeries(macd, signal, histogram)
    }

    private fun emaPoints(points: List<IndicatorPoint>, period: Int): List<IndicatorPoint> {
        if (points.size < period) return emptyList()
        var seed = 0.0
        for (index in 0 until period) seed += points[index].value
        var previous = seed / period
        val out = ArrayList<IndicatorPoint>()
        out += IndicatorPoint(points[period - 1].epochMillis, previous)
        val alpha = 2.0 / (period + 1.0)
        for (index in period until points.size) {
            previous = points[index].value * alpha + previous * (1.0 - alpha)
            out += IndicatorPoint(points[index].epochMillis, previous)
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - 100.0 / (1.0 + rs)
    }
}
