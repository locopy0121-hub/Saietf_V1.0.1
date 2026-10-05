package tw.saietf.core.market

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class MarketDataCenter(
    private val providers: List<MarketQuoteProvider>,
    private val liveThresholdMillis: Long = 30_000L,
    private val maxOfflineCacheAgeMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    private val providerPolicies: Map<MarketSource, MarketProviderPolicy> = defaultPolicies,
    private val hotStore: MemoryMarketStore = MemoryMarketStore(),
    private val arbitrator: MarketArbitrator = MarketArbitrator(),
) {
    private data class ProviderRuntime(
        var lastAttemptEpochMillis: Long? = null,
        var lastSuccessEpochMillis: Long? = null,
        val circuitBreaker: ProviderCircuitBreaker = ProviderCircuitBreaker(),
    )

    private val cache = linkedMapOf<String, MarketQuote>()
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private val providerRuntime = providers.associate { it.source to ProviderRuntime() }.toMutableMap()

    val quotesState = hotStore.quotes

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

        hotStore.snapshot(pending.toSet()).values.forEach { raw ->
            if (raw.source !in setOf(MarketSource.FUGLE, MarketSource.SHIOAJI)) return@forEach
            val symbol = raw.symbol.trim().uppercase(Locale.US)
            if (symbol !in pending) return@forEach
            val normalized = raw.copy(
                symbol = symbol,
                quality = qualityFor(raw.sourceTimestampEpochMillis, nowEpochMillis, currentTaipeiDate),
            )
            val sameSessionDate = taipeiDate(normalized.sourceTimestampEpochMillis) == currentTaipeiDate
            if (sameSessionDate && normalized.quality == QuoteQuality.LIVE) {
                accepted[symbol] = normalized
                pending.remove(symbol)
            }
        }

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
                    runtime.circuitBreaker.recordSuccess(nowEpochMillis)
                }
            } catch (error: Throwable) {
                runtime.circuitBreaker.recordFailure(
                    nowEpochMillis = nowEpochMillis,
                    cooldownMillis = backoffFor(
                        error = error,
                        consecutiveFailures = runtime.circuitBreaker
                            .snapshot(nowEpochMillis)
                            .consecutiveFailures + 1,
                        policy = policy,
                    ),
                )
                emptyMap()
            }

            result.values.forEach { raw ->
                val symbol = raw.symbol.trim().uppercase(Locale.US)
                if (symbol !in pending) return@forEach
                if (!raw.price.isFinite() || raw.price <= 0.0) return@forEach

                val sourceTime = raw.sourceTimestampEpochMillis
                    .takeIf { it > 0L }
                    ?: raw.asOfEpochMillis
                val normalized = raw.copy(
                    symbol = symbol,
                    asOfEpochMillis = sourceTime,
                    sourceTimestampEpochMillis = sourceTime,
                    receivedAtEpochMillis = raw.receivedAtEpochMillis
                        .takeIf { it > 0L }
                        ?: nowEpochMillis,
                    sessionDate = raw.sessionDate
                        ?.takeIf { it.isNotBlank() }
                        ?: taipeiDate(sourceTime),
                    quality = qualityFor(sourceTime, nowEpochMillis, currentTaipeiDate),
                )

                val existing = hotStore.snapshot(setOf(symbol))[symbol]
                    ?: synchronized(cache) { cache[symbol] }
                if (!arbitrator.decide(existing, normalized).accepted) {
                    return@forEach
                }

                synchronized(cache) {
                    cache[symbol] = normalized
                }

                val sameSessionDate = taipeiDate(normalized.sourceTimestampEpochMillis) == currentTaipeiDate
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

        if (accepted.isNotEmpty()) {
            hotStore.publish(accepted.values)
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

    fun acceptStreamingQuote(
        raw: MarketQuote,
        nowEpochMillis: Long = System.currentTimeMillis(),
        currentTaipeiDate: String = taipeiDate(System.currentTimeMillis()),
    ): Boolean {
        val symbol = raw.symbol.trim().uppercase(Locale.US)
        if (symbol.isBlank() || !raw.price.isFinite() || raw.price <= 0.0) return false
        if (raw.isTrial) return false

        val sourceTime = raw.sourceTimestampEpochMillis
        if (taipeiDate(sourceTime) != currentTaipeiDate) return false

        val normalized = raw.copy(
            symbol = symbol,
            asOfEpochMillis = sourceTime,
            sourceTimestampEpochMillis = sourceTime,
            receivedAtEpochMillis = raw.receivedAtEpochMillis
                .takeIf { it > 0L }
                ?: nowEpochMillis,
            quality = qualityFor(sourceTime, nowEpochMillis, currentTaipeiDate),
            sessionDate = currentTaipeiDate,
        )
        val existing = hotStore.snapshot(setOf(symbol))[symbol]
            ?: synchronized(cache) { cache[symbol] }
        val decision = arbitrator.decide(existing, normalized)
        if (!decision.accepted) return false

        synchronized(cache) {
            cache[symbol] = normalized
        }
        hotStore.publish(listOf(normalized))
        return true
    }

    fun providerHealthSnapshot(nowEpochMillis: Long): List<ProviderHealth> =
        providers.map { provider ->
            val runtime = providerRuntime.getOrPut(provider.source) { ProviderRuntime() }
            val policy = providerPolicies[provider.source] ?: MarketProviderPolicy(1_000L)
            val nextByInterval = runtime.lastAttemptEpochMillis
                ?.plus(policy.minFetchIntervalMillis)
                ?: 0L
            val breaker = runtime.circuitBreaker.snapshot(nowEpochMillis)
            val nextAllowed = maxOf(nextByInterval, breaker.cooldownUntilEpochMillis)
            val availability = when {
                breaker.state == ProviderCircuitState.COOLDOWN -> ProviderAvailability.COOLDOWN
                breaker.state == ProviderCircuitState.RECOVERING -> ProviderAvailability.THROTTLED
                nowEpochMillis < nextByInterval -> ProviderAvailability.THROTTLED
                else -> ProviderAvailability.READY
            }
            ProviderHealth(
                source = provider.source,
                availability = availability,
                consecutiveFailures = breaker.consecutiveFailures,
                lastAttemptEpochMillis = runtime.lastAttemptEpochMillis,
                lastSuccessEpochMillis = runtime.lastSuccessEpochMillis,
                nextAllowedEpochMillis = nextAllowed,
                circuitState = breaker.state,
            )
        }

    fun memoryQuotes(symbols: Set<String> = emptySet()): Map<String, MarketQuote> =
        hotStore.snapshot(symbols)

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
        if (!runtime.circuitBreaker.canAttempt(nowEpochMillis)) return false
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
