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

        val existing = database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID)
        val sameSymbol = existing.filter { it.symbol.equals(normalizedSymbol, ignoreCase = true) }
        projector.project((sameSymbol + candidate).map(::toFinanceEntry))
        database.ledgerDao().insertBlocking(candidate)
        return loadDashboard()
    }

    fun loadDashboard(): DashboardSnapshot {
        ensureDefaultPortfolio()
        val entries = database.ledgerDao().listChronological(DEFAULT_PORTFOLIO_ID)
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
    }
}
