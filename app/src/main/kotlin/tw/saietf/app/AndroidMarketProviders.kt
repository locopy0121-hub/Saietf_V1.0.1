package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONObject
import tw.saietf.core.market.MarketQuote
import tw.saietf.core.market.MarketQuoteProvider
import tw.saietf.core.market.MarketSource

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
