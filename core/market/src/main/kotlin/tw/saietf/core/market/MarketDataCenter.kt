package tw.saietf.core.market

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MarketDataCenter(
    private val providers: List<MarketQuoteProvider>,
    private val liveThresholdMillis: Long = 30_000L,
    private val maxOfflineCacheAgeMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    private val providerPolicies: Map<MarketSource, MarketProviderPolicy> = defaultPolicies,
) {
    private data class ProviderRuntime(
        var lastAttemptEpochMillis: Long? = null,
        var lastSuccessEpochMillis: Long? = null,
        var consecutiveFailures: Int = 0,
        var cooldownUntilEpochMillis: Long = 0L,
    )

    private val cache = linkedMapOf<String, MarketQuote>()
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private val providerRuntime = providers.associate { it.source to ProviderRuntime() }.toMutableMap()

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
                providerHealth = providerHealthSnapshot(nowEpochMillis),
            )
        }

        val accepted = linkedMapOf<String, MarketQuote>()
        val stale = linkedMapOf<String, MarketQuote>()
        val pending = requested.toMutableSet()
        val sourcesTried = mutableListOf<MarketSource>()

        providers.forEach { provider ->
            if (pending.isEmpty()) return@forEach
            val runtime = providerRuntime.getOrPut(provider.source) { ProviderRuntime() }
            val policy = providerPolicies[provider.source] ?: MarketProviderPolicy(1_000L)
            if (!canAttempt(runtime, policy, nowEpochMillis)) return@forEach

            sourcesTried += provider.source
            runtime.lastAttemptEpochMillis = nowEpochMillis

            val result = try {
                provider.fetch(pending.toSet()).also {
                    runtime.lastSuccessEpochMillis = nowEpochMillis
                    runtime.consecutiveFailures = 0
                    runtime.cooldownUntilEpochMillis = 0L
                }
            } catch (error: Throwable) {
                runtime.consecutiveFailures += 1
                runtime.cooldownUntilEpochMillis =
                    nowEpochMillis + backoffFor(error, runtime.consecutiveFailures, policy)
                emptyMap()
            }

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
            providerHealth = providerHealthSnapshot(nowEpochMillis),
        )
    }

    fun providerHealthSnapshot(nowEpochMillis: Long): List<ProviderHealth> =
        providers.map { provider ->
            val runtime = providerRuntime.getOrPut(provider.source) { ProviderRuntime() }
            val policy = providerPolicies[provider.source] ?: MarketProviderPolicy(1_000L)
            val nextByInterval = runtime.lastAttemptEpochMillis
                ?.plus(policy.minFetchIntervalMillis)
                ?: 0L
            val nextAllowed = maxOf(nextByInterval, runtime.cooldownUntilEpochMillis)
            val availability = when {
                nowEpochMillis < runtime.cooldownUntilEpochMillis -> ProviderAvailability.COOLDOWN
                nowEpochMillis < nextByInterval -> ProviderAvailability.THROTTLED
                else -> ProviderAvailability.READY
            }
            ProviderHealth(
                source = provider.source,
                availability = availability,
                consecutiveFailures = runtime.consecutiveFailures,
                lastAttemptEpochMillis = runtime.lastAttemptEpochMillis,
                lastSuccessEpochMillis = runtime.lastSuccessEpochMillis,
                nextAllowedEpochMillis = nextAllowed,
            )
        }

    fun cachedQuotes(symbols: Set<String>): Map<String, MarketQuote> {
        val requested = symbols.map { it.trim().uppercase(Locale.US) }.toSet()
        return synchronized(cache) {
            cache.filterKeys { it in requested }.toMap()
        }
    }

    private fun canAttempt(
        runtime: ProviderRuntime,
        policy: MarketProviderPolicy,
        nowEpochMillis: Long,
    ): Boolean {
        if (nowEpochMillis < runtime.cooldownUntilEpochMillis) return false
        val lastAttempt = runtime.lastAttemptEpochMillis ?: return true
        return nowEpochMillis - lastAttempt >= policy.minFetchIntervalMillis
    }

    private fun backoffFor(
        error: Throwable,
        consecutiveFailures: Int,
        policy: MarketProviderPolicy,
    ): Long {
        if (error is MarketProviderException) {
            error.retryAfterMillis?.takeIf { it > 0L }?.let {
                return it.coerceAtMost(15L * 60L * 1_000L)
            }
            when (error.httpStatusCode) {
                429 -> return 60_000L
                403 -> return 5L * 60L * 1_000L
            }
        }
        val exponent = (consecutiveFailures - 1).coerceIn(0, 6)
        val delay = 2_000L shl exponent
        return delay.coerceAtMost(policy.maxBackoffMillis)
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

    companion object {
        val defaultPolicies: Map<MarketSource, MarketProviderPolicy> = mapOf(
            MarketSource.TWSE_MIS to MarketProviderPolicy(
                minFetchIntervalMillis = 1_000L,
                maxBackoffMillis = 60_000L,
            ),
            MarketSource.YAHOO to MarketProviderPolicy(
                minFetchIntervalMillis = 15_000L,
                maxBackoffMillis = 5L * 60L * 1_000L,
            ),
        )
    }
}
