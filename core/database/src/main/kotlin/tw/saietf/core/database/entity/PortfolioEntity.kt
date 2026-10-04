package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "portfolios",
    indices = [
        Index(value = ["kind", "archivedAtEpochMillis"]),
    ],
)
data class PortfolioEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val kind: String,
    val createdAtEpochMillis: Long,
    val archivedAtEpochMillis: Long? = null,
)
