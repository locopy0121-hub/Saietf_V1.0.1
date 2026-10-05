package tw.saietf.core.database

import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor
import tw.saietf.core.database.entity.DividendEventEntity

class DividendRepository(
    private val database: SaiEtfDatabase,
    private val ledgerRepository: LedgerRepository,
) {
    enum class Status {
        ANNOUNCED,
        CONFIRMED,
    }

    data class UpsertCommand(
        val symbol: String,
        val exDateTaipei: String,
        val recordDateTaipei: String?,
        val paymentDateTaipei: String?,
        val cashPerShare: Double,
        val status: Status,
    )

    data class DividendRow(
        val symbol: String,
        val exDateTaipei: String,
        val recordDateTaipei: String?,
        val paymentDateTaipei: String?,
        val cashPerShare: Double,
        val status: Status,
        val sharesAtEntry: Long,
        val estimatedCash: Long,
    )

    fun upsert(command: UpsertCommand): DividendRow {
        val symbol = command.symbol.trim().uppercase(Locale.US)
        require(symbol.isNotBlank()) { "請輸入股票 / ETF 代號" }
        require(command.cashPerShare.isFinite() && command.cashPerShare >= 0.0) {
            "每股現金股利不可為負數"
        }

        val exDate = LocalDate.parse(command.exDateTaipei)
        val recordDate = command.recordDateTaipei
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(LocalDate::parse)
        val paymentDate = command.paymentDateTaipei
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(LocalDate::parse)

        require(recordDate == null || !recordDate.isBefore(exDate)) {
            "股權登記日不可早於除息日"
        }
        require(paymentDate == null || !paymentDate.isBefore(exDate)) {
            "發放日不可早於除息日"
        }

        val holdings = ledgerRepository.loadDashboard().holdings
        val shares = holdings.firstOrNull { it.symbol == symbol }?.shares ?: 0L
        val estimatedCash = floor(shares.toDouble() * command.cashPerShare).toLong()
        val portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID
        val id = "dividend:$portfolioId:$symbol:${command.exDateTaipei}"
        val existing = database.dividendEventDao().findBlocking(
            portfolioId = portfolioId,
            symbol = symbol,
            exDateTaipei = command.exDateTaipei,
        )
        val now = System.currentTimeMillis()

        val entity = DividendEventEntity(
            id = id,
            portfolioId = portfolioId,
            symbol = symbol,
            exDateTaipei = command.exDateTaipei,
            recordDateTaipei = recordDate?.toString(),
            paymentDateTaipei = paymentDate?.toString(),
            cashPerShare = command.cashPerShare,
            status = command.status.name,
            sharesAtEntry = shares,
            estimatedCash = estimatedCash,
            createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
            updatedAtEpochMillis = now,
        )
        database.dividendEventDao().upsertBlocking(entity)
        return entity.toRow()
    }

    fun recent(limit: Int = 30): List<DividendRow> =
        database.dividendEventDao()
            .recentBlocking(LedgerRepository.DEFAULT_PORTFOLIO_ID, limit.coerceIn(1, 120))
            .map { it.toRow() }

    private fun DividendEventEntity.toRow(): DividendRow = DividendRow(
        symbol = symbol,
        exDateTaipei = exDateTaipei,
        recordDateTaipei = recordDateTaipei,
        paymentDateTaipei = paymentDateTaipei,
        cashPerShare = cashPerShare,
        status = Status.valueOf(status),
        sharesAtEntry = sharesAtEntry,
        estimatedCash = estimatedCash,
    )
}
