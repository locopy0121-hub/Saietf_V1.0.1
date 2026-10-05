package tw.saietf.app

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tw.saietf.core.market.MarketDataCenter
import tw.saietf.core.market.MarketQuote

class MarketPersistenceController(
    private val scope: CoroutineScope,
    private val marketDataCenter: MarketDataCenter,
    private val repository: MarketPersistenceRepository,
) {
    private val latest = AtomicReference<Map<String, MarketQuote>>(emptyMap())
    private var writerJob: Job? = null

    init {
        scope.launch {
            marketDataCenter.quotesState.collectLatest { snapshot ->
                latest.set(snapshot)
            }
        }
        writerJob = scope.launch {
            while (isActive) {
                delay(PERSIST_INTERVAL_MILLIS)
                flushNow()
            }
        }
    }

    suspend fun flushNow() {
        val snapshot = latest.get().values
        if (snapshot.isNotEmpty()) {
            repository.persist(snapshot)
        }
    }

    companion object {
        const val PERSIST_INTERVAL_MILLIS = 5_000L
    }
}
