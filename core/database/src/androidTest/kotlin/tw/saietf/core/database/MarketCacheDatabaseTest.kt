package tw.saietf.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tw.saietf.core.database.entity.MarketMinuteCandleEntity
import tw.saietf.core.database.entity.MarketQuoteSnapshotEntity

@RunWith(AndroidJUnit4::class)
class MarketCacheDatabaseTest {
    private lateinit var database: SaiEtfDatabase

    @Before
    fun setUp() {
        database = SaiEtfDatabase.buildInMemoryForTests(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun snapshotAndMinuteCandleUpsertRemainSingleSourceOfPersistence() = runBlocking {
        val dao = database.marketCacheDao()
        dao.upsertSnapshots(
            listOf(
                MarketQuoteSnapshotEntity(
                    symbol = "2330",
                    name = "台積電",
                    exchange = "TWSE",
                    market = "TSE",
                    price = 1425.0,
                    previousClose = 1400.0,
                    open = 1410.0,
                    high = 1430.0,
                    low = 1405.0,
                    volume = 1000L,
                    bid = 1424.0,
                    ask = 1425.0,
                    source = "FUGLE",
                    quality = "LIVE",
                    sourceTimestampEpochMillis = 1_800_000_000_000L,
                    receivedAtEpochMillis = 1_800_000_000_100L,
                    sessionDate = "2027-01-15",
                    fallbackLevel = 0,
                    sequence = 99L,
                    isClose = false,
                    persistedAtEpochMillis = 1_800_000_000_200L,
                ),
            ),
        )
        dao.upsertCandles(
            listOf(
                MarketMinuteCandleEntity(
                    symbol = "2330",
                    sessionDate = "2027-01-15",
                    bucketEpochMillis = 1_800_000_000_000L,
                    open = 1420.0,
                    high = 1425.0,
                    low = 1419.0,
                    close = 1425.0,
                    volume = 1000L,
                    source = "FUGLE",
                    updatedAtEpochMillis = 1_800_000_000_200L,
                ),
            ),
        )

        val snapshot = dao.observeSnapshots(setOf("2330")).first().single()
        val candle = dao.observeMinuteCandles("2330", "2027-01-15").first().single()

        assertEquals(1425.0, snapshot.price, 0.0)
        assertEquals("FUGLE", snapshot.source)
        assertEquals(1425.0, candle.close, 0.0)
        assertEquals(1000L, candle.volume)
    }
}
