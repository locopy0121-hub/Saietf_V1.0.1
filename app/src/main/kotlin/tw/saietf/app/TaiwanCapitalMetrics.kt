package tw.saietf.app

import kotlin.math.floor

data class TaiwanCapitalMetrics(
    val issuedCommonShares: Long?,
    val paidInCapitalTwd: Long?,
    val marketCapitalizationTwd: Long?,
    val capitalPerIssuedShareTwd: Double?,
    val oneYearHigh: Double?,
    val oneYearLow: Double?,
    val oneYearReturnPct: Double?,
)

object TaiwanCapitalMetricCalculator {
    fun calculate(
        profile: TaiwanInstrumentProfile?,
        currentPrice: Double?,
        dailyBars: List<TaiwanDailyBar>,
    ): TaiwanCapitalMetrics {
        val shares = profile?.issuedCommonShares?.takeIf { it > 0L }
        val capital = profile?.paidInCapitalTwd?.takeIf { it >= 0L }
        val validPrice = currentPrice?.takeIf { it.isFinite() && it > 0.0 }
        val marketCap = if (shares != null && validPrice != null) {
            val raw = shares.toDouble() * validPrice
            raw.takeIf { it.isFinite() && it >= 0.0 && it <= Long.MAX_VALUE.toDouble() }
                ?.let { floor(it).toLong() }
        } else {
            null
        }
        val sorted = dailyBars.sortedBy { it.epochMillis }
        val firstClose = sorted.firstOrNull()?.close
        val lastClose = sorted.lastOrNull()?.close
        val oneYearReturn = if (
            firstClose != null && lastClose != null &&
            firstClose.isFinite() && firstClose > 0.0 && lastClose.isFinite()
        ) {
            (lastClose - firstClose) / firstClose * 100.0
        } else {
            null
        }

        return TaiwanCapitalMetrics(
            issuedCommonShares = shares,
            paidInCapitalTwd = capital,
            marketCapitalizationTwd = marketCap,
            capitalPerIssuedShareTwd = if (shares != null && capital != null) {
                capital.toDouble() / shares.toDouble()
            } else {
                null
            },
            oneYearHigh = sorted.maxOfOrNull { it.high },
            oneYearLow = sorted.minOfOrNull { it.low },
            oneYearReturnPct = oneYearReturn,
        )
    }
}
