package tw.saietf.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "etf_components",
    primaryKeys = ["symbol", "componentSymbol", "period"],
)
data class EtfComponentEntity(
    val symbol: String,
    val componentSymbol: String,
    val componentName: String?,
    val dataDate: String,
    val period: String,
    val weightPct: Double?,
    val shares: Long?,
    val source: String,
    val fetchedAtEpochMillis: Long,
    val sourceUpdatedAtEpochMillis: Long?,
    val quality: String,
    val freshness: String,
    val rawRevision: String,
)
