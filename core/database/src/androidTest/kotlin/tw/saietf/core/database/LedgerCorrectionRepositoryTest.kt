package tw.saietf.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tw.saietf.core.finance.LedgerEntryKind
import tw.saietf.core.model.TradeMode

@RunWith(AndroidJUnit4::class)
class LedgerCorrectionRepositoryTest {
    private lateinit var database: SaiEtfDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        database = SaiEtfDatabase.buildInMemoryForTests(
            ApplicationProvider.getApplicationContext(),
        )
        repository = LedgerRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun editAndDeleteUseAppendOnlyCorrectionsButExposeEffectiveTransactions() {
        repository.addTrade(
            trade(
                side = LedgerEntryKind.BUY,
                symbol = "0050",
                shares = 100,
                price = 100.0,
                date = "2026-10-01",
            ),
        )
        repository.addTrade(
            trade(
                side = LedgerEntryKind.SELL,
                symbol = "0050",
                shares = 20,
                price = 110.0,
                date = "2026-10-02",
            ),
        )

        val originalPage = repository.transactionPage(0, 10)
        val originalBuy = originalPage.rows.single { it.side == LedgerEntryKind.BUY }
        val originalSell = originalPage.rows.single { it.side == LedgerEntryKind.SELL }

        repository.correctTrade(
            originalBuy.id,
            trade(
                side = LedgerEntryKind.BUY,
                symbol = "0050",
                shares = 120,
                price = 99.5,
                date = "2026-10-01",
                note = "修正買進資料",
            ),
        )

        val editedPage = repository.transactionPage(0, 10)
        val editedBuy = editedPage.rows.single { it.side == LedgerEntryKind.BUY }
        assertEquals(2L, editedPage.totalCount)
        assertEquals(120L, editedBuy.shares)
        assertEquals(99.5, editedBuy.price, 0.0001)
        assertTrue(editedBuy.isEdited)
        assertEquals(3L, database.ledgerDao().countBlocking(LedgerRepository.DEFAULT_PORTFOLIO_ID))

        repository.deleteTrade(originalSell.id)

        val afterDelete = repository.transactionPage(0, 10)
        assertEquals(1L, afterDelete.totalCount)
        assertEquals(LedgerEntryKind.BUY, afterDelete.rows.single().side)
        assertEquals(4L, database.ledgerDao().countBlocking(LedgerRepository.DEFAULT_PORTFOLIO_ID))
        assertEquals(120L, repository.loadDashboard().holdings.single().shares)
    }

    @Test
    fun deleteRejectsHistoryThatWouldCreateOversell() {
        repository.addTrade(
            trade(
                side = LedgerEntryKind.BUY,
                symbol = "2330",
                shares = 100,
                price = 1000.0,
                date = "2026-10-01",
            ),
        )
        repository.addTrade(
            trade(
                side = LedgerEntryKind.SELL,
                symbol = "2330",
                shares = 50,
                price = 1010.0,
                date = "2026-10-02",
            ),
        )
        val buy = repository.transactionPage(0, 10).rows
            .single { it.side == LedgerEntryKind.BUY }

        val error = assertThrows(IllegalArgumentException::class.java) {
            repository.deleteTrade(buy.id)
        }

        assertTrue(error.message.orEmpty().contains("歷史賣出超過可用持股"))
        assertEquals(2L, database.ledgerDao().countBlocking(LedgerRepository.DEFAULT_PORTFOLIO_ID))
        assertEquals(50L, repository.loadDashboard().holdings.single().shares)
    }

    private fun trade(
        side: LedgerEntryKind,
        symbol: String,
        shares: Long,
        price: Double,
        date: String,
        note: String? = null,
    ) = LedgerRepository.AddTradeCommand(
        side = side,
        symbol = symbol,
        shares = shares,
        price = price,
        tradeMode = TradeMode.ODD_LOT,
        tradeDateTaipei = date,
        actualFee = 0,
        actualTax = if (side == LedgerEntryKind.SELL) 0 else null,
        note = note,
    )
}
