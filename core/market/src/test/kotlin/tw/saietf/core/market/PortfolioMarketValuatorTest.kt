package tw.saietf.core.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioMarketValuatorTest {
    @Test
    fun `valuation keeps today pnl and total holding pnl as different measures`() {
        val valuation = PortfolioMarketValuator().value(
            holdings = listOf(
                HoldingCost("0050", 100L, 5_500.0),
                HoldingCost("2330", 2L, 2_000.0),
            ),
            quotes = mapOf(
                "0050" to MarketQuote(
                    symbol = "0050",
                    price = 60.0,
                    previousClose = 59.0,
                    asOfEpochMillis = 1L,
                    source = MarketSource.TWSE_MIS,
                ),
                "2330" to MarketQuote(
                    symbol = "2330",
                    price = 1_100.0,
                    previousClose = 1_080.0,
                    asOfEpochMillis = 1L,
                    source = MarketSource.TWSE_MIS,
                ),
            ),
        )

        assertTrue(valuation.isComplete)
        assertEquals(8_200L, valuation.totalMarketValue)
        assertEquals(140L, valuation.todayPnl)
        assertEquals(700.0, valuation.totalPnl ?: 0.0, 0.0)
    }

    @Test
    fun `incomplete quotes refuse to publish a fake total asset value`() {
        val valuation = PortfolioMarketValuator().value(
            holdings = listOf(
                HoldingCost("0050", 100L, 5_500.0),
                HoldingCost("2330", 2L, 2_000.0),
            ),
            quotes = mapOf(
                "0050" to MarketQuote(
                    symbol = "0050",
                    price = 60.0,
                    previousClose = 59.0,
                    asOfEpochMillis = 1L,
                    source = MarketSource.TWSE_MIS,
                ),
            ),
        )

        assertFalse(valuation.isComplete)
        assertNull(valuation.totalMarketValue)
        assertNull(valuation.todayPnl)
        assertNull(valuation.totalPnl)
    }
}
