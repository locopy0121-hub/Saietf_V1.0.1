package tw.saietf.core.database.entity

import androidx.room.Entity

@Entity(
    tableName = "institutional_trading",
    primaryKeys = ["symbol", "dataDate"],
)
data class InstitutionalTradingEntity(
    val symbol: String,
    val dataDate: String,
    val period: String,
    val foreignNetShares: Long?,
    val investmentTrustNetShares: Long?,
    val dealerNetShares: Long?,
    val source: String,
    val fetchedAtEpochMillis: Long,
    val sourceUpdatedAtEpochMillis: Long?,
    val quality: String,
    val freshness: String,
    val rawRevision: String,
)
