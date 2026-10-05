package tw.saietf.core.market

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Intraday in-memory SSOT.
 *
 * High-frequency market updates publish here first. UI layers subscribe to [quotes].
 * Room persistence is intentionally not part of this class and will remain throttled.
 */
class MemoryMarketStore(
    initialQuotes: Map<String, MarketQuote> = emptyMap(),
) {
    private val lock = Any()
    private val _quotes = MutableStateFlow(normalize(initialQuotes))
    val quotes: StateFlow<Map<String, MarketQuote>> = _quotes.asStateFlow()

    fun publish(updates: Collection<MarketQuote>): Map<String, MarketQuote> {
        if (updates.isEmpty()) return _quotes.value
        synchronized(lock) {
            val next = _quotes.value.toMutableMap()
            updates.forEach { raw ->
                val symbol = raw.symbol.trim().uppercase(Locale.US)
                if (symbol.isBlank()) return@forEach
                if (!raw.price.isFinite() || raw.price <= 0.0) return@forEach
                next[symbol] = raw.copy(symbol = symbol)
            }
            val snapshot = next.toMap()
            _quotes.value = snapshot
            return snapshot
        }
    }

    fun replace(snapshot: Map<String, MarketQuote>): Map<String, MarketQuote> =
        synchronized(lock) {
            val normalized = normalize(snapshot)
            _quotes.value = normalized
            normalized
        }

    fun snapshot(symbols: Set<String> = emptySet()): Map<String, MarketQuote> {
        val current = _quotes.value
        if (symbols.isEmpty()) return current
        val requested = symbols
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .toSet()
        return current.filterKeys { it in requested }
    }

    fun clear() {
        synchronized(lock) {
            _quotes.value = emptyMap()
        }
    }

    private fun normalize(input: Map<String, MarketQuote>): Map<String, MarketQuote> =
        input.values
            .mapNotNull { quote ->
                val symbol = quote.symbol.trim().uppercase(Locale.US)
                quote.takeIf {
                    symbol.isNotBlank() && quote.price.isFinite() && quote.price > 0.0
                }?.copy(symbol = symbol)
            }
            .associateBy { it.symbol }
}
