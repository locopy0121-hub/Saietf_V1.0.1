package tw.saietf.core.finance

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.saietf.core.model.InstrumentType
import tw.saietf.core.model.TradeMode

class FinanceEngineGoldenTest {
    private val engine = DefaultFinanceEngine()

    @Test
    fun executed0050BuyPreservesActualFeeTruth() {
        val settlement = engine.calculateSettlement(
            shares = 20,
            price = 82.4,
            tradeMode = TradeMode.ODD_LOT,
            side = TradeSide.BUY,
            actualFee = 2,
        )
        assertEquals(1648L, settlement.tradeAmount)
        assertEquals(2L, settlement.commission)
        assertEquals(0L, settlement.tax)
        assertEquals(1650L, settlement.settlementAmount)

        val summary = engine.calculateInstrument(
            InstrumentCalculationInput(
                symbol = "0050",
                currentPrice = 82.4,
                liquidationTradeMode = TradeMode.ODD_LOT,
                dividendFrequency = 4,
                transactions = listOf(
                    TradeRecord("b1", TradeSide.BUY, TradeMode.ODD_LOT, 20, 82.4, "2026-01-01", actualFee = 2),
                ),
            ),
        )
        assertEquals(1650.0, summary.totalInvestmentCost, 0.0)
    }

    @Test
    fun roundAndOddLotMinimumFeesRemainDistinct() {
        val round = engine.calculateSettlement(10, 100.0, TradeMode.ROUND_LOT, TradeSide.BUY)
        val odd = engine.calculateSettlement(10, 100.0, TradeMode.ODD_LOT, TradeSide.BUY)
        assertEquals(1000L, round.tradeAmount)
        assertEquals(20L, round.commission)
        assertEquals(1020L, round.settlementAmount)
        assertEquals(1000L, odd.tradeAmount)
        assertEquals(1L, odd.commission)
        assertEquals(1001L, odd.settlementAmount)
    }

    @Test
    fun etfAndStockSellTaxesCannotBeInterchanged() {
        val etf = engine.calculateSettlement(
            10, 1000.0, TradeMode.ROUND_LOT, TradeSide.SELL, InstrumentType.ETF,
        )
        val stock = engine.calculateSettlement(
            10, 1000.0, TradeMode.ROUND_LOT, TradeSide.SELL, InstrumentType.STOCK,
        )
        assertEquals(10L, etf.tax)
        assertEquals(30L, stock.tax)
        assertEquals(9970L, etf.settlementAmount)
        assertEquals(9950L, stock.settlementAmount)
    }

    @Test
    fun suppliedSellFeeAndTaxOverrideEstimates() {
        val settlement = engine.calculateSettlement(
            shares = 100,
            price = 100.0,
            tradeMode = TradeMode.ODD_LOT,
            side = TradeSide.SELL,
            actualFee = 5,
            actualTax = 13,
        )
        assertEquals(10000L, settlement.tradeAmount)
        assertEquals(5L, settlement.commission)
        assertEquals(13L, settlement.tax)
        assertEquals(9982L, settlement.settlementAmount)
    }

    @Test
    fun dividendPolicyMatchesFrozenThresholdAndTransferFee() {
        assertEquals(
            DividendCalculationResult(400, 0, 10, 390),
            engine.calculateNetDividend(DividendCalculationInput(1000, 0.4)),
        )
        assertEquals(
            DividendCalculationResult(20000, 422, 10, 19568),
            engine.calculateNetDividend(DividendCalculationInput(10000, 2.0)),
        )
    }

    @Test
    fun canonical00878SummaryMatchesFrozenCore() {
        val result = engine.calculateInstrument(
            InstrumentCalculationInput(
                symbol = "00878",
                currentPrice = 22.0,
                liquidationTradeMode = TradeMode.ROUND_LOT,
                dividendFrequency = 4,
                latestDividendPerShare = 0.4,
                transactions = listOf(
                    TradeRecord("b1", TradeSide.BUY, TradeMode.ROUND_LOT, 1000, 20.0, "2026-02-01"),
                ),
                dividendRecords = listOf(
                    DividendRecord("d1", "2026-03-01", 0.4, 1000),
                ),
            ),
        )
        assertEquals(1000L, result.totalShares)
        assertEquals(20020.0, result.totalInvestmentCost, 0.0)
        assertEquals(22000L, result.currentMarketValue)
        assertEquals(20L, result.estimatedSellCommission)
        assertEquals(22L, result.estimatedSellTax)
        assertEquals(21958L, result.netLiquidationValue)
        assertEquals(1938.0, result.unrealizedProfit, 0.0)
        assertEquals(390L, result.totalDividendsReceived)
        assertEquals(2328.0, result.comprehensivePnL, 0.0)
        assertEquals(390L, result.nextEstimatedDividend)
    }

    @Test
    fun portfolioAggregatesCanonicalInstrumentSummariesOnly() {
        val result = engine.calculatePortfolio(
            PortfolioCalculationInput(
                listOf(
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
            ),
        )
        assertEquals(23800L, result.totalMarketValue)
        assertEquals(21670.0, result.totalInvestmentCost, 0.0)
        assertEquals(23756L, result.totalNetLiquidationValue)
        assertEquals(21L, result.totalEstimatedSellCommission)
        assertEquals(23L, result.totalEstimatedSellTax)
        assertEquals(2086.0, result.totalUnrealizedProfit, 0.0)
        assertEquals(9.63, result.totalUnrealizedROI, 0.0)
        assertEquals(400L, result.totalDividendsReceived)
        assertEquals(2486.0, result.comprehensivePnL, 0.0)
        assertEquals(7.56, result.instrumentSummaries.first { it.symbol == "0050" }.portfolioWeight, 0.0)
        assertEquals(92.44, result.instrumentSummaries.first { it.symbol == "00878" }.portfolioWeight, 0.0)
    }
}
