package tw.saietf.core.finance

import org.junit.Assert.assertEquals
import org.junit.Test
import tw.saietf.core.model.BrokerProfile
import tw.saietf.core.model.InstrumentType
import tw.saietf.core.model.TradeMode

class FinanceCoreLockTest {
    @Test
    fun publicResultSchemaMatchesLockInventory() {
        assertEquals(
            setOf(
                "symbol",
                "name",
                "currentPrice",
                "totalShares",
                "totalInvestmentCost",
                "averageCostPerShare",
                "currentMarketValue",
                "estimatedSellCommission",
                "estimatedSellTax",
                "netLiquidationValue",
                "unrealizedProfit",
                "unrealizedROI",
                "realizedNetPnL",
                "comprehensivePnL",
                "totalDividendsReceived",
                "nextEstimatedDividend",
                "singlePeriodYield",
                "annualizedYield",
                "portfolioWeight",
            ),
            fieldNames(InstrumentCalculationResult::class.java),
        )
        assertEquals(
            setOf(
                "totalMarketValue",
                "totalInvestmentCost",
                "totalNetLiquidationValue",
                "totalEstimatedSellCommission",
                "totalEstimatedSellTax",
                "totalUnrealizedProfit",
                "totalUnrealizedROI",
                "realizedNetPnL",
                "comprehensivePnL",
                "totalPnl",
                "totalDividendsReceived",
                "nextEstimatedDividendTotal",
                "instrumentSummaries",
            ),
            fieldNames(PortfolioCalculationResult::class.java),
        )
        assertEquals(
            setOf(
                "grossDividend",
                "supplementaryHealthPremium",
                "transferFee",
                "netDividend",
            ),
            fieldNames(DividendCalculationResult::class.java),
        )
    }

    @Test
    fun frozenBrokerAndPolicyValuesCannotDrift() {
        val profile = BrokerProfile.DEFAULT
        assertEquals(0.001425, profile.commissionRate, 0.0)
        assertEquals(0.65, profile.commissionDiscount, 0.0)
        assertEquals(20L, profile.minimumCommissionRoundLot)
        assertEquals(1L, profile.minimumCommissionOddLot)
        assertEquals(0.001, profile.etfSellTaxRate, 0.0)
        assertEquals(0.003, profile.stockSellTaxRate, 0.0)

        val engine = DefaultFinanceEngine()
        assertEquals(
            DividendCalculationResult(20000, 422, 10, 19568),
            engine.calculateNetDividend(DividendCalculationInput(10000, 2.0)),
        )
    }

    @Test
    fun golden0050AndPartialSellRemainLocked() {
        val engine = DefaultFinanceEngine()
        assertEquals(
            TradeSettlement(1648, 2, 0, 1650),
            engine.calculateSettlement(
                shares = 20,
                price = 82.4,
                tradeMode = TradeMode.ODD_LOT,
                side = TradeSide.BUY,
                instrumentType = InstrumentType.ETF,
                actualFee = 2,
            ),
        )

        val projection = DefaultLedgerProjector().project(
            listOf(
                LedgerEntry("b1", "0050", LedgerEntryKind.BUY, "2026-01-01", 100, 10.0, TradeMode.ROUND_LOT),
                LedgerEntry("b2", "0050", LedgerEntryKind.BUY, "2026-01-02", 100, 20.0, TradeMode.ROUND_LOT),
                LedgerEntry("s1", "0050", LedgerEntryKind.SELL, "2026-01-03", 50, 30.0, TradeMode.ROUND_LOT),
            ),
        )
        assertEquals(150L, projection.totalShares)
        assertEquals(2280.0, projection.totalInvestmentCost, 0.0)
        assertEquals(719.0, projection.realizedNetPnL, 0.0)
    }

    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields
            .map { it.name }
            .filterNot { it.startsWith("$") }
            .toSet()
}
