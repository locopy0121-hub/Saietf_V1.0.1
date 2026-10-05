package tw.saietf.core.database

import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity

class PerformanceHistoryRepository(
    private val database: SaiEtfDatabase,
) {
    data class HistoryState(
        val previousTradingDayPnl: Long?,
        val currentDayPointCount: Int,
    )

    data class IntradaySummary(
        val pointCount: Int,
        val openMarketValue: Long?,
        val latestMarketValue: Long?,
        val highMarketValue: Long?,
        val lowMarketValue: Long?,
    )

    data class DailyStats(
        val sampleCount: Int,
        val totalDailyPnl: Long,
        val averageDailyPnl: Long,
        val bestDayPnl: Long?,
        val worstDayPnl: Long?,
    )

    fun previousTradingDay(beforeTaipeiDate: String): DailySnapshotEntity? =
        database.dailySnapshotDao().latestBeforeBlocking(
            portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID,
            beforeTaipeiDate = beforeTaipeiDate,
        )

    fun recentDaily(limit: Int = 30): List<DailySnapshotEntity> =
        database.dailySnapshotDao().recentBlocking(
            portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID,
            limit = limit.coerceIn(1, 400),
        )

    fun dailyRange(
        startTaipeiDate: String,
        endTaipeiDate: String,
    ): List<DailySnapshotEntity> =
        database.dailySnapshotDao().rangeBlocking(
            portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID,
            startTaipeiDate = startTaipeiDate,
            endTaipeiDate = endTaipeiDate,
        )

    fun dailyStats(
        startTaipeiDate: String,
        endTaipeiDate: String,
    ): DailyStats {
        val rows = dailyRange(startTaipeiDate, endTaipeiDate)
        val values = rows.map { it.dailyMarketPnL }
        return DailyStats(
            sampleCount = values.size,
            totalDailyPnl = values.sum(),
            averageDailyPnl = if (values.isEmpty()) 0L else values.sum() / values.size,
            bestDayPnl = values.maxOrNull(),
            worstDayPnl = values.minOrNull(),
        )
    }

    fun intradayPointCount(taipeiDate: String): Int =
        database.intradayPortfolioPointDao().countForDateBlocking(
            portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID,
            taipeiDate = taipeiDate,
        )

    fun intradayPoints(taipeiDate: String): List<IntradayPortfolioPointEntity> =
        database.intradayPortfolioPointDao().listForDateBlocking(
            portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID,
            taipeiDate = taipeiDate,
        )

    fun intradaySummary(taipeiDate: String): IntradaySummary {
        val points = intradayPoints(taipeiDate)
        return IntradaySummary(
            pointCount = points.size,
            openMarketValue = points.firstOrNull()?.totalMarketValue,
            latestMarketValue = points.lastOrNull()?.totalMarketValue,
            highMarketValue = points.maxOfOrNull { it.totalMarketValue },
            lowMarketValue = points.minOfOrNull { it.totalMarketValue },
        )
    }

    fun dailyStats(limit: Int = 30): DailyStats {
        val rows = recentDaily(limit)
        val values = rows.map { it.dailyMarketPnL }
        return DailyStats(
            sampleCount = values.size,
            totalDailyPnl = values.sum(),
            averageDailyPnl = if (values.isEmpty()) 0L else values.sum() / values.size,
            bestDayPnl = values.maxOrNull(),
            worstDayPnl = values.minOrNull(),
        )
    }

    fun recordFreshValuation(
        taipeiDate: String,
        capturedAtEpochMillis: Long,
        totalMarketValue: Long,
        totalInvestmentCost: Double,
        realizedNetPnL: Double,
        dailyMarketPnL: Long,
        totalUnrealizedProfit: Double,
        quotedHoldingCount: Int,
        expectedHoldingCount: Int,
        recordIntraday: Boolean,
    ): HistoryState {
        require(totalMarketValue >= 0L)
        require(totalInvestmentCost.isFinite())
        require(realizedNetPnL.isFinite())
        require(totalUnrealizedProfit.isFinite())
        require(expectedHoldingCount > 0)
        require(quotedHoldingCount == expectedHoldingCount)

        val portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID
        val snapshotId = "daily:$portfolioId:$taipeiDate:$SOURCE_REVISION"

        database.dailySnapshotDao().upsertBlocking(
            DailySnapshotEntity(
                id = snapshotId,
                portfolioId = portfolioId,
                taipeiDate = taipeiDate,
                sourceRevision = SOURCE_REVISION,
                capturedAtEpochMillis = capturedAtEpochMillis,
                totalMarketValue = totalMarketValue,
                totalInvestmentCost = totalInvestmentCost,
                totalNetLiquidationValue = totalMarketValue,
                totalUnrealizedProfit = totalUnrealizedProfit,
                realizedNetPnL = realizedNetPnL,
                totalDividendsReceived = 0L,
                comprehensivePnL = totalUnrealizedProfit + realizedNetPnL,
                dailyMarketPnL = dailyMarketPnL,
            ),
        )

        if (recordIntraday) {
            val bucket = capturedAtEpochMillis -
                (capturedAtEpochMillis % INTRADAY_BUCKET_MILLIS)
            database.intradayPortfolioPointDao().upsertBlocking(
                IntradayPortfolioPointEntity(
                    id = "intraday:$portfolioId:$taipeiDate:$bucket",
                    portfolioId = portfolioId,
                    taipeiDate = taipeiDate,
                    bucketEpochMillis = bucket,
                    capturedAtEpochMillis = capturedAtEpochMillis,
                    totalMarketValue = totalMarketValue,
                    todayPnl = dailyMarketPnL,
                    totalPnl = totalUnrealizedProfit,
                    quotedHoldingCount = quotedHoldingCount,
                    expectedHoldingCount = expectedHoldingCount,
                    sourceRevision = SOURCE_REVISION,
                ),
            )
        }

        return HistoryState(
            previousTradingDayPnl = previousTradingDay(taipeiDate)?.dailyMarketPnL,
            currentDayPointCount = intradayPointCount(taipeiDate),
        )
    }

    companion object {
        const val SOURCE_REVISION = "market-portfolio-v2"
        const val INTRADAY_BUCKET_MILLIS = 15_000L
    }
}
