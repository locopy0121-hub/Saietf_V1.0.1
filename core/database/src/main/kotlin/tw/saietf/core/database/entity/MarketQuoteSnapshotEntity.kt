package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "market_quote_snapshots")
data class MarketQuoteSnapshotEntity(
    @PrimaryKey
    val symbol: String,
    val name: String,
    val exchange: String?,
    val market: String?,
    val price: Double,
    val previousClose: Double?,
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val volume: Long?,
    val bid: Double?,
    val ask: Double?,
    val source: String,
    val quality: String,
    val sourceTimestampEpochMillis: Long,
    val receivedAtEpochMillis: Long,
    val sessionDate: String,
    val fallbackLevel: Int,
    val sequence: Long?,
    val isClose: Boolean,
    val persistedAtEpochMillis: Long,
)
