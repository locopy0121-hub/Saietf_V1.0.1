package tw.saietf.core.market

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketDataV2ContractTest {
    @Test
    fun `memory hot store publishes one normalized StateFlow snapshot`() {
        val store = MemoryMarketStore()
        store.publish(
            listOf(
                MarketQuote(
                    symbol = " 2330 ",
                    price = 1425.0,
                    previousClose = 1400.0,
                    asOfEpochMillis = 1_000L,
                    source = MarketSource.TWSE_MIS,
                    fallbackLevel = 2,
                ),
            ),
        )

        val snapshot = store.quotes.value
        assertEquals(setOf("2330"), snapshot.keys)
        assertEquals(1425.0, snapshot.getValue("2330").price, 0.0)
        assertEquals(2, snapshot.getValue("2330").fallbackLevel)
    }

    @Test
    fun `polling provider bridge exposes V2 capability without breaking legacy provider`() = runBlocking {
        val legacy = object : MarketQuoteProvider {
            override val source = MarketSource.TWSE_MIS

            override fun fetch(symbols: Set<String>): Map<String, MarketQuote> =
                symbols.associateWith { symbol ->
                    MarketQuote(
                        symbol = symbol,
                        price = 100.0,
                        asOfEpochMillis = 1_000L,
                        source = source,
                    )
                }
        }
        val provider = PollingMarketDataProviderAdapter(legacy)

        provider.connect()
        provider.subscribe(setOf("0050", "2330"))
        provider.unsubscribe(setOf("0050"))
        val rows = provider.fetchSnapshot(setOf("2330"))

        assertEquals(setOf(MarketProviderCapability.POLL), provider.capabilities)
        assertEquals(setOf("2330"), provider.subscribedSnapshot())
        assertEquals(100.0, rows.getValue("2330").price, 0.0)
        assertTrue(provider.health(System.currentTimeMillis()).lastSuccessEpochMillis != null)
    }

    @Test
    fun `market data center publishes accepted quotes into memory SSOT`() {
        val source = object : MarketQuoteProvider {
            override val source = MarketSource.TWSE_MIS

            override fun fetch(symbols: Set<String>): Map<String, MarketQuote> =
                mapOf(
                    "0050" to MarketQuote(
                        symbol = "0050",
                        price = 61.5,
                        asOfEpochMillis = 1_781_000_000_000L,
                        source = source,
                    ),
                )
        }
        val center = MarketDataCenter(
            providers = listOf(source),
            liveThresholdMillis = Long.MAX_VALUE,
        )

        center.refresh(
            symbols = setOf("0050"),
            nowEpochMillis = 1_781_000_000_000L,
            currentTaipeiDate = "2026-06-09",
            tradingSessionActive = false,
        )

        assertEquals(61.5, center.quotesState.value.getValue("0050").price, 0.0)
        assertEquals(61.5, center.memoryQuotes(setOf("0050")).getValue("0050").price, 0.0)
    }
}
