package tw.saietf.core.finance

import kotlin.math.ceil
import kotlin.math.floor
import tw.saietf.core.model.BrokerProfile
import tw.saietf.core.model.InstrumentType
import tw.saietf.core.model.RoundingMode
import tw.saietf.core.model.TradeMode

interface FinanceEngine {
    fun calculateInstrument(input: InstrumentCalculationInput): InstrumentCalculationResult
    fun calculatePortfolio(input: PortfolioCalculationInput): PortfolioCalculationResult
    fun calculateNetDividend(input: DividendCalculationInput): DividendCalculationResult
}

class DefaultFinanceEngine : FinanceEngine {
    override fun calculateInstrument(input: InstrumentCalculationInput): InstrumentCalculationResult {
        require(input.symbol.isNotBlank()) { "symbol is required" }
        require(input.dividendFrequency in setOf(1, 2, 4, 6, 12)) {
            "dividendFrequency must be 1, 2, 4, 6, or 12"
        }

        var totalShares = 0L
        var totalInvestmentCost = 0.0
        var realizedNetPnL = 0.0

        input.transactions
            .sortedWith(compareBy<TradeRecord> { it.date }.thenBy { it.id })
            .forEach { transaction ->
                val shares = transaction.shares.coerceAtLeast(0)
                val price = transaction.price.nonNegative()
                if (shares <= 0 || price <= 0.0) return@forEach

                val profile = transaction.brokerProfile ?: input.brokerProfile
                when (transaction.side) {
                    TradeSide.BUY -> {
                        val settlement = calculateSettlement(
                            shares,
                            price,
                            transaction.tradeMode,
                            TradeSide.BUY,
                            input.instrumentType,
                            profile,
                            transaction.actualFee,
                            transaction.actualTax,
                        )
                        totalShares = Math.addExact(totalShares, shares)
                        totalInvestmentCost += settlement.settlementAmount.toDouble()
                    }

                    TradeSide.SELL -> {
                        if (totalShares <= 0 || totalInvestmentCost <= 0.0) return@forEach
                        val sellShares = minOf(shares, totalShares)
                        val releasedCost = totalInvestmentCost / totalShares.toDouble() * sellShares.toDouble()
                        val settlement = calculateSettlement(
                            sellShares,
                            price,
                            transaction.tradeMode,
                            TradeSide.SELL,
                            input.instrumentType,
                            profile,
                            transaction.actualFee,
                            transaction.actualTax,
                        )
                        realizedNetPnL += settlement.settlementAmount.toDouble() - releasedCost
                        totalShares -= sellShares
                        totalInvestmentCost -= releasedCost
                        if (totalShares <= 0) {
                            totalShares = 0
                            totalInvestmentCost = 0.0
                        } else {
                            totalInvestmentCost = totalInvestmentCost.coerceAtLeast(0.0)
                        }
                    }
                }
            }

        val currentPrice = input.currentPrice.nonNegative()
        val hasValidPosition = totalShares > 0 && currentPrice > 0.0
        val averageCostPerShare =
            if (hasValidPosition && totalInvestmentCost > 0.0) totalInvestmentCost / totalShares.toDouble() else 0.0
        val currentMarketValue =
            if (hasValidPosition) floorMoney(totalShares.toDouble() * currentPrice) else 0L
        val estimatedSellCommission =
            if (currentMarketValue > 0) commission(currentMarketValue, input.liquidationTradeMode, input.brokerProfile) else 0L
        val estimatedSellTax =
            if (currentMarketValue > 0) sellTax(currentMarketValue, input.instrumentType, input.brokerProfile) else 0L
        val netLiquidationValue =
            if (currentMarketValue > 0) currentMarketValue - estimatedSellCommission - estimatedSellTax else 0L
        val unrealizedProfit =
            if (hasValidPosition && totalInvestmentCost > 0.0) netLiquidationValue.toDouble() - totalInvestmentCost else 0.0
        val unrealizedROI =
            if (hasValidPosition && totalInvestmentCost > 0.0) roundPercent(unrealizedProfit / totalInvestmentCost * 100.0) else 0.0

        val totalDividendsReceived = input.dividendRecords.fold(0L) { sum, record ->
            Math.addExact(
                sum,
                calculateNetDividend(
                    DividendCalculationInput(record.sharesHeld, record.perShareAmount),
                ).netDividend,
            )
        }
        val comprehensivePnL = unrealizedProfit + realizedNetPnL + totalDividendsReceived.toDouble()
        val latestDividend = input.latestDividendPerShare.nonNegative()
        val nextEstimatedDividend =
            if (hasValidPosition && latestDividend > 0.0) {
                calculateNetDividend(DividendCalculationInput(totalShares, latestDividend)).netDividend
            } else 0L
        val singlePeriodYield =
            if (hasValidPosition && latestDividend > 0.0) roundPercent(latestDividend / currentPrice * 100.0) else 0.0
        val annualizedYield =
            if (hasValidPosition && latestDividend > 0.0) {
                roundPercent(latestDividend * input.dividendFrequency / currentPrice * 100.0)
            } else 0.0

        return InstrumentCalculationResult(
            input.symbol,
            input.name,
            currentPrice,
            totalShares,
            totalInvestmentCost,
            averageCostPerShare,
            currentMarketValue,
            estimatedSellCommission,
            estimatedSellTax,
            netLiquidationValue,
            unrealizedProfit,
            unrealizedROI,
            realizedNetPnL,
            comprehensivePnL,
            totalDividendsReceived,
            nextEstimatedDividend,
            singlePeriodYield,
            annualizedYield,
        )
    }

    override fun calculatePortfolio(input: PortfolioCalculationInput): PortfolioCalculationResult {
        val raw = input.instruments.map(::calculateInstrument)
        val totalMarketValue = raw.fold(0L) { sum, row -> Math.addExact(sum, row.currentMarketValue) }
        val totalInvestmentCost = raw.sumOf { it.totalInvestmentCost }
        val totalNetLiquidationValue = raw.fold(0L) { sum, row -> Math.addExact(sum, row.netLiquidationValue) }
        val totalEstimatedSellCommission = raw.fold(0L) { sum, row -> Math.addExact(sum, row.estimatedSellCommission) }
        val totalEstimatedSellTax = raw.fold(0L) { sum, row -> Math.addExact(sum, row.estimatedSellTax) }
        val totalUnrealizedProfit = totalNetLiquidationValue.toDouble() - totalInvestmentCost
        val totalUnrealizedROI =
            if (totalMarketValue > 0 && totalInvestmentCost > 0.0) {
                roundPercent(totalUnrealizedProfit / totalInvestmentCost * 100.0)
            } else 0.0
        val realized = raw.sumOf { it.realizedNetPnL }
        val dividends = raw.fold(0L) { sum, row -> Math.addExact(sum, row.totalDividendsReceived) }
        val comprehensive = totalUnrealizedProfit + realized + dividends.toDouble()
        val nextDividend = raw.fold(0L) { sum, row -> Math.addExact(sum, row.nextEstimatedDividend) }
        val weighted = raw.map { row ->
            row.copy(
                portfolioWeight =
                    if (totalMarketValue > 0 && row.totalShares > 0 && row.currentPrice > 0.0) {
                        roundPercent(row.currentMarketValue.toDouble() / totalMarketValue.toDouble() * 100.0)
                    } else 0.0,
            )
        }

        return PortfolioCalculationResult(
            totalMarketValue,
            totalInvestmentCost,
            totalNetLiquidationValue,
            totalEstimatedSellCommission,
            totalEstimatedSellTax,
            totalUnrealizedProfit,
            totalUnrealizedROI,
            realized,
            comprehensive,
            comprehensive,
            dividends,
            nextDividend,
            weighted,
        )
    }

    override fun calculateNetDividend(input: DividendCalculationInput): DividendCalculationResult {
        val shares = input.sharesHeld.coerceAtLeast(0)
        val perShare = input.perShareAmount.nonNegative()
        if (shares <= 0 || perShare <= 0.0) return DividendCalculationResult(0, 0, 0, 0)

        val gross = floorMoney(shares.toDouble() * perShare)
        val health =
            if (gross >= HEALTH_PREMIUM_THRESHOLD) floorMoney(gross.toDouble() * HEALTH_PREMIUM_RATE) else 0L
        val transfer = if (gross > 0) DIVIDEND_TRANSFER_FEE else 0L
        return DividendCalculationResult(
            gross,
            health,
            transfer,
            (gross - health - transfer).coerceAtLeast(0),
        )
    }

    internal fun calculateSettlement(
        shares: Long,
        price: Double,
        tradeMode: TradeMode,
        side: TradeSide,
        instrumentType: InstrumentType = InstrumentType.ETF,
        profile: BrokerProfile = BrokerProfile.DEFAULT,
        actualFee: Long? = null,
        actualTax: Long? = null,
    ): TradeSettlement {
        val safeShares = shares.coerceAtLeast(0)
        val safePrice = price.nonNegative()
        if (safeShares <= 0 || safePrice <= 0.0) return TradeSettlement(0, 0, 0, 0)

        val amount = applyRounding(safeShares.toDouble() * safePrice, profile.tradeAmountRounding)
        val fee = actualFee?.coerceAtLeast(0) ?: commission(amount, tradeMode, profile)
        val tax =
            if (side == TradeSide.SELL) {
                actualTax?.coerceAtLeast(0) ?: sellTax(amount, instrumentType, profile)
            } else 0L
        val cash = if (side == TradeSide.BUY) amount + fee else amount - fee - tax
        return TradeSettlement(amount, fee, tax, cash)
    }

    private fun commission(amount: Long, mode: TradeMode, profile: BrokerProfile): Long {
        if (amount <= 0) return 0
        val minimum =
            if (mode == TradeMode.ROUND_LOT) profile.minimumCommissionRoundLot else profile.minimumCommissionOddLot
        val calculated = applyRounding(
            amount.toDouble() * profile.commissionRate * profile.commissionDiscount,
            profile.commissionRounding,
        )
        return maxOf(minimum, calculated)
    }

    private fun sellTax(amount: Long, type: InstrumentType, profile: BrokerProfile): Long {
        if (amount <= 0) return 0
        val rate = if (type == InstrumentType.STOCK) profile.stockSellTaxRate else profile.etfSellTaxRate
        return applyRounding(amount.toDouble() * rate, profile.taxRounding)
    }

    private fun floorMoney(value: Double): Long = floor(value.nonNegative()).toLong()

    private fun applyRounding(value: Double, mode: RoundingMode): Long {
        if (!value.isFinite()) return 0
        return when (mode) {
            RoundingMode.FLOOR -> floor(value)
            RoundingMode.ROUND -> floor(value + 0.5)
            RoundingMode.CEIL -> ceil(value)
        }.toLong()
    }

    private fun roundPercent(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return floor((value + Math.ulp(1.0)) * 100.0 + 0.5) / 100.0
    }

    private fun Double.nonNegative(): Double = if (isFinite()) coerceAtLeast(0.0) else 0.0

    private companion object {
        const val HEALTH_PREMIUM_THRESHOLD = 20_000L
        const val HEALTH_PREMIUM_RATE = 0.0211
        const val DIVIDEND_TRANSFER_FEE = 10L
    }
}
