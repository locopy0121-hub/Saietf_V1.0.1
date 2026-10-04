package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tw.saietf.core.database.entity.LedgerEntryEntity

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: LedgerEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertBlocking(entry: LedgerEntryEntity)

    @Query("SELECT * FROM ledger_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LedgerEntryEntity?

    @Query("SELECT * FROM ledger_entries WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): LedgerEntryEntity?

    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE portfolioId = :portfolioId
        ORDER BY occurredAtEpochMillis ASC, id ASC
        """,
    )
    fun observeChronological(portfolioId: String): Flow<List<LedgerEntryEntity>>

    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE portfolioId = :portfolioId
        ORDER BY occurredAtEpochMillis ASC, id ASC
        """,
    )
    fun listChronological(portfolioId: String): List<LedgerEntryEntity>

    @Query(
        """
        SELECT * FROM ledger_entries
        WHERE portfolioId = :portfolioId
          AND (
            :cursorEpochMillis IS NULL
            OR occurredAtEpochMillis < :cursorEpochMillis
            OR (occurredAtEpochMillis = :cursorEpochMillis AND id < :cursorId)
          )
        ORDER BY occurredAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun pageBefore(
        portfolioId: String,
        cursorEpochMillis: Long?,
        cursorId: String?,
        limit: Int,
    ): List<LedgerEntryEntity>

    @Query("SELECT COUNT(*) FROM ledger_entries WHERE portfolioId = :portfolioId")
    suspend fun count(portfolioId: String): Long
}
