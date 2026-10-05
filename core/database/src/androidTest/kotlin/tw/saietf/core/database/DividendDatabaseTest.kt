package tw.saietf.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tw.saietf.core.database.entity.DividendEventEntity
import tw.saietf.core.database.entity.PortfolioEntity

@RunWith(AndroidJUnit4::class)
class DividendDatabaseTest {
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
    fun announcementCanBeUpdatedInPlace() {
        val base = DividendEventEntity(
            id = "dividend:default:0050:2026-10-20",
            portfolioId = "default",
            symbol = "0050",
            exDateTaipei = "2026-10-20",
            recordDateTaipei = "2026-10-22",
            paymentDateTaipei = null,
            cashPerShare = 1.0,
            status = "ANNOUNCED",
            sharesAtEntry = 100,
            estimatedCash = 100,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        database.dividendEventDao().upsertBlocking(base)
        database.dividendEventDao().upsertBlocking(
            base.copy(
                cashPerShare = 1.25,
                paymentDateTaipei = "2026-11-15",
                status = "CONFIRMED",
                estimatedCash = 125,
                updatedAtEpochMillis = 2L,
            ),
        )

        val row = database.dividendEventDao()
            .findBlocking("default", "0050", "2026-10-20")
        assertEquals("CONFIRMED", row?.status)
        assertEquals(1.25, row?.cashPerShare ?: 0.0, 0.0)
        assertEquals(125L, row?.estimatedCash)
        assertEquals(3, database.openHelper.readableDatabase.version)
    }
}
