package tw.saietf.core.market

import kotlin.math.floor
import java.util.Locale

data class HoldingCost(
    val symbol: String,
    val shares: Long,
    val investmentCost: Double,
)

data class HoldingMarketValue(
    val symbol: String,
    val shares: Long,
    val investmentCost: Double,
    val quote: MarketQuote?,
    val marketValue: Long?,
    val todayPnl: Long?,
    val totalPnl: Double?,
)

data class PortfolioMarketValuation(
    val expectedHoldingCount: Int,
    val quotedHoldingCount: Int,
    val isComplete: Boolean,
    val totalMarketValue: Long?,
    val todayPnl: Long?,
    val totalPnl: Double?,
    val holdings: List<HoldingMarketValue>,
)

class PortfolioMarketValuator {
    fun value(
        holdings: List<HoldingCost>,
        quotes: Map<String, MarketQuote>,
    ): PortfolioMarketValuation {
        val active = holdings
            .filter { it.shares > 0L }
            .map { it.copy(symbol = it.symbol.trim().uppercase(Locale.US)) }

        val rows = active.map { holding ->
            val quote = quotes[holding.symbol]
                ?.takeIf { it.price.isFinite() && it.price > 0.0 }

            val marketValue = quote?.let {
                floor(holding.shares.toDouble() * it.price).toLong()
            }
            val today = quote?.previousClose
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { previous ->
                    val priorValue = floor(holding.shares.toDouble() * previous).toLong()
                    marketValue?.minus(priorValue)
                }
            val total = marketValue?.toDouble()?.minus(holding.investmentCost)

            HoldingMarketValue(
                symbol = holding.symbol,
                shares = holding.shares,
                investmentCost = holding.investmentCost,
                quote = quote,
                marketValue = marketValue,
                todayPnl = today,
                totalPnl = total,
            )
        }

        val complete = rows.all { it.quote != null }
        val todayComplete = complete && rows.all { it.todayPnl != null }

        return PortfolioMarketValuation(
            expectedHoldingCount = rows.size,
            quotedHoldingCount = rows.count { it.quote != null },
            isComplete = complete,
            totalMarketValue = if (complete) rows.fold(0L) { sum, row ->
                Math.addExact(sum, row.marketValue ?: 0L)
            } else null,
            todayPnl = if (todayComplete) rows.fold(0L) { sum, row ->
                Math.addExact(sum, row.todayPnl ?: 0L)
            } else null,
            totalPnl = if (complete) rows.sumOf { it.totalPnl ?: 0.0 } else null,
            holdings = rows,
        )
    }
}
