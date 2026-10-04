package tw.saietf.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ledger_entries",
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
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["portfolioId"]),
        Index(value = ["portfolioId", "tradeDateTaipei", "occurredAtEpochMillis", "id"]),
        Index(value = ["correctionOfEntryId"]),
    ],
)
data class LedgerEntryEntity(
    @PrimaryKey
    val id: String,
    val portfolioId: String,
    val idempotencyKey: String,
    val entryType: String,
    val symbol: String,
    val shares: Long,
    val price: Double,
    val tradeMode: String?,
    val actualFee: Long?,
    val actualTax: Long?,
    val occurredAtEpochMillis: Long,
    val tradeDateTaipei: String,
    val note: String? = null,
    val correctionOfEntryId: String? = null,
    val correctionReason: String? = null,
    val createdAtEpochMillis: Long,
)
