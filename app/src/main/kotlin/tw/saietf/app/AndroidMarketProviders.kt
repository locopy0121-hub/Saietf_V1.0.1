package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor
import org.json.JSONObject
import tw.saietf.core.market.MarketQuote
import tw.saietf.core.market.MarketQuoteProvider
import tw.saietf.core.market.MarketSource


data class IntradayPortfolioHistoryPoint(
    val epochMillis: Long,
    val totalMarketValue: Long,
)

class YahooIntradayHistoryProvider {
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private var cacheKey: String? = null
    private var cacheAtEpochMillis: Long = 0L
    private var cachePoints: List<IntradayPortfolioHistoryPoint> = emptyList()

    fun fetchPortfolioSeries(
        holdings: Map<String, Long>,
        taipeiDate: String,
    ): List<IntradayPortfolioHistoryPoint> {
        val normalized = holdings
            .filterValues { it > 0L }
            .mapKeys { it.key.trim().uppercase(Locale.US) }
            .toSortedMap()
        if (normalized.isEmpty()) return emptyList()

        val key = taipeiDate + "|" + normalized.entries.joinToString(",") { "${it.key}:${it.value}" }
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (key == cacheKey && now - cacheAtEpochMillis < 60_000L) {
                return cachePoints
            }
        }

        val perSymbol = linkedMapOf<String, Map<Long, Double>>()
        normalized.keys.forEach { symbol ->
            val series = fetchSymbol(symbol, taipeiDate)
            if (series.isEmpty()) return emptyList()
            perSymbol[symbol] = series
        }

        val timeline = perSymbol.values
            .flatMap { it.keys }
            .distinct()
            .sorted()
        val lastPrices = mutableMapOf<String, Double>()
        val points = mutableListOf<IntradayPortfolioHistoryPoint>()

        timeline.forEach { epochMillis ->
            perSymbol.forEach { (symbol, series) ->
                series[epochMillis]?.let { lastPrices[symbol] = it }
            }
            if (lastPrices.keys.containsAll(normalized.keys)) {
                val total = normalized.entries.fold(0L) { sum, (symbol, shares) ->
                    val price = lastPrices.getValue(symbol)
                    Math.addExact(sum, floor(shares.toDouble() * price).toLong())
                }
                points += IntradayPortfolioHistoryPoint(
                    epochMillis = epochMillis,
                    totalMarketValue = total,
                )
            }
        }

        synchronized(this) {
            cacheKey = key
            cacheAtEpochMillis = now
            cachePoints = points
        }
        return points
    }

    private fun fetchSymbol(
        symbol: String,
        taipeiDate: String,
    ): Map<Long, Double> {
        for (suffix in listOf("TW", "TWO")) {
            val ticker = "$symbol.$suffix"
            val url =
                "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                    "?interval=1m&range=1d&includePrePost=false"
            val body = runCatching {
                HttpText.get(url, headers = mapOf("Accept" to "application/json"))
            }.getOrNull() ?: continue

            val result = JSONObject(body)
                .optJSONObject("chart")
                ?.optJSONArray("result")
                ?.optJSONObject(0)
                ?: continue
            val timestamps = result.optJSONArray("timestamp") ?: continue
            val quote = result
                .optJSONObject("indicators")
                ?.optJSONArray("quote")
                ?.optJSONObject(0)
                ?: continue
            val closes = quote.optJSONArray("close") ?: continue

            val rows = linkedMapOf<Long, Double>()
            val count = minOf(timestamps.length(), closes.length())
            for (index in 0 until count) {
                if (timestamps.isNull(index) || closes.isNull(index)) continue
                val epochSeconds = timestamps.optLong(index, 0L)
                val price = closes.optDouble(index, Double.NaN)
                if (epochSeconds <= 0L || !price.isFinite() || price <= 0.0) continue

                val epochMillis = epochSeconds * 1_000L
                val zoned = Instant.ofEpochMilli(epochMillis).atZone(taipeiZone)
                if (zoned.toLocalDate().toString() != taipeiDate) continue
                val time = zoned.toLocalTime()
                if (time.isBefore(LocalTime.of(9, 0)) || time.isAfter(LocalTime.of(13, 30))) {
                    continue
                }

                val minuteBucket = epochMillis - (epochMillis % 60_000L)
                rows[minuteBucket] = price
            }
            if (rows.isNotEmpty()) return rows
        }
        return emptyMap()
    }
}

class TwseMisQuoteProvider : MarketQuoteProvider {
    override val source: MarketSource = MarketSource.TWSE_MIS

    override fun fetch(symbols: Set<String>): Map<String, MarketQuote> {
        if (symbols.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, MarketQuote>()

        symbols.toList().sorted().chunked(30).forEach { chunk ->
            val channels = chunk.flatMap { symbol ->
                listOf("tse_${symbol}.tw", "otc_${symbol}.tw")
            }.joinToString("|")
            val encoded = URLEncoder.encode(channels, Charsets.UTF_8.name())
            val body = HttpText.get(
                "https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=$encoded&json=1&delay=0",
                headers = mapOf(
                    "Referer" to "https://mis.twse.com.tw/stock/fibest.jsp",
                    "Accept" to "application/json,text/plain,*/*",
                ),
            )
            val rows = JSONObject(body).optJSONArray("msgArray") ?: return@forEach
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val symbol = row.optString("c").trim().uppercase(Locale.US)
                if (symbol !in symbols) continue

                val price = marketNumber(row.optString("z")) ?: continue
                val previousClose = marketNumber(row.optString("y"))
                val timestamp = row.optString("tlong").toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: System.currentTimeMillis()

                result[symbol] = MarketQuote(
                    symbol = symbol,
                    name = row.optString("n").trim().ifBlank { symbol },
                    price = price,
                    previousClose = previousClose,
                    open = marketNumber(row.optString("o")),
                    high = marketNumber(row.optString("h")),
                    low = marketNumber(row.optString("l")),
                    asOfEpochMillis = timestamp,
                    source = source,
                )
            }
        }

        return result
    }

    private fun marketNumber(raw: String): Double? =
        raw.replace(",", "").trim()
            .takeIf { it.isNotEmpty() && it != "-" }
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
}

class YahooQuoteProvider : MarketQuoteProvider {
    override val source: MarketSource = MarketSource.YAHOO

    override fun fetch(symbols: Set<String>): Map<String, MarketQuote> {
        val result = linkedMapOf<String, MarketQuote>()
        symbols.toList().sorted().forEach { symbol ->
            fetchOne(symbol)?.let { result[symbol] = it }
        }
        return result
    }

    private fun fetchOne(symbol: String): MarketQuote? {
        for (suffix in listOf("TW", "TWO")) {
            val ticker = "$symbol.$suffix"
            val url =
                "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1m&range=1d"
            val body = runCatching {
                HttpText.get(url, headers = mapOf("Accept" to "application/json"))
            }.getOrNull() ?: continue

            val result = JSONObject(body)
                .optJSONObject("chart")
                ?.optJSONArray("result")
                ?.optJSONObject(0)
                ?: continue
            val meta = result.optJSONObject("meta") ?: continue
            val price = meta.optDouble("regularMarketPrice", Double.NaN)
            if (!price.isFinite() || price <= 0.0) continue

            val previousClose = sequenceOf(
                meta.optDouble("previousClose", Double.NaN),
                meta.optDouble("chartPreviousClose", Double.NaN),
            ).firstOrNull { it.isFinite() && it > 0.0 }

            val epochSeconds = meta.optLong("regularMarketTime", 0L)
            val epochMillis = if (epochSeconds > 0L) {
                epochSeconds * 1_000L
            } else {
                System.currentTimeMillis()
            }

            return MarketQuote(
                symbol = symbol,
                name = meta.optString("longName").trim()
                    .ifBlank { meta.optString("shortName").trim() }
                    .ifBlank { symbol },
                price = price,
                previousClose = previousClose,
                open = meta.optDouble("regularMarketOpen", Double.NaN).finitePositive(),
                high = meta.optDouble("regularMarketDayHigh", Double.NaN).finitePositive(),
                low = meta.optDouble("regularMarketDayLow", Double.NaN).finitePositive(),
                asOfEpochMillis = epochMillis,
                source = source,
            )
        }
        return null
    }

    private fun Double.finitePositive(): Double? =
        takeIf { it.isFinite() && it > 0.0 }
}

private object HttpText {
    fun get(url: String, headers: Map<String, String>): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_500
            readTimeout = 3_500
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 SaiETF/1.0",
            )
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }

        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                error("HTTP $code")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
