package tw.saietf.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity
import tw.saietf.core.database.entity.PortfolioEntity

@RunWith(AndroidJUnit4::class)
class PerformanceHistoryDatabaseTest {
    private lateinit var database: SaiEtfDatabase

    @Before
    fun setUp() {
        database = SaiEtfDatabase.buildInMemoryForTests(ApplicationProvider.getApplicationContext())
        database.portfolioDao().insertBlocking(
            PortfolioEntity(
                id = "default",
                name = "預設投資組合",
                kind = "REAL",
                createdAtEpochMillis = 1L,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun dailyAndIntradayHistoryPersist() {
        database.dailySnapshotDao().upsertBlocking(
            DailySnapshotEntity(
                id = "daily-1",
                portfolioId = "default",
                taipeiDate = "2026-10-05",
                sourceRevision = "test-v2",
                capturedAtEpochMillis = 100L,
                totalMarketValue = 12_000L,
                totalInvestmentCost = 10_000.0,
                totalNetLiquidationValue = 12_000L,
                totalUnrealizedProfit = 2_000.0,
                realizedNetPnL = 100.0,
                totalDividendsReceived = 0L,
                comprehensivePnL = 2_100.0,
                dailyMarketPnL = 350L,
            ),
        )
        database.intradayPortfolioPointDao().upsertBlocking(
            IntradayPortfolioPointEntity(
                id = "point-1",
                portfolioId = "default",
                taipeiDate = "2026-10-05",
                bucketEpochMillis = 90L,
                capturedAtEpochMillis = 100L,
                totalMarketValue = 12_000L,
                todayPnl = 350L,
                totalPnl = 2_000.0,
                quotedHoldingCount = 2,
                expectedHoldingCount = 2,
                sourceRevision = "test-v2",
            ),
        )

        assertEquals(
            350L,
            database.dailySnapshotDao().recentBlocking("default", 1).single().dailyMarketPnL,
        )
        assertEquals(
            1,
            database.intradayPortfolioPointDao().countForDateBlocking("default", "2026-10-05"),
        )
        assertEquals(2, database.openHelper.readableDatabase.version)
    }
}
