package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tw.saietf.core.database.entity.DividendEventEntity

@Dao
interface DividendEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(event: DividendEventEntity)

    @Query(
        """
        SELECT * FROM dividend_events
        WHERE portfolioId = :portfolioId
        ORDER BY exDateTaipei ASC, updatedAtEpochMillis ASC, id ASC
        """,
    )
    fun allBlocking(portfolioId: String): List<DividendEventEntity>

    @Query(
        """
        SELECT * FROM dividend_events
        WHERE portfolioId = :portfolioId
        ORDER BY exDateTaipei DESC, updatedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    fun recentBlocking(
        portfolioId: String,
        limit: Int,
    ): List<DividendEventEntity>

    @Query(
        """
        SELECT * FROM dividend_events
        WHERE portfolioId = :portfolioId
          AND symbol = :symbol
          AND exDateTaipei = :exDateTaipei
        LIMIT 1
        """,
    )
    fun findBlocking(
        portfolioId: String,
        symbol: String,
        exDateTaipei: String,
    ): DividendEventEntity?
}
