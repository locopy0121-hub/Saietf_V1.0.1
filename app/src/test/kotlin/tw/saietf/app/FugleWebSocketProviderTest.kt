package tw.saietf.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.ProviderAvailability

class FugleWebSocketProviderTest {
    @Test
    fun `auth subscribe trade and subscription diff work without reconnecting socket`() {
        val transport = FakeTransport()
        var now = 1_685_338_200_100L
        val provider = FugleWebSocketProvider(
            apiKeyProvider = { "test-api-key" },
            transport = transport,
            clockMillis = { now },
        )

        runBlocking {
            provider.replaceSubscriptions(setOf("2330"))
        }
        transport.open()

        assertTrue(transport.socket.messages.any { it.contains("\"event\":\"auth\"") })
        assertTrue(transport.socket.messages.none { it.contains("subscribe") })

        transport.message(
            """{"event":"authenticated","data":{"message":"Authenticated successfully"}}""",
        )
        assertTrue(
            transport.socket.messages.any {
                it.contains("\"event\":\"subscribe\"") &&
                    it.contains("\"2330\"") &&
                    it.contains("\"trades\"")
            },
        )

        transport.message(
            """{"event":"subscribed","data":{"id":"trade-2330","channel":"trades","symbol":"2330"}}""",
        )
        transport.message(
            """{"event":"data","channel":"trades","data":{"symbol":"2330","exchange":"TWSE","market":"TSE","bid":1424.0,"ask":1425.0,"price":1425.0,"volume":12345,"time":1685338200000000,"serial":6652422,"isClose":false}}""",
        )

        val quote = runBlocking {
            provider.fetchSnapshot(setOf("2330")).getValue("2330")
        }
        assertEquals(MarketSource.FUGLE, quote.source)
        assertEquals(1425.0, quote.price, 0.0)
        assertEquals(1424.0, quote.bid ?: 0.0, 0.0)
        assertEquals(1425.0, quote.ask ?: 0.0, 0.0)
        assertEquals(6_652_422L, quote.sequence)
        assertEquals(1_685_338_200_000L, quote.sourceTimestampEpochMillis)

        now += 1_000L
        runBlocking {
            provider.replaceSubscriptions(setOf("0050"))
        }
        assertTrue(
            transport.socket.messages.any {
                it.contains("\"event\":\"unsubscribe\"") &&
                    it.contains("\"trade-2330\"")
            },
        )
        assertTrue(
            transport.socket.messages.any {
                it.contains("\"event\":\"subscribe\"") &&
                    it.contains("\"0050\"")
            },
        )
        assertEquals(1, transport.connectCount)

        provider.shutdownForTest()
    }

    @Test
    fun `heartbeat drives provider health and trial trade never enters snapshot`() {
        val transport = FakeTransport()
        var now = 1_700_000_000_000L
        val provider = FugleWebSocketProvider(
            apiKeyProvider = { "test-api-key" },
            transport = transport,
            clockMillis = { now },
        )

        runBlocking {
            provider.replaceSubscriptions(setOf("0050"))
        }
        transport.open()
        transport.message("""{"event":"authenticated","data":{"message":"ok"}}""")
        transport.message("""{"event":"heartbeat","data":{"time":"ignored"}}""")

        assertEquals(ProviderAvailability.READY, provider.health(now).availability)

        transport.message(
            """{"event":"data","channel":"trades","data":{"symbol":"0050","price":100.0,"time":1700000000000000,"serial":1,"isTrial":true}}""",
        )
        val snapshot = runBlocking { provider.fetchSnapshot(setOf("0050")) }
        assertFalse(snapshot.containsKey("0050"))

        now += 76_000L
        assertEquals(ProviderAvailability.COOLDOWN, provider.health(now).availability)

        provider.shutdownForTest()
    }

    private class FakeTransport : FugleSocketTransport {
        val socket = FakeSocket()
        lateinit var listener: FugleSocketListener
        var connectCount: Int = 0

        override fun connect(url: String, listener: FugleSocketListener): FugleSocket {
            assertEquals(FugleWebSocketProvider.STREAMING_URL, url)
            connectCount += 1
            this.listener = listener
            return socket
        }

        fun open() {
            listener.onOpen(socket)
        }

        fun message(text: String) {
            listener.onText(socket, text)
        }
    }

    private class FakeSocket : FugleSocket {
        val messages = mutableListOf<String>()
        var closed = false
        var cancelled = false

        override fun send(text: String): Boolean {
            messages += text
            return true
        }

        override fun close(code: Int, reason: String): Boolean {
            closed = true
            return true
        }

        override fun cancel() {
            cancelled = true
        }
    }
}
