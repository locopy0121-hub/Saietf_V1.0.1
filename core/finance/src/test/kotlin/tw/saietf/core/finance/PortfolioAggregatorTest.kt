package tw.saietf.core.finance

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.saietf.core.model.TradeMode

class PortfolioAggregatorTest {
    private val engine = DefaultFinanceEngine()
    private val aggregator = DefaultPortfolioAggregator()

    @Test
    fun aggregatesCanonicalSummariesWithoutRecalculatingUiValues() {
        val items = listOf(
            engine.calculateInstrument(
                InstrumentCalculationInput(
                    symbol = "0050",
                    currentPrice = 90.0,
                    liquidationTradeMode = TradeMode.ODD_LOT,
                    dividendFrequency = 4,
                    latestDividendPerShare = 1.0,
                    transactions = listOf(
                        TradeRecord("b1", TradeSide.BUY, TradeMode.ODD_LOT, 20, 82.4, "2026-01-01", actualFee = 2),
                    ),
                    dividendRecords = listOf(DividendRecord("d1", "2026-02-01", 1.0, 20)),
                ),
            ),
            engine.calculateInstrument(
                InstrumentCalculationInput(
                    symbol = "00878",
                    currentPrice = 22.0,
                    liquidationTradeMode = TradeMode.ROUND_LOT,
                    dividendFrequency = 4,
                    latestDividendPerShare = 0.4,
                    transactions = listOf(
                        TradeRecord("b1", TradeSide.BUY, TradeMode.ROUND_LOT, 1000, 20.0, "2026-02-01"),
                    ),
                    dividendRecords = listOf(DividendRecord("d1", "2026-03-01", 0.4, 1000)),
                ),
            ),
        )

        val result = aggregator.aggregate(items)

        assertEquals(23800L, result.totalMarketValue)
        assertEquals(21670.0, result.totalInvestmentCost, 0.0)
        assertEquals(23756L, result.totalNetLiquidationValue)
        assertEquals(21L, result.totalEstimatedSellCommission)
        assertEquals(23L, result.totalEstimatedSellTax)
        assertEquals(2086.0, result.totalUnrealizedProfit, 0.0)
        assertEquals(9.63, result.totalUnrealizedROI, 0.0)
        assertEquals(0.0, result.realizedNetPnL, 0.0)
        assertEquals(400L, result.totalDividendsReceived)
        assertEquals(2486.0, result.comprehensivePnL, 0.0)
        assertEquals(result.comprehensivePnL, result.totalPnl, 0.0)
        assertEquals(400L, result.nextEstimatedDividendTotal)
        assertEquals(7.56, result.instrumentSummaries.first { it.symbol == "0050" }.portfolioWeight, 0.0)
        assertEquals(92.44, result.instrumentSummaries.first { it.symbol == "00878" }.portfolioWeight, 0.0)
    }

    @Test
    fun emptyPortfolioIsCanonicalZero() {
        val result = aggregator.aggregate(emptyList())
        assertEquals(0L, result.totalMarketValue)
        assertEquals(0.0, result.totalInvestmentCost, 0.0)
        assertEquals(0.0, result.comprehensivePnL, 0.0)
        assertEquals(0, result.instrumentSummaries.size)
    }
}
