package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "market_minute_candles",
    primaryKeys = ["symbol", "bucketEpochMillis"],
    indices = [
        Index(value = ["symbol"]),
        Index(value = ["sessionDate"]),
    ],
)
data class MarketMinuteCandleEntity(
    val symbol: String,
    val sessionDate: String,
    val bucketEpochMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val source: String,
    val updatedAtEpochMillis: Long,
)
