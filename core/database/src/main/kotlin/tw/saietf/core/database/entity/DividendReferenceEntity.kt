package tw.saietf.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "dividend_reference",
    primaryKeys = ["symbol", "exDateTaipei"],
)
data class DividendReferenceEntity(
    val symbol: String,
    val dataDate: String,
    val period: String,
    val exDateTaipei: String,
    val recordDateTaipei: String?,
    val paymentDateTaipei: String?,
    val cashDividendPerShare: Double?,
    val stockDividendPerShare: Double?,
    val source: String,
    val fetchedAtEpochMillis: Long,
    val sourceUpdatedAtEpochMillis: Long?,
    val quality: String,
    val freshness: String,
    val rawRevision: String,
)
