package tw.saietf.app

import android.app.Application
import tw.saietf.core.database.LedgerRepository
import tw.saietf.core.database.PerformanceHistoryRepository
import tw.saietf.core.database.SaiEtfDatabase
import tw.saietf.core.market.MarketDataCenter

class SaiEtfApplication : Application() {
    val database: SaiEtfDatabase by lazy {
        SaiEtfDatabase.build(this)
    }

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(database)
    }

    val performanceHistoryRepository: PerformanceHistoryRepository by lazy {
        PerformanceHistoryRepository(database)
    }

    val marketDataCenter: MarketDataCenter by lazy {
        MarketDataCenter(
            providers = listOf(
                TwseMisQuoteProvider(),
                YahooQuoteProvider(),
            ),
        )
    }

    companion object {
        const val DEFAULT_LOCALE_TAG = "zh-Hant-TW"
    }
}
