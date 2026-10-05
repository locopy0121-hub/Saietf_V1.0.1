package tw.saietf.core.market

enum class MarketSource {
    FUGLE,
    SHIOAJI,
    TWSE_MIS,
    YAHOO,
    CACHE,
}

enum class QuoteQuality {
    LIVE,
    DELAYED,
    STALE,
    OFFLINE,
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
    val exchange: String? = null,
    val market: String? = null,
    val price: Double,
    val previousClose: Double? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val asOfEpochMillis: Long,
    val source: MarketSource,
    val quality: QuoteQuality = QuoteQuality.LIVE,
    val change: Double? = null,
    val changePercent: Double? = null,
    val volume: Long? = null,
    val bid: Double? = null,
    val ask: Double? = null,
    val sourceTimestampEpochMillis: Long = asOfEpochMillis,
    val receivedAtEpochMillis: Long = asOfEpochMillis,
    val sessionDate: String? = null,
    val fallbackLevel: Int = 0,
    val sequence: Long? = null,
    val isTrial: Boolean = false,
    val isClose: Boolean = false,
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
