package tw.saietf.core.finance

import tw.saietf.core.model.BrokerProfile
import tw.saietf.core.model.InstrumentType
import tw.saietf.core.model.TradeMode

enum class TradeSide {
    BUY,
    SELL,
}

data class TradeRecord(
    val id: String,
    val side: TradeSide,
    val tradeMode: TradeMode,
    val shares: Long,
    val price: Double,
    val date: String,
    val actualFee: Long? = null,
    val actualTax: Long? = null,
    val brokerProfile: BrokerProfile? = null,
)

data class DividendRecord(
    val id: String,
    val paymentDate: String,
    val perShareAmount: Double,
    val sharesHeld: Long,
)

data class InstrumentCalculationInput(
    val symbol: String,
    val name: String = symbol,
    val currentPrice: Double,
    val liquidationTradeMode: TradeMode,
    val dividendFrequency: Int = 1,
    val latestDividendPerShare: Double = 0.0,
    val transactions: List<TradeRecord> = emptyList(),
    val dividendRecords: List<DividendRecord> = emptyList(),
    val brokerProfile: BrokerProfile = BrokerProfile.DEFAULT,
    val instrumentType: InstrumentType = InstrumentType.ETF,
)

data class InstrumentCalculationResult(
    val symbol: String,
    val name: String,
    val currentPrice: Double,
    val totalShares: Long,
    val totalInvestmentCost: Double,
    val averageCostPerShare: Double,
    val currentMarketValue: Long,
    val estimatedSellCommission: Long,
    val estimatedSellTax: Long,
    val netLiquidationValue: Long,
    val unrealizedProfit: Double,
    val unrealizedROI: Double,
    val realizedNetPnL: Double,
    val comprehensivePnL: Double,
    val totalDividendsReceived: Long,
    val nextEstimatedDividend: Long,
    val singlePeriodYield: Double,
    val annualizedYield: Double,
    val portfolioWeight: Double = 0.0,
)

data class PortfolioCalculationInput(
    val instruments: List<InstrumentCalculationInput>,
)

data class PortfolioCalculationResult(
    val totalMarketValue: Long,
    val totalInvestmentCost: Double,
    val totalNetLiquidationValue: Long,
    val totalEstimatedSellCommission: Long,
    val totalEstimatedSellTax: Long,
    val totalUnrealizedProfit: Double,
    val totalUnrealizedROI: Double,
    val realizedNetPnL: Double,
    val comprehensivePnL: Double,
    val totalPnl: Double,
    val totalDividendsReceived: Long,
    val nextEstimatedDividendTotal: Long,
    val instrumentSummaries: List<InstrumentCalculationResult>,
)

data class DividendCalculationInput(
    val sharesHeld: Long,
    val perShareAmount: Double,
)

data class DividendCalculationResult(
    val grossDividend: Long,
    val supplementaryHealthPremium: Long,
    val transferFee: Long,
    val netDividend: Long,
)

internal data class TradeSettlement(
    val tradeAmount: Long,
    val commission: Long,
    val tax: Long,
    val settlementAmount: Long,
)
