package tw.saietf.core.market

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class MarketStreamingPriorityTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun `fresh Fugle stream wins before polling fallback`() {
        val now = epoch("2026-10-05", 10, 0)
        val polling = CountingProvider()
        val center = MarketDataCenter(listOf(polling))

        center.acceptStreamingQuote(
            MarketQuote(
                symbol = "2330",
                price = 1425.0,
                asOfEpochMillis = now,
                source = MarketSource.FUGLE,
                sourceTimestampEpochMillis = now,
                receivedAtEpochMillis = now,
                sessionDate = "2026-10-05",
                sequence = 100L,
            ),
            nowEpochMillis = now,
            currentTaipeiDate = "2026-10-05",
        )

        val batch = center.refresh(
            symbols = setOf("2330"),
            nowEpochMillis = now + 1_000L,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )

        assertEquals(0, polling.fetchCount)
        assertEquals(MarketSource.FUGLE, batch.quotes.getValue("2330").source)
        assertEquals(1425.0, batch.quotes.getValue("2330").price, 0.0)
    }

    @Test
    fun `aged stream allows polling fallback to verify current price`() {
        val old = epoch("2026-10-05", 9, 58)
        val now = epoch("2026-10-05", 10, 0)
        val polling = CountingProvider()
        val center = MarketDataCenter(listOf(polling))

        center.acceptStreamingQuote(
            MarketQuote(
                symbol = "2330",
                price = 1420.0,
                asOfEpochMillis = old,
                source = MarketSource.FUGLE,
                sourceTimestampEpochMillis = old,
                receivedAtEpochMillis = old,
                sessionDate = "2026-10-05",
            ),
            nowEpochMillis = old,
            currentTaipeiDate = "2026-10-05",
        )

        val batch = center.refresh(
            symbols = setOf("2330"),
            nowEpochMillis = now,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )

        assertEquals(1, polling.fetchCount)
        assertEquals(MarketSource.TWSE_MIS, batch.quotes.getValue("2330").source)
        assertEquals(1426.0, batch.quotes.getValue("2330").price, 0.0)
    }

    private fun epoch(date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private class CountingProvider : MarketQuoteProvider {
        override val source = MarketSource.TWSE_MIS
        var fetchCount: Int = 0

        override fun fetch(symbols: Set<String>): Map<String, MarketQuote> {
            fetchCount += 1
            val now = System.currentTimeMillis()
            return symbols.associateWith { symbol ->
                MarketQuote(
                    symbol = symbol,
                    price = 1426.0,
                    asOfEpochMillis = now,
                    source = source,
                )
            }
        }
    }
}
