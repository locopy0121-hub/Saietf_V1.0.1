package tw.saietf.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tw.saietf.core.database.BackupRepository
import tw.saietf.core.database.DividendRepository
import tw.saietf.core.database.LedgerRepository
import tw.saietf.core.database.PerformanceHistoryRepository
import tw.saietf.core.database.SaiEtfDatabase
import tw.saietf.core.market.MarketDataCenter

class SaiEtfApplication : Application() {
    private val marketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: SaiEtfDatabase by lazy {
        SaiEtfDatabase.build(this)
    }

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(database)
    }

    val performanceHistoryRepository: PerformanceHistoryRepository by lazy {
        PerformanceHistoryRepository(database)
    }

    val dividendRepository: DividendRepository by lazy {
        DividendRepository(database, ledgerRepository)
    }

    val backupRepository: BackupRepository by lazy {
        BackupRepository(database, ledgerRepository)
    }

    val intradayHistoryProvider: YahooIntradayHistoryProvider by lazy {
        YahooIntradayHistoryProvider()
    }

    val fugleApiKeyStore: FugleApiKeyStore by lazy {
        FugleApiKeyStore(this)
    }

    val shioajiGatewaySettingsStore: ShioajiGatewaySettingsStore by lazy {
        ShioajiGatewaySettingsStore(this)
    }

    internal val fugleWebSocketProvider: FugleWebSocketProvider by lazy {
        FugleWebSocketProvider(
            apiKeyProvider = fugleApiKeyStore::load,
        )
    }

    val marketDataCenter: MarketDataCenter by lazy {
        MarketDataCenter(
            providers = listOf(
                TwseMisQuoteProvider(),
                YahooQuoteProvider(),
            ),
        )
    }

    val marketPersistenceRepository: MarketPersistenceRepository by lazy {
        MarketPersistenceRepository(database)
    }

    val stockDetailRepository: StockDetailRepository by lazy {
        StockDetailRepository(database)
    }

    internal val marketPersistenceController: MarketPersistenceController by lazy {
        MarketPersistenceController(
            scope = marketScope,
            marketDataCenter = marketDataCenter,
            repository = marketPersistenceRepository,
        )
    }

    internal val fugleStreamingController: FugleStreamingController by lazy {
        FugleStreamingController(
            scope = marketScope,
            provider = fugleWebSocketProvider,
            marketDataCenter = marketDataCenter,
        )
    }

    internal val shioajiSseGatewayProvider: ShioajiSseGatewayProvider by lazy {
        ShioajiSseGatewayProvider(
            endpointProvider = shioajiGatewaySettingsStore::url,
            bearerTokenProvider = shioajiGatewaySettingsStore::bearerToken,
        )
    }

    internal val realtimeStreamingController: RealtimeStreamingController by lazy {
        RealtimeStreamingController(
            scope = marketScope,
            fugleController = fugleStreamingController,
            fugleProvider = fugleWebSocketProvider,
            shioajiProvider = shioajiSseGatewayProvider,
            shioajiSettings = shioajiGatewaySettingsStore,
            marketDataCenter = marketDataCenter,
        )
    }

    companion object {
        const val DEFAULT_LOCALE_TAG = "zh-Hant-TW"
    }
}
