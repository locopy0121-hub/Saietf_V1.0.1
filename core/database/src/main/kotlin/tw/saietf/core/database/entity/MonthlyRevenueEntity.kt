package tw.saietf.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "monthly_revenue",
    primaryKeys = ["symbol", "period"],
)
data class MonthlyRevenueEntity(
    val symbol: String,
    val dataDate: String,
    val period: String,
    val currentMonthRevenueTwd: Long?,
    val previousMonthRevenueTwd: Long?,
    val lastYearMonthRevenueTwd: Long?,
    val monthOverMonthPct: Double?,
    val yearOverYearPct: Double?,
    val accumulatedRevenueTwd: Long?,
    val source: String,
    val fetchedAtEpochMillis: Long,
    val sourceUpdatedAtEpochMillis: Long?,
    val quality: String,
    val freshness: String,
    val rawRevision: String,
)
