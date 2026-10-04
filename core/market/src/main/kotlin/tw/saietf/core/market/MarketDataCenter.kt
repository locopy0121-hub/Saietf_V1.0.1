package tw.saietf.core.market

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MarketDataCenter(
    private val providers: List<MarketQuoteProvider>,
    private val liveThresholdMillis: Long = 30_000L,
    private val maxOfflineCacheAgeMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
) {
    private val cache = linkedMapOf<String, MarketQuote>()
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    fun refresh(
        symbols: Set<String>,
        nowEpochMillis: Long,
        currentTaipeiDate: String,
        tradingSessionActive: Boolean,
    ): MarketBatch {
        val requested = symbols
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .toSet()

        if (requested.isEmpty()) {
            return MarketBatch(
                quotes = emptyMap(),
                staleQuotes = emptyMap(),
                unresolvedSymbols = emptySet(),
                sourcesTried = emptyList(),
                refreshedAtEpochMillis = nowEpochMillis,
            )
        }

        val accepted = linkedMapOf<String, MarketQuote>()
        val stale = linkedMapOf<String, MarketQuote>()
        val pending = requested.toMutableSet()
        val sourcesTried = mutableListOf<MarketSource>()

        providers.forEach { provider ->
            if (pending.isEmpty()) return@forEach
            sourcesTried += provider.source

            val result = runCatching { provider.fetch(pending.toSet()) }
                .getOrDefault(emptyMap())

            result.values.forEach { raw ->
                val symbol = raw.symbol.trim().uppercase(Locale.US)
                if (symbol !in pending) return@forEach
                if (!raw.price.isFinite() || raw.price <= 0.0) return@forEach

                val normalized = raw.copy(
                    symbol = symbol,
                    quality = qualityFor(raw.asOfEpochMillis, nowEpochMillis, currentTaipeiDate),
                )
                synchronized(cache) {
                    cache[symbol] = normalized
                }

                val sameSessionDate = taipeiDate(normalized.asOfEpochMillis) == currentTaipeiDate
                if (tradingSessionActive && !sameSessionDate) {
                    stale[symbol] = normalized.copy(quality = QuoteQuality.STALE)
                } else {
                    accepted[symbol] = normalized
                    pending.remove(symbol)
                }
            }
        }

        pending.toList().forEach { symbol ->
            val cached = synchronized(cache) { cache[symbol] } ?: return@forEach
            val age = (nowEpochMillis - cached.asOfEpochMillis).coerceAtLeast(0L)
            if (age > maxOfflineCacheAgeMillis) return@forEach

            val normalized = cached.copy(
                quality = qualityFor(cached.asOfEpochMillis, nowEpochMillis, currentTaipeiDate),
            )
            val sameSessionDate = taipeiDate(normalized.asOfEpochMillis) == currentTaipeiDate
            if (tradingSessionActive && !sameSessionDate) {
                stale[symbol] = normalized.copy(quality = QuoteQuality.STALE)
            } else {
                accepted[symbol] = normalized
                pending.remove(symbol)
            }
        }

        return MarketBatch(
            quotes = accepted.toMap(),
            staleQuotes = stale.toMap(),
            unresolvedSymbols = pending.toSet(),
            sourcesTried = sourcesTried.toList(),
            refreshedAtEpochMillis = nowEpochMillis,
        )
    }

    fun cachedQuotes(symbols: Set<String>): Map<String, MarketQuote> {
        val requested = symbols.map { it.trim().uppercase(Locale.US) }.toSet()
        return synchronized(cache) {
            cache.filterKeys { it in requested }.toMap()
        }
    }

    private fun qualityFor(
        quoteEpochMillis: Long,
        nowEpochMillis: Long,
        currentTaipeiDate: String,
    ): QuoteQuality {
        val quoteDate = taipeiDate(quoteEpochMillis)
        if (quoteDate != currentTaipeiDate) return QuoteQuality.STALE
        val age = (nowEpochMillis - quoteEpochMillis).coerceAtLeast(0L)
        return if (age <= liveThresholdMillis) QuoteQuality.LIVE else QuoteQuality.DELAYED
    }

    private fun taipeiDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(taipeiZone).toLocalDate().toString()
}
