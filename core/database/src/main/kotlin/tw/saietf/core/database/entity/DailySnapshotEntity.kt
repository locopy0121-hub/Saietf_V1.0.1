package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "daily_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["portfolioId"]),
        Index(value = ["portfolioId", "taipeiDate", "sourceRevision"], unique = true),
    ],
)
data class DailySnapshotEntity(
    @PrimaryKey
    val id: String,
    val portfolioId: String,
    val taipeiDate: String,
    val sourceRevision: String,
    val capturedAtEpochMillis: Long,
    val totalMarketValue: Long,
    val totalInvestmentCost: Double,
    val totalNetLiquidationValue: Long,
    val totalUnrealizedProfit: Double,
    val realizedNetPnL: Double,
    val totalDividendsReceived: Long,
    val comprehensivePnL: Double,
    val dailyMarketPnL: Long,
)
