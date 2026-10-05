package tw.saietf.app

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaiwanTechnicalEngineTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun `session policy uses Taiwan stock hours and minute-end buckets`() {
        val date = LocalDate.of(2026, 10, 5)
        val open = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val noon = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val afterClose = date.atTime(13, 33).atZone(zone).toInstant().toEpochMilli()
        val tooLate = date.atTime(13, 35).atZone(zone).toInstant().toEpochMilli()

        assertTrue(TaiwanIntradaySessionPolicy.isRegularSession(open))
        assertTrue(TaiwanIntradaySessionPolicy.isRegularSession(noon))
        assertTrue(TaiwanIntradaySessionPolicy.isAcceptedClosePrint(afterClose))
        assertFalse(TaiwanIntradaySessionPolicy.isAcceptedClosePrint(tooLate))
        assertEquals(open + 60_000L, TaiwanIntradaySessionPolicy.minuteBucketEnd(open + 1_000L))
    }

    @Test
    fun `indicator engine returns deterministic SMA RSI and MACD series`() {
        val bars = (1..80).map { index ->
            TaiwanDailyBar(
                epochMillis = index * 86_400_000L,
                open = 100.0 + index,
                high = 101.0 + index,
                low = 99.0 + index,
                close = 100.0 + index,
                volume = 1_000L + index,
            )
        }

        val sma = TaiwanTechnicalEngine.sma(bars, 5)
        val rsi = TaiwanTechnicalEngine.rsi(bars, 14)
        val macd = TaiwanTechnicalEngine.macd(bars)

        assertEquals(76, sma.size)
        assertEquals(103.0, sma.first().value, 0.000001)
        assertTrue(rsi.isNotEmpty())
        assertEquals(100.0, rsi.last().value, 0.000001)
        assertTrue(macd.macd.isNotEmpty())
        assertTrue(macd.signal.isNotEmpty())
        assertTrue(macd.histogram.isNotEmpty())
    }
}
