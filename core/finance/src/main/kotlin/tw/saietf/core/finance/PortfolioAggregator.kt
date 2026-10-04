package tw.saietf.core.finance

import kotlin.math.floor

interface PortfolioAggregator {
    fun aggregate(items: List<InstrumentCalculationResult>): PortfolioCalculationResult
}

class DefaultPortfolioAggregator : PortfolioAggregator {
    override fun aggregate(items: List<InstrumentCalculationResult>): PortfolioCalculationResult {
        val totalMarketValue = items.fold(0L) { sum, row -> Math.addExact(sum, row.currentMarketValue) }
        val totalInvestmentCost = items.sumOf { it.totalInvestmentCost }
        val totalNetLiquidationValue = items.fold(0L) { sum, row -> Math.addExact(sum, row.netLiquidationValue) }
        val totalEstimatedSellCommission = items.fold(0L) { sum, row -> Math.addExact(sum, row.estimatedSellCommission) }
        val totalEstimatedSellTax = items.fold(0L) { sum, row -> Math.addExact(sum, row.estimatedSellTax) }
        val totalUnrealizedProfit = totalNetLiquidationValue.toDouble() - totalInvestmentCost
        val totalUnrealizedROI =
            if (totalMarketValue > 0 && totalInvestmentCost > 0.0) {
                roundPercent(totalUnrealizedProfit / totalInvestmentCost * 100.0)
            } else 0.0
        val realized = items.sumOf { it.realizedNetPnL }
        val dividends = items.fold(0L) { sum, row -> Math.addExact(sum, row.totalDividendsReceived) }
        val comprehensive = totalUnrealizedProfit + realized + dividends.toDouble()
        val nextDividend = items.fold(0L) { sum, row -> Math.addExact(sum, row.nextEstimatedDividend) }

        val weighted = items.map { row ->
            row.copy(
                portfolioWeight =
                    if (totalMarketValue > 0 && row.totalShares > 0 && row.currentPrice > 0.0) {
                        roundPercent(row.currentMarketValue.toDouble() / totalMarketValue.toDouble() * 100.0)
                    } else 0.0,
            )
        }

        return PortfolioCalculationResult(
            totalMarketValue = totalMarketValue,
            totalInvestmentCost = totalInvestmentCost,
            totalNetLiquidationValue = totalNetLiquidationValue,
            totalEstimatedSellCommission = totalEstimatedSellCommission,
            totalEstimatedSellTax = totalEstimatedSellTax,
            totalUnrealizedProfit = totalUnrealizedProfit,
            totalUnrealizedROI = totalUnrealizedROI,
            realizedNetPnL = realized,
            comprehensivePnL = comprehensive,
            totalPnl = comprehensive,
            totalDividendsReceived = dividends,
            nextEstimatedDividendTotal = nextDividend,
            instrumentSummaries = weighted,
        )
    }

    private fun roundPercent(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return floor((value + Math.ulp(1.0)) * 100.0 + 0.5) / 100.0
    }
}
