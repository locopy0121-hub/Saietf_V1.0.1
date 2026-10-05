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
import tw.saietf.core.database.entity.InstitutionalTradingEntity
import tw.saietf.core.database.entity.MonthlyRevenueEntity

@RunWith(AndroidJUnit4::class)
class StockDetailDatabaseTest {
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
    fun batchEntitiesUpsertByDataPeriodAndFlowImmediately() = runBlocking {
        val dao = database.stockDetailDao()
        dao.upsertInstitutional(
            listOf(
                InstitutionalTradingEntity(
                    symbol = "2330",
                    dataDate = "2026-10-05",
                    period = "2026-10-05",
                    foreignNetShares = 10L,
                    investmentTrustNetShares = 20L,
                    dealerNetShares = -5L,
                    source = "TWSE T86",
                    fetchedAtEpochMillis = 100L,
                    sourceUpdatedAtEpochMillis = null,
                    quality = "VERIFIED",
                    freshness = "FRESH",
                    rawRevision = "institutional-v1",
                ),
            ),
        )
        dao.upsertMonthlyRevenue(
            listOf(
                MonthlyRevenueEntity(
                    symbol = "2330",
                    dataDate = "2026-09-30",
                    period = "2026-09",
                    currentMonthRevenueTwd = 1000L,
                    previousMonthRevenueTwd = 900L,
                    lastYearMonthRevenueTwd = 800L,
                    monthOverMonthPct = 11.1,
                    yearOverYearPct = 25.0,
                    accumulatedRevenueTwd = 9000L,
                    source = "TWSE OpenAPI",
                    fetchedAtEpochMillis = 100L,
                    sourceUpdatedAtEpochMillis = null,
                    quality = "VERIFIED",
                    freshness = "FRESH",
                    rawRevision = "revenue-v1",
                ),
            ),
        )

        assertEquals(
            10L,
            dao.observeInstitutional("2330").first().single().foreignNetShares,
        )
        assertEquals(
            "2026-09",
            dao.observeMonthlyRevenue("2330").first().single().period,
        )
        assertEquals(5, database.openHelper.readableDatabase.version)
    }
}
