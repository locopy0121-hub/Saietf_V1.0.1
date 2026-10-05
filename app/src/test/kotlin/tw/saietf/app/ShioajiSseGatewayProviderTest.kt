package tw.saietf.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.ProviderCircuitState

class ShioajiSseGatewayProviderTest {
    @Test
    fun `gateway SSE normalizes quote as fallback level one`() = runBlocking {
        val transport = FakeTransport()
        var now = 1_800_000_000_000L
        val provider = ShioajiSseGatewayProvider(
            endpointProvider = { "https://gateway.example.com/quotes" },
            bearerTokenProvider = { "token" },
            transport = transport,
            clockMillis = { now },
        )

        provider.replaceSubscriptions(setOf("2330"))
        assertTrue(transport.url.contains("symbols=2330"))
        assertEquals("token", transport.token)

        transport.listener.onOpen()
        transport.listener.onActivity()
        transport.listener.onEvent(
            """{"symbol":"2330","price":1425.0,"bid":1424.0,"ask":1425.0,"volume":1000,"timestamp":1800000000000,"sequence":88,"sessionDate":"2027-01-15"}""",
        )

        val quote = provider.fetchSnapshot(setOf("2330")).getValue("2330")
        assertEquals(MarketSource.SHIOAJI, quote.source)
        assertEquals(1425.0, quote.price, 0.0)
        assertEquals(1, quote.fallbackLevel)
        assertEquals(88L, quote.sequence)
        assertEquals(ProviderCircuitState.HEALTHY, provider.health(now).circuitState)

        provider.shutdownForTest()
    }

    @Test
    fun `subscription change reconnects SSE with only current symbols`() = runBlocking {
        val transport = FakeTransport()
        val provider = ShioajiSseGatewayProvider(
            endpointProvider = { "https://gateway.example.com/quotes" },
            bearerTokenProvider = { null },
            transport = transport,
        )

        provider.replaceSubscriptions(setOf("0050", "2330"))
        transport.listener.onOpen()
        provider.replaceSubscriptions(setOf("0050"))

        assertEquals(2, transport.openCount)
        assertTrue(transport.urls.last().contains("symbols=0050"))
        assertTrue(!transport.urls.last().contains("2330"))

        provider.shutdownForTest()
    }

    private class FakeTransport : ShioajiSseTransport {
        lateinit var listener: ShioajiSseListener
        var url: String = ""
        var token: String? = null
        var openCount: Int = 0
        val urls = mutableListOf<String>()

        override fun open(
            url: String,
            bearerToken: String?,
            listener: ShioajiSseListener,
        ): ShioajiSseConnection {
            this.url = url
            this.token = bearerToken
            this.listener = listener
            openCount += 1
            urls += url
            return object : ShioajiSseConnection {
                override fun cancel() = Unit
            }
        }
    }
}
