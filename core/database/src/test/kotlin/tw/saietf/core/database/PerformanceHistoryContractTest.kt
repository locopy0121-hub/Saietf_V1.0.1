package tw.saietf.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity

class PerformanceHistoryContractTest {
    @Test
    fun dailySnapshotCarriesDailyMarketPnl() {
        val fields = DailySnapshotEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("dailyMarketPnL" in fields)
        assertTrue("taipeiDate" in fields)
        assertTrue("sourceRevision" in fields)
    }

    @Test
    fun intradayPointCarriesStableBucketAndPortfolioValuation() {
        val fields = IntradayPortfolioPointEntity::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("bucketEpochMillis" in fields)
        assertTrue("capturedAtEpochMillis" in fields)
        assertTrue("totalMarketValue" in fields)
        assertTrue("todayPnl" in fields)
        assertTrue("totalPnl" in fields)
        assertEquals(15_000L, PerformanceHistoryRepository.INTRADAY_BUCKET_MILLIS)
    }
}
