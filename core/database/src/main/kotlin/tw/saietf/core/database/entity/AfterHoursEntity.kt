package tw.saietf.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "after_hours_market",
    primaryKeys = ["symbol", "dataDate"],
)
data class AfterHoursEntity(
    val symbol: String,
    val dataDate: String,
    val period: String,
    val closePrice: Double?,
    val afterHoursPrice: Double?,
    val afterHoursVolume: Long?,
    val totalVolume: Long?,
    val source: String,
    val fetchedAtEpochMillis: Long,
    val sourceUpdatedAtEpochMillis: Long?,
    val quality: String,
    val freshness: String,
    val rawRevision: String,
)
