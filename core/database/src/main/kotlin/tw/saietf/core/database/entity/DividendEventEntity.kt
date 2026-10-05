package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "dividend_events",
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
        Index(value = ["portfolioId", "symbol", "exDateTaipei"], unique = true),
    ],
)
data class DividendEventEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    val symbol: String,
    val exDateTaipei: String,
    val recordDateTaipei: String?,
    val paymentDateTaipei: String?,
    val cashPerShare: Double,
    val status: String,
    val sharesAtEntry: Long,
    val estimatedCash: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)
