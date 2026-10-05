package tw.saietf.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tw.saietf.core.database.entity.LedgerEntryEntity
import tw.saietf.core.database.entity.PortfolioEntity

@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {
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
    fun schemaV1EnforcesIdempotencyAndForeignKeys() = runBlocking {
        database.portfolioDao().insert(
            PortfolioEntity(
                id = "actual-1",
                name = "我的投資組合",
                kind = "ACTUAL",
                createdAtEpochMillis = 1L,
            ),
        )
        database.ledgerDao().insert(entry(id = "e1", idempotencyKey = "command-1"))

        assertThrows(Exception::class.java) {
            runBlocking {
                database.ledgerDao().insert(entry(id = "e2", idempotencyKey = "command-1"))
            }
        }
        assertThrows(Exception::class.java) {
            runBlocking {
                database.ledgerDao().insert(
                    entry(
                        id = "e3",
                        idempotencyKey = "command-3",
                        portfolioId = "missing",
                    ),
                )
            }
        }

        assertEquals(1L, database.ledgerDao().count("actual-1"))
    }

    @Test
    fun ledgerTableRejectsUpdateAndDelete() = runBlocking {
        database.portfolioDao().insert(
            PortfolioEntity(
                id = "actual-1",
                name = "我的投資組合",
                kind = "ACTUAL",
                createdAtEpochMillis = 1L,
            ),
        )
        database.ledgerDao().insert(entry(id = "e1", idempotencyKey = "command-1"))

        assertThrows(Exception::class.java) {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE ledger_entries SET note = 'changed' WHERE id = 'e1'",
            )
        }
        assertThrows(Exception::class.java) {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM ledger_entries WHERE id = 'e1'",
            )
        }

        assertEquals("原始資料", database.ledgerDao().findById("e1")?.note)
    }

    @Test
    fun databaseReportsSchemaVersionFour() {
        assertEquals(4, database.openHelper.readableDatabase.version)
    }

    private fun entry(
        id: String,
        idempotencyKey: String,
        portfolioId: String = "actual-1",
    ) = LedgerEntryEntity(
        id = id,
        portfolioId = portfolioId,
        idempotencyKey = idempotencyKey,
        entryType = "BUY",
        symbol = "0050",
        shares = 20,
        price = 82.4,
        tradeMode = "ODD_LOT",
        actualFee = 2,
        actualTax = null,
        occurredAtEpochMillis = 1_780_000_000_000L,
        tradeDateTaipei = "2026-10-04",
        note = "原始資料",
        correctionOfEntryId = null,
        correctionReason = null,
        createdAtEpochMillis = 1_780_000_000_100L,
    )
}
