package tw.saietf.core.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketArbitratorTest {
    private val arbitrator = MarketArbitrator()

    @Test
    fun `older session can never replace newer session`() {
        val existing = quote(
            source = MarketSource.TWSE_MIS,
            price = 100.0,
            sourceTime = 2_000L,
            sessionDate = "2026-10-05",
        )
        val candidate = quote(
            source = MarketSource.FUGLE,
            price = 99.0,
            sourceTime = 9_000L,
            sessionDate = "2026-10-02",
        )

        val decision = arbitrator.decide(existing, candidate)

        assertFalse(decision.accepted)
        assertEquals(ArbitrationReason.REJECT_OLDER_SESSION, decision.reason)
    }

    @Test
    fun `same source lower sequence is rejected even when received later`() {
        val existing = quote(
            source = MarketSource.FUGLE,
            price = 1425.0,
            sourceTime = 10_000L,
            sessionDate = "2026-10-05",
            sequence = 200L,
            receivedAt = 10_100L,
        )
        val candidate = quote(
            source = MarketSource.FUGLE,
            price = 1424.0,
            sourceTime = 11_000L,
            sessionDate = "2026-10-05",
            sequence = 199L,
            receivedAt = 11_100L,
        )

        val decision = arbitrator.decide(existing, candidate)

        assertFalse(decision.accepted)
        assertEquals(ArbitrationReason.REJECT_OUT_OF_ORDER_SEQUENCE, decision.reason)
    }

    @Test
    fun `newer verified fallback can replace older primary quote`() {
        val existing = quote(
            source = MarketSource.FUGLE,
            price = 1420.0,
            sourceTime = 10_000L,
            sessionDate = "2026-10-05",
            fallbackLevel = 0,
        )
        val candidate = quote(
            source = MarketSource.TWSE_MIS,
            price = 1425.0,
            sourceTime = 12_000L,
            sessionDate = "2026-10-05",
            fallbackLevel = 2,
        )

        val decision = arbitrator.decide(existing, candidate)

        assertTrue(decision.accepted)
        assertEquals(ArbitrationReason.ACCEPT_NEWER_TIMESTAMP, decision.reason)
    }

    @Test
    fun `equal timestamp prefers higher priority source`() {
        val existing = quote(
            source = MarketSource.YAHOO,
            price = 1425.0,
            sourceTime = 12_000L,
            sessionDate = "2026-10-05",
            fallbackLevel = 0,
        )
        val candidate = quote(
            source = MarketSource.FUGLE,
            price = 1425.0,
            sourceTime = 12_000L,
            sessionDate = "2026-10-05",
            fallbackLevel = 0,
        )

        val decision = arbitrator.decide(existing, candidate)

        assertTrue(decision.accepted)
        assertEquals(ArbitrationReason.ACCEPT_HIGHER_SOURCE_PRIORITY, decision.reason)
    }

    private fun quote(
        source: MarketSource,
        price: Double,
        sourceTime: Long,
        sessionDate: String,
        sequence: Long? = null,
        receivedAt: Long = sourceTime,
        fallbackLevel: Int = 0,
    ) = MarketQuote(
        symbol = "2330",
        price = price,
        asOfEpochMillis = sourceTime,
        source = source,
        quality = QuoteQuality.LIVE,
        sourceTimestampEpochMillis = sourceTime,
        receivedAtEpochMillis = receivedAt,
        sessionDate = sessionDate,
        fallbackLevel = fallbackLevel,
        sequence = sequence,
    )
}
