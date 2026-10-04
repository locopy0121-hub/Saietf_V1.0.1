package tw.saietf.core.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tw.saietf.core.model.TradeMode

class LedgerProjectorGoldenTest {
    private val projector = DefaultLedgerProjector()

    @Test
    fun movingAveragePartialSellReleasesProportionalCost() {
        val result = projector.project(
            listOf(
                LedgerEntry("b1", "0050", LedgerEntryKind.BUY, "2026-01-01", 100, 10.0, TradeMode.ROUND_LOT),
                LedgerEntry("b2", "0050", LedgerEntryKind.BUY, "2026-01-02", 100, 20.0, TradeMode.ROUND_LOT),
                LedgerEntry("s1", "0050", LedgerEntryKind.SELL, "2026-01-03", 50, 30.0, TradeMode.ROUND_LOT),
            ),
        )

        assertEquals(150L, result.totalShares)
        assertEquals(2280.0, result.totalInvestmentCost, 0.0)
        assertEquals(719.0, result.realizedNetPnL, 0.0)
    }

    @Test
    fun suppliedActualFeesAndTaxesRemainSettlementTruth() {
        val result = projector.project(
            listOf(
                LedgerEntry(
                    id = "b1",
                    symbol = "0050",
                    kind = LedgerEntryKind.BUY,
                    date = "2026-01-01",
                    shares = 100,
                    price = 10.0,
                    tradeMode = TradeMode.ODD_LOT,
                    actualFee = 5,
                ),
                LedgerEntry(
                    id = "s1",
                    symbol = "0050",
                    kind = LedgerEntryKind.SELL,
                    date = "2026-01-02",
                    shares = 50,
                    price = 20.0,
                    tradeMode = TradeMode.ODD_LOT,
                    actualFee = 2,
                    actualTax = 7,
                ),
            ),
        )

        assertEquals(50L, result.totalShares)
        assertEquals(502.5, result.totalInvestmentCost, 0.0)
        assertEquals(488.5, result.realizedNetPnL, 0.0)
    }

    @Test
    fun oversellIsRejectedInsteadOfSilentlyClamped() {
        assertThrows(IllegalArgumentException::class.java) {
            projector.project(
                listOf(
                    LedgerEntry("b1", "0050", LedgerEntryKind.BUY, "2026-01-01", 100, 10.0, TradeMode.ODD_LOT),
                    LedgerEntry("s1", "0050", LedgerEntryKind.SELL, "2026-01-02", 101, 12.0, TradeMode.ODD_LOT),
                ),
            )
        }
    }

    @Test
    fun sameDayEntriesUseStableIdTieBreaking() {
        val buy = LedgerEntry("a-buy", "0050", LedgerEntryKind.BUY, "2026-01-01", 100, 10.0, TradeMode.ODD_LOT)
        val sell = LedgerEntry("b-sell", "0050", LedgerEntryKind.SELL, "2026-01-01", 50, 12.0, TradeMode.ODD_LOT)
        val forward = projector.project(listOf(buy, sell))
        val reversed = projector.project(listOf(sell, buy))

        assertEquals(forward, reversed)
        assertEquals(50L, reversed.totalShares)
    }
}
