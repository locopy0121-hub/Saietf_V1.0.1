package tw.saietf.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "quarterly_financial",
    primaryKeys = ["symbol", "period"],
)
data class QuarterlyFinancialEntity(
    val symbol: String,
    val dataDate: String,
    val period: String,
    val revenueTwd: Long?,
    val netIncomeTwd: Long?,
    val eps: Double?,
    val roePct: Double?,
    val grossMarginPct: Double?,
    val operatingMarginPct: Double?,
    val source: String,
    val fetchedAtEpochMillis: Long,
    val sourceUpdatedAtEpochMillis: Long?,
    val quality: String,
    val freshness: String,
    val rawRevision: String,
)
