package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "intraday_portfolio_points",
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
        Index(value = ["portfolioId", "taipeiDate", "bucketEpochMillis"], unique = true),
    ],
)
data class IntradayPortfolioPointEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    val taipeiDate: String,
    val bucketEpochMillis: Long,
    val capturedAtEpochMillis: Long,
    val totalMarketValue: Long,
    val todayPnl: Long,
    val totalPnl: Double,
    val quotedHoldingCount: Int,
    val expectedHoldingCount: Int,
    val sourceRevision: String,
)
