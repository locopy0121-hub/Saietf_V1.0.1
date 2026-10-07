package tw.saietf.core.database

import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import tw.saietf.core.database.entity.LedgerEntryEntity
import tw.saietf.core.database.entity.PortfolioEntity
import tw.saietf.core.finance.DefaultLedgerProjector
import tw.saietf.core.finance.LedgerEntry
import tw.saietf.core.finance.LedgerEntryKind
import tw.saietf.core.model.TradeMode

class LedgerRepository(
    private val database: SaiEtfDatabase,
    private val projector: DefaultLedgerProjector = DefaultLedgerProjector(),
) {
    data class AddTradeCommand(
        val side: LedgerEntryKind,
        val symbol: String,
        val shares: Long,
        val price: Double,
        val tradeMode: TradeMode,
        val tradeDateTaipei: String,
        val actualFee: Long? = null,
        val actualTax: Long? = null,
        val note: String? = null,
    )

    data class HoldingSnapshot(
        val symbol: String,
        val shares: Long,
        val investmentCost: Double,
        val realizedNetPnL: Double,
    )

    data class DashboardSnapshot(
        val totalInvestmentCost: Double,
        val realizedNetPnL: Double,
        val holdingCount: Int,
        val ledgerCount: Int,
        val holdings: List<HoldingSnapshot>,
    )

    data class TransactionRow(
        val id: String,
        val side: LedgerEntryKind,
        val symbol: String,
        val shares: Long,
        val price: Double,
        val tradeMode: TradeMode,
        val fee: Long?,
        val tax: Long?,
        val tradeDateTaipei: String,
        val note: String?,
        val isEdited: Boolean,
    )

    data class TransactionPage(
        val pageIndex: Int,
        val pageSize: Int,
        val totalCount: Long,
        val totalPages: Int,
        val rows: List<TransactionRow>,
    )

    fun estimateCommission(
        shares: Long,
        price: Double,
        tradeMode: TradeMode,
    ): Long {
        if (shares <= 0L || !price.isFinite() || price <= 0.0) return 0L
        val projection = projector.project(
            listOf(
                LedgerEntry(
                    id = "preview",
                    symbol = "PREVIEW",
                    kind = LedgerEntryKind.BUY,
                    date = "1970-01-01",
                    shares = shares,
                    price = price,
                    tradeMode = tradeMode,
                ),
            ),
        )
        val tradeAmount = kotlin.math.floor(shares.toDouble() * price).toLong()
        return (projection.totalInvestmentCost.toLong() - tradeAmount).coerceAtLeast(0L)
    }

    fun addTrade(command: AddTradeCommand): DashboardSnapshot {
        val normalizedSymbol = command.symbol.trim().uppercase(Locale.US)
        require(normalizedSymbol.isNotBlank()) { "請輸入股票 / ETF 代號" }
        require(command.shares > 0) { "股數必須大於 0" }
        require(command.price.isFinite() && command.price > 0.0) { "成交價必須大於 0" }
        require(command.actualFee == null || command.actualFee >= 0) { "手續費不可為負數" }
        require(command.actualTax == null || command.actualTax >= 0) { "證交稅不可為負數" }
        LocalDate.parse(command.tradeDateTaipei)

        ensureDefaultPortfolio()

        val now = System.currentTimeMillis()
        val entryId = "%013d-%s".format(Locale.US, now, UUID.randomUUID().toString())
        val candidate = LedgerEntryEntity(
            id = entryId,
            portfolioId = DEFAULT_PORTFOLIO_ID,
            idempotencyKey = "trade-$entryId",
            entryType = command.side.name,
            symbol = normalizedSymbol,
            shares = command.shares,
            price = command.price,
            tradeMode = command.tradeMode.name,
            actualFee = command.actualFee,
            actualTax = if (command.side == LedgerEntryKind.SELL) command.actualTax else null,
            occurredAtEpochMillis = now,
            tradeDateTaipei = command.tradeDateTaipei,
            note = command.note?.trim()?.takeIf { it.isNotEmpty() },
            createdAtEpochMillis = now,
        )

        val existing = effectiveLedgerEntries(
            database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID),
        )
        val candidateEffective = existing + candidate
        validateEffectiveProjection(candidateEffective)
        database.ledgerDao().insertBlocking(candidate)
        return loadDashboard()
    }

    fun correctTrade(
        entryId: String,
        command: AddTradeCommand,
    ): DashboardSnapshot {
        val allEntries = database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID)
        val activeEntries = effectiveLedgerEntries(allEntries)
        val target = activeEntries.firstOrNull { it.id == entryId }
            ?: error("找不到可修改的交易紀錄")

        val normalizedSymbol = command.symbol.trim().uppercase(Locale.US)
        require(normalizedSymbol.isNotBlank()) { "請輸入股票 / ETF 代號" }
        require(command.shares > 0) { "股數必須大於 0" }
        require(command.price.isFinite() && command.price > 0.0) { "成交價必須大於 0" }
        require(command.actualFee == null || command.actualFee >= 0) { "手續費不可為負數" }
        require(command.actualTax == null || command.actualTax >= 0) { "證交稅不可為負數" }
        LocalDate.parse(command.tradeDateTaipei)

        val now = System.currentTimeMillis()
        val correctionId =
            target.id + "~edit-" + "%013d".format(Locale.US, now) + "-" + UUID.randomUUID()
        val correction = LedgerEntryEntity(
            id = correctionId,
            portfolioId = DEFAULT_PORTFOLIO_ID,
            idempotencyKey = "edit-$correctionId",
            entryType = command.side.name,
            symbol = normalizedSymbol,
            shares = command.shares,
            price = command.price,
            tradeMode = command.tradeMode.name,
            actualFee = command.actualFee,
            actualTax = if (command.side == LedgerEntryKind.SELL) command.actualTax else null,
            occurredAtEpochMillis = target.occurredAtEpochMillis,
            tradeDateTaipei = command.tradeDateTaipei,
            note = command.note?.trim()?.takeIf { it.isNotEmpty() },
            correctionOfEntryId = target.id,
            correctionReason = CORRECTION_EDIT,
            createdAtEpochMillis = now,
        )

        validateEffectiveProjection(effectiveLedgerEntries(allEntries + correction))
        database.ledgerDao().insertBlocking(correction)
        return loadDashboard()
    }

    fun deleteTrade(entryId: String): DashboardSnapshot {
        val allEntries = database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID)
        val activeEntries = effectiveLedgerEntries(allEntries)
        val target = activeEntries.firstOrNull { it.id == entryId }
            ?: error("找不到可刪除的交易紀錄")

        val now = System.currentTimeMillis()
        val correctionId =
            target.id + "~delete-" + "%013d".format(Locale.US, now) + "-" + UUID.randomUUID()
        val deletionMarker = target.copy(
            id = correctionId,
            idempotencyKey = "delete-$correctionId",
            shares = 0L,
            price = 0.0,
            actualFee = null,
            actualTax = null,
            note = null,
            correctionOfEntryId = target.id,
            correctionReason = CORRECTION_DELETE,
            createdAtEpochMillis = now,
        )

        validateEffectiveProjection(effectiveLedgerEntries(allEntries + deletionMarker))
        database.ledgerDao().insertBlocking(deletionMarker)
        return loadDashboard()
    }

    fun transactionPage(
        pageIndex: Int,
        pageSize: Int,
    ): TransactionPage {
        ensureDefaultPortfolio()
        require(pageSize in setOf(10, 20, 50)) { "pageSize must be 10, 20, or 50" }
        require(pageIndex >= 0) { "pageIndex cannot be negative" }

        val effective = effectiveLedgerEntries(
            database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID),
        ).sortedWith(
            compareByDescending<LedgerEntryEntity> { it.occurredAtEpochMillis }
                .thenByDescending { it.id },
        )
        val totalCount = effective.size.toLong()
        val totalPages = if (totalCount == 0L) 1 else {
            ((totalCount + pageSize - 1L) / pageSize).toInt()
        }
        val safePage = pageIndex.coerceAtMost(totalPages - 1)
        val rows = effective
            .drop(safePage * pageSize)
            .take(pageSize)
            .map { entity ->
                TransactionRow(
                    id = entity.id,
                    side = LedgerEntryKind.valueOf(entity.entryType),
                    symbol = entity.symbol.uppercase(Locale.US),
                    shares = entity.shares,
                    price = entity.price,
                    tradeMode = TradeMode.valueOf(entity.tradeMode ?: TradeMode.ROUND_LOT.name),
                    fee = entity.actualFee,
                    tax = entity.actualTax,
                    tradeDateTaipei = entity.tradeDateTaipei,
                    note = entity.note,
                    isEdited = entity.correctionReason == CORRECTION_EDIT,
                )
            }

        return TransactionPage(
            pageIndex = safePage,
            pageSize = pageSize,
            totalCount = totalCount,
            totalPages = totalPages,
            rows = rows,
        )
    }

    fun loadDashboard(): DashboardSnapshot {
        ensureDefaultPortfolio()
        val entries = effectiveLedgerEntries(
            database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID),
        )
        if (entries.isEmpty()) {
            return DashboardSnapshot(
                totalInvestmentCost = 0.0,
                realizedNetPnL = 0.0,
                holdingCount = 0,
                ledgerCount = 0,
                holdings = emptyList(),
            )
        }

        val projections = entries
            .groupBy { it.symbol.uppercase(Locale.US) }
            .toSortedMap()
            .map { (symbol, rows) ->
                val projection = projector.project(rows.map(::toFinanceEntry))
                HoldingSnapshot(
                    symbol = symbol,
                    shares = projection.totalShares,
                    investmentCost = projection.totalInvestmentCost,
                    realizedNetPnL = projection.realizedNetPnL,
                )
            }

        return DashboardSnapshot(
            totalInvestmentCost = projections.sumOf { it.investmentCost },
            realizedNetPnL = projections.sumOf { it.realizedNetPnL },
            holdingCount = projections.count { it.shares > 0L },
            ledgerCount = entries.size,
            holdings = projections.filter { it.shares > 0L },
        )
    }

    private fun effectiveLedgerEntries(
        entries: List<LedgerEntryEntity>,
    ): List<LedgerEntryEntity> {
        if (entries.isEmpty()) return emptyList()
        val correctedIds = entries.mapNotNull { it.correctionOfEntryId }.toSet()
        return entries.filter { entry ->
            entry.id !in correctedIds && entry.correctionReason != CORRECTION_DELETE
        }
    }

    private fun validateEffectiveProjection(entries: List<LedgerEntryEntity>) {
        try {
            entries
                .groupBy { it.symbol.uppercase(Locale.US) }
                .values
                .filter { it.isNotEmpty() }
                .forEach { rows -> projector.project(rows.map(::toFinanceEntry)) }
        } catch (error: IllegalArgumentException) {
            if (error.message?.contains("oversell", ignoreCase = true) == true) {
                throw IllegalArgumentException(
                    "修改或刪除後會造成歷史賣出超過可用持股，請先調整後續交易紀錄",
                )
            }
            throw error
        }
    }

    private fun ensureDefaultPortfolio() {
        if (database.portfolioDao().findByIdBlocking(DEFAULT_PORTFOLIO_ID) != null) return
        database.portfolioDao().insertBlocking(
            PortfolioEntity(
                id = DEFAULT_PORTFOLIO_ID,
                name = "預設投資組合",
                kind = "REAL",
                createdAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun toFinanceEntry(entity: LedgerEntryEntity): LedgerEntry = LedgerEntry(
        id = entity.id,
        symbol = entity.symbol.uppercase(Locale.US),
        kind = LedgerEntryKind.valueOf(entity.entryType),
        date = entity.tradeDateTaipei,
        shares = entity.shares,
        price = entity.price,
        tradeMode = TradeMode.valueOf(entity.tradeMode ?: TradeMode.ROUND_LOT.name),
        actualFee = entity.actualFee,
        actualTax = entity.actualTax,
    )

    companion object {
        const val DEFAULT_PORTFOLIO_ID = "default"
        const val CORRECTION_EDIT = "USER_EDIT"
        const val CORRECTION_DELETE = "USER_DELETE"
    }
}
