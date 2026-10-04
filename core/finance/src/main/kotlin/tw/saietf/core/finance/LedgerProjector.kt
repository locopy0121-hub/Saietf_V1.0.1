package tw.saietf.core.finance

import tw.saietf.core.model.BrokerProfile
import tw.saietf.core.model.InstrumentType
import tw.saietf.core.model.TradeMode

enum class LedgerEntryKind {
    BUY,
    SELL,
}

data class LedgerEntry(
    val id: String,
    val symbol: String,
    val kind: LedgerEntryKind,
    val date: String,
    val shares: Long,
    val price: Double,
    val tradeMode: TradeMode,
    val actualFee: Long? = null,
    val actualTax: Long? = null,
    val brokerProfile: BrokerProfile? = null,
    val instrumentType: InstrumentType = InstrumentType.ETF,
)

data class LedgerProjection(
    val symbol: String,
    val totalShares: Long,
    val totalInvestmentCost: Double,
    val realizedNetPnL: Double,
)

interface LedgerProjector {
    fun project(entries: List<LedgerEntry>): LedgerProjection
}

class DefaultLedgerProjector(
    private val defaultBrokerProfile: BrokerProfile = BrokerProfile.DEFAULT,
    private val engine: DefaultFinanceEngine = DefaultFinanceEngine(),
) : LedgerProjector {
    override fun project(entries: List<LedgerEntry>): LedgerProjection {
        require(entries.isNotEmpty()) { "ledger projection requires at least one entry" }
        val symbol = entries.first().symbol
        require(symbol.isNotBlank()) { "symbol is required" }
        require(entries.all { it.symbol == symbol }) { "ledger projection must contain one symbol only" }

        var shares = 0L
        var cost = 0.0
        var realized = 0.0

        entries
            .sortedWith(compareBy<LedgerEntry> { it.date }.thenBy { it.id })
            .forEach { entry ->
                require(entry.shares >= 0) { "shares cannot be negative" }
                require(entry.price >= 0.0 && entry.price.isFinite()) { "price must be finite and non-negative" }
                if (entry.shares == 0L || entry.price == 0.0) return@forEach

                val profile = entry.brokerProfile ?: defaultBrokerProfile
                when (entry.kind) {
                    LedgerEntryKind.BUY -> {
                        val settlement = engine.calculateSettlement(
                            entry.shares,
                            entry.price,
                            entry.tradeMode,
                            TradeSide.BUY,
                            entry.instrumentType,
                            profile,
                            entry.actualFee,
                            entry.actualTax,
                        )
                        shares = Math.addExact(shares, entry.shares)
                        cost += settlement.settlementAmount.toDouble()
                    }

                    LedgerEntryKind.SELL -> {
                        require(entry.shares <= shares) {
                            "oversell rejected: sell=" + entry.shares + ", available=" + shares
                        }
                        require(shares > 0) { "cannot sell an empty position" }
                        val releasedCost = cost / shares.toDouble() * entry.shares.toDouble()
                        val settlement = engine.calculateSettlement(
                            entry.shares,
                            entry.price,
                            entry.tradeMode,
                            TradeSide.SELL,
                            entry.instrumentType,
                            profile,
                            entry.actualFee,
                            entry.actualTax,
                        )
                        realized += settlement.settlementAmount.toDouble() - releasedCost
                        shares -= entry.shares
                        cost -= releasedCost
                        if (shares == 0L) {
                            cost = 0.0
                        } else {
                            cost = cost.coerceAtLeast(0.0)
                        }
                    }
                }
            }

        return LedgerProjection(
            symbol = symbol,
            totalShares = shares,
            totalInvestmentCost = cost,
            realizedNetPnL = realized,
        )
    }
}
