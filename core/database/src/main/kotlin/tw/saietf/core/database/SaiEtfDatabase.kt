package tw.saietf.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import tw.saietf.core.database.dao.DailySnapshotDao
import tw.saietf.core.database.dao.LedgerDao
import tw.saietf.core.database.dao.PortfolioDao
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.LedgerEntryEntity
import tw.saietf.core.database.entity.ModelAllocationEntity
import tw.saietf.core.database.entity.PortfolioEntity

@Database(
    entities = [
        PortfolioEntity::class,
        LedgerEntryEntity::class,
        ModelAllocationEntity::class,
        DailySnapshotEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SaiEtfDatabase : RoomDatabase() {
    abstract fun ledgerDao(): LedgerDao
    abstract fun portfolioDao(): PortfolioDao
    abstract fun dailySnapshotDao(): DailySnapshotDao

    companion object {
        const val DATABASE_NAME = "saietf.db"

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
