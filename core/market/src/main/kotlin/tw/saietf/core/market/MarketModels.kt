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
)
