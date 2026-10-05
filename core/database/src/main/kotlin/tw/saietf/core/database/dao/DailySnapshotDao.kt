package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tw.saietf.core.database.entity.DailySnapshotEntity

@Dao
interface DailySnapshotDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(snapshot: DailySnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(snapshot: DailySnapshotEntity)

    @Query(
        """
        SELECT * FROM daily_snapshots
        WHERE portfolioId = :portfolioId
          AND taipeiDate = :taipeiDate
          AND sourceRevision = :sourceRevision
        LIMIT 1
        """,
    )
    suspend fun findReceipt(
        portfolioId: String,
        taipeiDate: String,
        sourceRevision: String,
    ): DailySnapshotEntity?

    @Query(
        """
        SELECT * FROM daily_snapshots
        WHERE portfolioId = :portfolioId
          AND taipeiDate < :beforeTaipeiDate
        ORDER BY taipeiDate DESC, capturedAtEpochMillis DESC, id DESC
        LIMIT 1
        """,
    )
    fun latestBeforeBlocking(
        portfolioId: String,
        beforeTaipeiDate: String,
    ): DailySnapshotEntity?

    @Query(
        """
        SELECT * FROM daily_snapshots
        WHERE portfolioId = :portfolioId
          AND taipeiDate >= :startTaipeiDate
          AND taipeiDate <= :endTaipeiDate
        ORDER BY taipeiDate ASC, capturedAtEpochMillis ASC, id ASC
        """,
    )
    fun rangeBlocking(
        portfolioId: String,
        startTaipeiDate: String,
        endTaipeiDate: String,
    ): List<DailySnapshotEntity>

    @Query(
        """
        SELECT * FROM daily_snapshots
        WHERE portfolioId = :portfolioId
        ORDER BY taipeiDate DESC, capturedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun recentBlocking(
        portfolioId: String,
        limit: Int,
    ): List<DailySnapshotEntity>

    @Query(
        """
        SELECT * FROM daily_snapshots
        WHERE portfolioId = :portfolioId
        ORDER BY taipeiDate DESC, capturedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(portfolioId: String, limit: Int): Flow<List<DailySnapshotEntity>>
}
