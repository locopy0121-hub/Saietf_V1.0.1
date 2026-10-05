package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.saietf.core.database.entity.MarketMinuteCandleEntity
import tw.saietf.core.database.entity.MarketQuoteSnapshotEntity

@Dao
interface MarketCacheDao {
    @Upsert
    suspend fun upsertSnapshots(rows: List<MarketQuoteSnapshotEntity>)

    @Query("SELECT * FROM market_quote_snapshots ORDER BY symbol")
    fun observeAllSnapshots(): Flow<List<MarketQuoteSnapshotEntity>>

    @Query("SELECT * FROM market_quote_snapshots WHERE symbol IN (:symbols) ORDER BY symbol")
    fun observeSnapshots(symbols: Set<String>): Flow<List<MarketQuoteSnapshotEntity>>

    @Query("SELECT * FROM market_quote_snapshots WHERE symbol IN (:symbols) ORDER BY symbol")
    suspend fun snapshots(symbols: Set<String>): List<MarketQuoteSnapshotEntity>

    @Upsert
    suspend fun upsertCandles(rows: List<MarketMinuteCandleEntity>)

    @Query(
        """
        SELECT * FROM market_minute_candles
        WHERE symbol = :symbol AND sessionDate = :sessionDate
        ORDER BY bucketEpochMillis
        """
    )
    fun observeMinuteCandles(
        symbol: String,
        sessionDate: String,
    ): Flow<List<MarketMinuteCandleEntity>>

    @Query(
        """
        SELECT * FROM market_minute_candles
        WHERE symbol = :symbol AND bucketEpochMillis = :bucketEpochMillis
        LIMIT 1
        """
    )
    suspend fun findMinuteCandle(
        symbol: String,
        bucketEpochMillis: Long,
    ): MarketMinuteCandleEntity?
}
