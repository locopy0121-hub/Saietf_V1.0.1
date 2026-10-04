package tw.saietf.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import tw.saietf.core.database.dao.DailySnapshotDao
import tw.saietf.core.database.dao.IntradayPortfolioPointDao
import tw.saietf.core.database.dao.LedgerDao
import tw.saietf.core.database.dao.PortfolioDao
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity
import tw.saietf.core.database.entity.LedgerEntryEntity
import tw.saietf.core.database.entity.ModelAllocationEntity
import tw.saietf.core.database.entity.PortfolioEntity

@Database(
    entities = [
        PortfolioEntity::class,
        LedgerEntryEntity::class,
        ModelAllocationEntity::class,
        DailySnapshotEntity::class,
        IntradayPortfolioPointEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SaiEtfDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun portfolioDao(): PortfolioDao
    abstract fun dailySnapshotDao(): DailySnapshotDao
    abstract fun intradayPortfolioPointDao(): IntradayPortfolioPointDao

    companion object {
        const val DATABASE_NAME = "saietf.db"

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
                .addMigrations(MIGRATION_1_2)
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
