package tw.saietf.core.market

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataCenterArbitrationTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun `out of order Fugle sequence never overwrites hot store`() {
        val now = epoch("2026-10-05", 10, 0)
        val center = MarketDataCenter(providers = emptyList())

        assertTrue(
            center.acceptStreamingQuote(
                quote(price = 1425.0, time = now, sequence = 200L),
                nowEpochMillis = now,
                currentTaipeiDate = "2026-10-05",
            ),
        )
        assertFalse(
            center.acceptStreamingQuote(
                quote(price = 1424.0, time = now + 1_000L, sequence = 199L),
                nowEpochMillis = now + 1_000L,
                currentTaipeiDate = "2026-10-05",
            ),
        )

        val current = center.memoryQuotes(setOf("2330")).getValue("2330")
        assertEquals(1425.0, current.price, 0.0)
        assertEquals(200L, current.sequence)
    }

    @Test
    fun `prior session stream quote is rejected before valuation cache`() {
        val today = epoch("2026-10-05", 10, 0)
        val yesterday = epoch("2026-10-02", 13, 30)
        val center = MarketDataCenter(providers = emptyList())

        val accepted = center.acceptStreamingQuote(
            quote(price = 1400.0, time = yesterday, sequence = 50L),
            nowEpochMillis = today,
            currentTaipeiDate = "2026-10-05",
        )

        assertFalse(accepted)
        assertTrue(center.memoryQuotes().isEmpty())
        assertTrue(center.cachedQuotes(setOf("2330")).isEmpty())
    }

    private fun quote(
        price: Double,
        time: Long,
        sequence: Long,
    ) = MarketQuote(
        symbol = "2330",
        price = price,
        asOfEpochMillis = time,
        source = MarketSource.FUGLE,
        quality = QuoteQuality.LIVE,
        sourceTimestampEpochMillis = time,
        receivedAtEpochMillis = time,
        sessionDate = "2026-10-05",
        sequence = sequence,
    )

    private fun epoch(date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}
