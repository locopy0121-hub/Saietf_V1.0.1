package tw.saietf.core.market

enum class MarketSource {
    TWSE_MIS,
    YAHOO,
}

enum class QuoteQuality {
    LIVE,
    DELAYED,
    STALE,
}

enum class ProviderAvailability {
    READY,
    THROTTLED,
    COOLDOWN,
}

data class MarketProviderPolicy(
    val minFetchIntervalMillis: Long,
    val maxBackoffMillis: Long = 60_000L,
)

data class ProviderHealth(
    val source: MarketSource,
    val availability: ProviderAvailability,
    val consecutiveFailures: Int,
    val lastAttemptEpochMillis: Long?,
    val lastSuccessEpochMillis: Long?,
    val nextAllowedEpochMillis: Long,
)

class MarketProviderException(
    val httpStatusCode: Int? = null,
    val retryAfterMillis: Long? = null,
    message: String,
) : RuntimeException(message)

data class MarketQuote(
    val symbol: String,
    val name: String = symbol,
    val price: Double,
    val previousClose: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val asOfEpochMillis: Long,
    val source: MarketSource,
    val quality: QuoteQuality = QuoteQuality.LIVE,
)

interface MarketQuoteProvider {
    val source: MarketSource
    fun fetch(symbols: Set<String>): Map<String, MarketQuote>
}

data class MarketBatch(
    val quotes: Map<String, MarketQuote>,
    val staleQuotes: Map<String, MarketQuote>,
    val unresolvedSymbols: Set<String>,
    val sourcesTried: List<MarketSource>,
    val refreshedAtEpochMillis: Long,
    val providerHealth: List<ProviderHealth> = emptyList(),
)
