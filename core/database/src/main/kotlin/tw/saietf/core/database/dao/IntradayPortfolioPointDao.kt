package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity

@Dao
interface IntradayPortfolioPointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(point: IntradayPortfolioPointEntity)

    @Query(
        """
        SELECT * FROM intraday_portfolio_points
        WHERE portfolioId = :portfolioId AND taipeiDate = :taipeiDate
        ORDER BY bucketEpochMillis ASC, id ASC
        """,
    )
    fun listForDateBlocking(
        portfolioId: String,
        taipeiDate: String,
    ): List<IntradayPortfolioPointEntity>

    @Query(
        """
        SELECT COUNT(*) FROM intraday_portfolio_points
        WHERE portfolioId = :portfolioId AND taipeiDate = :taipeiDate
        """,
    )
    fun countForDateBlocking(
        portfolioId: String,
        taipeiDate: String,
    ): Int
}
