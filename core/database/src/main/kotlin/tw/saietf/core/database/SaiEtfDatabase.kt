package tw.saietf.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tw.saietf.core.database.dao.DailySnapshotDao
import tw.saietf.core.database.dao.DividendEventDao
import tw.saietf.core.database.dao.IntradayPortfolioPointDao
import tw.saietf.core.database.dao.LedgerDao
import tw.saietf.core.database.dao.MarketCacheDao
import tw.saietf.core.database.dao.PortfolioDao
import tw.saietf.core.database.dao.StockDetailDao
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.DividendEventEntity
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity
import tw.saietf.core.database.entity.LedgerEntryEntity
import tw.saietf.core.database.entity.MarketMinuteCandleEntity
import tw.saietf.core.database.entity.MarketQuoteSnapshotEntity
import tw.saietf.core.database.entity.ModelAllocationEntity
import tw.saietf.core.database.entity.PortfolioEntity
import tw.saietf.core.database.entity.AfterHoursEntity
import tw.saietf.core.database.entity.DividendReferenceEntity
import tw.saietf.core.database.entity.EtfComponentEntity
import tw.saietf.core.database.entity.InstitutionalTradingEntity
import tw.saietf.core.database.entity.MonthlyRevenueEntity
import tw.saietf.core.database.entity.QuarterlyFinancialEntity

@Database(
    entities = [
        PortfolioEntity::class,
        LedgerEntryEntity::class,
        ModelAllocationEntity::class,
        DailySnapshotEntity::class,
        IntradayPortfolioPointEntity::class,
        DividendEventEntity::class,
        MarketQuoteSnapshotEntity::class,
        MarketMinuteCandleEntity::class,
        InstitutionalTradingEntity::class,
        MonthlyRevenueEntity::class,
        QuarterlyFinancialEntity::class,
        EtfComponentEntity::class,
        DividendReferenceEntity::class,
        AfterHoursEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class SaiEtfDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun portfolioDao(): PortfolioDao
    abstract fun dailySnapshotDao(): DailySnapshotDao
    abstract fun intradayPortfolioPointDao(): IntradayPortfolioPointDao
    abstract fun dividendEventDao(): DividendEventDao
    abstract fun marketCacheDao(): MarketCacheDao
    abstract fun stockDetailDao(): StockDetailDao

    companion object {
        const val DATABASE_NAME = "saietf.db"
        const val SCHEMA_VERSION = 5

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE daily_snapshots
                    ADD COLUMN dailyMarketPnL INTEGER NOT NULL DEFAULT 0
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS intraday_portfolio_points (
                        id TEXT NOT NULL,
                        portfolioId TEXT NOT NULL,
                        taipeiDate TEXT NOT NULL,
                        bucketEpochMillis INTEGER NOT NULL,
                        capturedAtEpochMillis INTEGER NOT NULL,
                        totalMarketValue INTEGER NOT NULL,
                        todayPnl INTEGER NOT NULL,
                        totalPnl REAL NOT NULL,
                        quotedHoldingCount INTEGER NOT NULL,
                        expectedHoldingCount INTEGER NOT NULL,
                        sourceRevision TEXT NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(portfolioId) REFERENCES portfolios(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_intraday_portfolio_points_portfolioId
                    ON intraday_portfolio_points(portfolioId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_intraday_portfolio_points_portfolioId_taipeiDate_bucketEpochMillis
                    ON intraday_portfolio_points(portfolioId, taipeiDate, bucketEpochMillis)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dividend_events (
                        id TEXT NOT NULL,
                        portfolioId TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        exDateTaipei TEXT NOT NULL,
                        recordDateTaipei TEXT,
                        paymentDateTaipei TEXT,
                        cashPerShare REAL NOT NULL,
                        status TEXT NOT NULL,
                        sharesAtEntry INTEGER NOT NULL,
                        estimatedCash INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id),
                        FOREIGN KEY(portfolioId) REFERENCES portfolios(id)
                            ON UPDATE RESTRICT ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_dividend_events_portfolioId
                    ON dividend_events(portfolioId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS
                    index_dividend_events_portfolioId_symbol_exDateTaipei
                    ON dividend_events(portfolioId, symbol, exDateTaipei)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS market_quote_snapshots (
                        symbol TEXT NOT NULL,
                        name TEXT NOT NULL,
                        exchange TEXT,
                        market TEXT,
                        price REAL NOT NULL,
                        previousClose REAL,
                        open REAL,
                        high REAL,
                        low REAL,
                        volume INTEGER,
                        bid REAL,
                        ask REAL,
                        source TEXT NOT NULL,
                        quality TEXT NOT NULL,
                        sourceTimestampEpochMillis INTEGER NOT NULL,
                        receivedAtEpochMillis INTEGER NOT NULL,
                        sessionDate TEXT NOT NULL,
                        fallbackLevel INTEGER NOT NULL,
                        sequence INTEGER,
                        isClose INTEGER NOT NULL,
                        persistedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(symbol)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS market_minute_candles (
                        symbol TEXT NOT NULL,
                        sessionDate TEXT NOT NULL,
                        bucketEpochMillis INTEGER NOT NULL,
                        open REAL NOT NULL,
                        high REAL NOT NULL,
                        low REAL NOT NULL,
                        close REAL NOT NULL,
                        volume INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(symbol, bucketEpochMillis)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_market_minute_candles_symbol
                    ON market_minute_candles(symbol)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_market_minute_candles_sessionDate
                    ON market_minute_candles(sessionDate)
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS institutional_trading (
                        symbol TEXT NOT NULL,
                        dataDate TEXT NOT NULL,
                        period TEXT NOT NULL,
                        foreignNetShares INTEGER,
                        investmentTrustNetShares INTEGER,
                        dealerNetShares INTEGER,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        sourceUpdatedAtEpochMillis INTEGER,
                        quality TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        rawRevision TEXT NOT NULL,
                        PRIMARY KEY(symbol, dataDate)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS monthly_revenue (
                        symbol TEXT NOT NULL,
                        dataDate TEXT NOT NULL,
                        period TEXT NOT NULL,
                        currentMonthRevenueTwd INTEGER,
                        previousMonthRevenueTwd INTEGER,
                        lastYearMonthRevenueTwd INTEGER,
                        monthOverMonthPct REAL,
                        yearOverYearPct REAL,
                        accumulatedRevenueTwd INTEGER,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        sourceUpdatedAtEpochMillis INTEGER,
                        quality TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        rawRevision TEXT NOT NULL,
                        PRIMARY KEY(symbol, period)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quarterly_financial (
                        symbol TEXT NOT NULL,
                        dataDate TEXT NOT NULL,
                        period TEXT NOT NULL,
                        revenueTwd INTEGER,
                        netIncomeTwd INTEGER,
                        eps REAL,
                        roePct REAL,
                        grossMarginPct REAL,
                        operatingMarginPct REAL,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        sourceUpdatedAtEpochMillis INTEGER,
                        quality TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        rawRevision TEXT NOT NULL,
                        PRIMARY KEY(symbol, period)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS etf_components (
                        symbol TEXT NOT NULL,
                        componentSymbol TEXT NOT NULL,
                        componentName TEXT,
                        dataDate TEXT NOT NULL,
                        period TEXT NOT NULL,
                        weightPct REAL,
                        shares INTEGER,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        sourceUpdatedAtEpochMillis INTEGER,
                        quality TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        rawRevision TEXT NOT NULL,
                        PRIMARY KEY(symbol, componentSymbol, period)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS dividend_reference (
                        symbol TEXT NOT NULL,
                        dataDate TEXT NOT NULL,
                        period TEXT NOT NULL,
                        exDateTaipei TEXT NOT NULL,
                        recordDateTaipei TEXT,
                        paymentDateTaipei TEXT,
                        cashDividendPerShare REAL,
                        stockDividendPerShare REAL,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        sourceUpdatedAtEpochMillis INTEGER,
                        quality TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        rawRevision TEXT NOT NULL,
                        PRIMARY KEY(symbol, exDateTaipei)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS after_hours_market (
                        symbol TEXT NOT NULL,
                        dataDate TEXT NOT NULL,
                        period TEXT NOT NULL,
                        closePrice REAL,
                        afterHoursPrice REAL,
                        afterHoursVolume INTEGER,
                        totalVolume INTEGER,
                        source TEXT NOT NULL,
                        fetchedAtEpochMillis INTEGER NOT NULL,
                        sourceUpdatedAtEpochMillis INTEGER,
                        quality TEXT NOT NULL,
                        freshness TEXT NOT NULL,
                        rawRevision TEXT NOT NULL,
                        PRIMARY KEY(symbol, dataDate)
                    )
                    """.trimIndent(),
                )
            }
        }

        val APPEND_ONLY_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS ledger_entries_no_update
                    BEFORE UPDATE ON ledger_entries
                    BEGIN
                        SELECT RAISE(ABORT, 'ledger_entries is append-only');
                    END
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS ledger_entries_no_delete
                    BEFORE DELETE ON ledger_entries
                    BEGIN
                        SELECT RAISE(ABORT, 'ledger_entries is append-only');
                    END
                    """.trimIndent(),
                )
            }
        }

        fun build(context: Context): SaiEtfDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SaiEtfDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(APPEND_ONLY_CALLBACK)
                .build()

        fun buildInMemoryForTests(context: Context): SaiEtfDatabase =
            Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                SaiEtfDatabase::class.java,
            )
                .addCallback(APPEND_ONLY_CALLBACK)
                .build()
    }
}
