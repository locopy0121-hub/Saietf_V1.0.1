package tw.saietf.core.market

import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

enum class MarketProviderCapability {
    STREAM,
    POLL,
    BATCH,
}

sealed interface MarketEvent {
    data class Quote(
        val quote: MarketQuote,
    ) : MarketEvent

    data class ProviderState(
        val health: ProviderHealth,
    ) : MarketEvent
}

interface MarketDataProvider {
    val source: MarketSource
    val capabilities: Set<MarketProviderCapability>
    val events: Flow<MarketEvent>

    suspend fun connect()
    suspend fun disconnect()
    suspend fun subscribe(symbols: Set<String>)
    suspend fun unsubscribe(symbols: Set<String>)
    suspend fun fetchSnapshot(symbols: Set<String>): Map<String, MarketQuote>
    fun health(nowEpochMillis: Long): ProviderHealth
}

/**
 * Bridge for the existing synchronous polling providers.
 *
 * V1.0.58 keeps TWSE MIS / Yahoo behavior intact while presenting the V2 provider contract.
 * Streaming providers can implement [MarketDataProvider] directly in later versions.
 */
class PollingMarketDataProviderAdapter(
    private val delegate: MarketQuoteProvider,
) : MarketDataProvider {
    override val source: MarketSource = delegate.source
    override val capabilities: Set<MarketProviderCapability> =
        setOf(MarketProviderCapability.POLL)

    private val _events = MutableSharedFlow<MarketEvent>(extraBufferCapacity = 64)
    override val events: Flow<MarketEvent> = _events.asSharedFlow()

    private val subscribedSymbols = linkedSetOf<String>()
    @Volatile
    private var lastAttemptEpochMillis: Long? = null
    @Volatile
    private var lastSuccessEpochMillis: Long? = null
    @Volatile
    private var consecutiveFailures: Int = 0

    override suspend fun connect() = Unit

    override suspend fun disconnect() {
        synchronized(subscribedSymbols) {
            subscribedSymbols.clear()
        }
    }

    override suspend fun subscribe(symbols: Set<String>) {
        synchronized(subscribedSymbols) {
            subscribedSymbols += symbols
                .map { it.trim().uppercase(Locale.US) }
                .filter { it.isNotBlank() }
        }
    }

    override suspend fun unsubscribe(symbols: Set<String>) {
        synchronized(subscribedSymbols) {
            subscribedSymbols -= symbols
                .map { it.trim().uppercase(Locale.US) }
                .toSet()
        }
    }

    override suspend fun fetchSnapshot(symbols: Set<String>): Map<String, MarketQuote> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            lastAttemptEpochMillis = now
            try {
                delegate.fetch(symbols).also { rows ->
                    lastSuccessEpochMillis = System.currentTimeMillis()
                    consecutiveFailures = 0
                    rows.values.forEach { quote ->
                        _events.tryEmit(MarketEvent.Quote(quote))
                    }
                }
            } catch (error: Throwable) {
                consecutiveFailures += 1
                throw error
            }
        }

    override fun health(nowEpochMillis: Long): ProviderHealth =
        ProviderHealth(
            source = source,
            availability = ProviderAvailability.READY,
            consecutiveFailures = consecutiveFailures,
            lastAttemptEpochMillis = lastAttemptEpochMillis,
            lastSuccessEpochMillis = lastSuccessEpochMillis,
            nextAllowedEpochMillis = nowEpochMillis,
        )

    fun subscribedSnapshot(): Set<String> = synchronized(subscribedSymbols) {
        subscribedSymbols.toSet()
    }
}
