package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "model_allocations",
    primaryKeys = ["portfolioId", "symbol"],
    foreignKeys = [
        ForeignKey(
            entity = PortfolioEntity::class,
            parentColumns = ["id"],
            childColumns = ["portfolioId"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["portfolioId"]),
    ],
)
data class ModelAllocationEntity(
    val portfolioId: String,
    val symbol: String,
    val basisPoints: Int,
    val displayOrder: Int,
    val updatedAtEpochMillis: Long,
)
