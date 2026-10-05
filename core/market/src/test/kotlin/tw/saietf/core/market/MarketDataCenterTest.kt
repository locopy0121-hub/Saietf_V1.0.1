package tw.saietf.core.market

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataCenterTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun `provider priority fills unresolved symbols without overwriting primary`() {
        val now = epoch("2026-10-05", 10, 0)
        val primary = FakeProvider(
            MarketSource.TWSE_MIS,
            mapOf("0050" to quote("0050", 61.5, now, MarketSource.TWSE_MIS)),
        )
        val secondary = FakeProvider(
            MarketSource.YAHOO,
            mapOf(
                "0050" to quote("0050", 99.0, now, MarketSource.YAHOO),
                "2330" to quote("2330", 1200.0, now, MarketSource.YAHOO),
            ),
        )

        val batch = MarketDataCenter(listOf(primary, secondary)).refresh(
            symbols = setOf("0050", "2330"),
            nowEpochMillis = now,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )

        assertEquals(61.5, batch.quotes.getValue("0050").price, 0.0)
        assertEquals(1200.0, batch.quotes.getValue("2330").price, 0.0)
        assertEquals(listOf(MarketSource.TWSE_MIS, MarketSource.YAHOO), batch.sourcesTried)
        assertTrue(batch.unresolvedSymbols.isEmpty())
    }

    @Test
    fun `prior session cache cannot freeze an active session valuation`() {
        val friday = epoch("2026-10-02", 13, 30)
        val monday = epoch("2026-10-05", 10, 0)
        val source = MutableFakeProvider(
            MarketSource.TWSE_MIS,
            mapOf("0050" to quote("0050", 60.0, friday, MarketSource.TWSE_MIS)),
        )
        val center = MarketDataCenter(listOf(source))

        center.refresh(
            symbols = setOf("0050"),
            nowEpochMillis = friday,
            currentTaipeiDate = "2026-10-02",
            tradingSessionActive = false,
        )

        source.rows = emptyMap()
        val mondayBatch = center.refresh(
            symbols = setOf("0050"),
            nowEpochMillis = monday,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )

        assertTrue(mondayBatch.quotes.isEmpty())
        assertTrue("0050" in mondayBatch.staleQuotes)
        assertTrue("0050" in mondayBatch.unresolvedSymbols)
    }


    @Test
    fun `provider polling is paced while one second UI refresh reuses synchronized cache`() {
        val now = epoch("2026-10-05", 10, 0)
        val primary = CountingProvider(
            source = MarketSource.TWSE_MIS,
            rows = mapOf("0050" to quote("0050", 61.5, now, MarketSource.TWSE_MIS)),
        )
        val center = MarketDataCenter(
            providers = listOf(primary),
            providerPolicies = mapOf(
                MarketSource.TWSE_MIS to MarketProviderPolicy(minFetchIntervalMillis = 1_000L),
            ),
        )

        val first = center.refresh(
            symbols = setOf("0050"),
            nowEpochMillis = now,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )
        val halfSecond = center.refresh(
            symbols = setOf("0050"),
            nowEpochMillis = now + 500L,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )
        val oneSecond = center.refresh(
            symbols = setOf("0050"),
            nowEpochMillis = now + 1_000L,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )

        assertEquals(2, primary.fetchCount)
        assertEquals(61.5, first.quotes.getValue("0050").price, 0.0)
        assertEquals(61.5, halfSecond.quotes.getValue("0050").price, 0.0)
        assertEquals(
            ProviderAvailability.THROTTLED,
            halfSecond.providerHealth.single().availability,
        )
        assertEquals(61.5, oneSecond.quotes.getValue("0050").price, 0.0)
    }

    @Test
    fun `rate limited primary enters cooldown and fallback continues without hammering source`() {
        val now = epoch("2026-10-05", 10, 0)
        val primary = RateLimitedProvider(MarketSource.TWSE_MIS)
        val fallback = CountingProvider(
            source = MarketSource.YAHOO,
            rows = mapOf("2330" to quote("2330", 1200.0, now, MarketSource.YAHOO)),
        )
        val center = MarketDataCenter(
            providers = listOf(primary, fallback),
            providerPolicies = mapOf(
                MarketSource.TWSE_MIS to MarketProviderPolicy(minFetchIntervalMillis = 1_000L),
                MarketSource.YAHOO to MarketProviderPolicy(minFetchIntervalMillis = 15_000L),
            ),
        )

        val first = center.refresh(
            symbols = setOf("2330"),
            nowEpochMillis = now,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )
        val second = center.refresh(
            symbols = setOf("2330"),
            nowEpochMillis = now + 1_000L,
            currentTaipeiDate = "2026-10-05",
            tradingSessionActive = true,
        )

        assertEquals(1, primary.fetchCount)
        assertEquals(1, fallback.fetchCount)
        assertEquals(1200.0, first.quotes.getValue("2330").price, 0.0)
        assertEquals(1200.0, second.quotes.getValue("2330").price, 0.0)
        assertEquals(
            ProviderAvailability.COOLDOWN,
            second.providerHealth.first { it.source == MarketSource.TWSE_MIS }.availability,
        )
    }

    private fun quote(symbol: String, price: Double, time: Long, source: MarketSource) =
        MarketQuote(
            symbol = symbol,
            price = price,
            previousClose = price - 1.0,
            asOfEpochMillis = time,
            source = source,
        )

    private fun epoch(date: String, hour: Int, minute: Int): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()


    private class CountingProvider(
        override val source: MarketSource,
        private val rows: Map<String, MarketQuote>,
    ) : MarketQuoteProvider {
        var fetchCount: Int = 0

        override fun fetch(symbols: Set<String>): Map<String, MarketQuote> {
            fetchCount += 1
            return rows.filterKeys { it in symbols }
        }
    }

    private class RateLimitedProvider(
        override val source: MarketSource,
    ) : MarketQuoteProvider {
        var fetchCount: Int = 0

        override fun fetch(symbols: Set<String>): Map<String, MarketQuote> {
            fetchCount += 1
            throw MarketProviderException(
                httpStatusCode = 429,
                retryAfterMillis = 60_000L,
                message = "HTTP 429",
            )
        }
    }

    private class FakeProvider(
        override val source: MarketSource,
        private val rows: Map<String, MarketQuote>,
    ) : MarketQuoteProvider {
        override fun fetch(symbols: Set<String>): Map<String, MarketQuote> =
            rows.filterKeys { it in symbols }
    }

    private class MutableFakeProvider(
        override val source: MarketSource,
        var rows: Map<String, MarketQuote>,
    ) : MarketQuoteProvider {
        override fun fetch(symbols: Set<String>): Map<String, MarketQuote> =
            rows.filterKeys { it in symbols }
    }
}
