package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.json.JSONObject

data class TaiwanDailyBar(
    val epochMillis: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
)

class TaiwanDailyHistoryProvider {
    private data class CacheEntry(
        val fetchedAtEpochMillis: Long,
        val bars: List<TaiwanDailyBar>,
    )

    private val cache = linkedMapOf<String, CacheEntry>()
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    fun fetch(symbol: String): List<TaiwanDailyBar> {
        val normalized = symbol.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[normalized]?.takeIf { now - it.fetchedAtEpochMillis < CACHE_MILLIS }?.let {
                return it.bars
            }
        }

        val bars = listOf("TW", "TWO")
            .asSequence()
            .mapNotNull { suffix -> runCatching { fetchTicker("$normalized.$suffix") }.getOrNull() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

        synchronized(cache) {
            cache[normalized] = CacheEntry(now, bars)
        }
        return bars
    }

    private fun fetchTicker(ticker: String): List<TaiwanDailyBar> {
        val url =
            "https://query1.finance.yahoo.com/v8/finance/chart/$ticker" +
                "?interval=1d&range=1y&includePrePost=false&events=div%2Csplits"
        val body = httpGet(url)
        val result = JSONObject(body)
            .optJSONObject("chart")
            ?.optJSONArray("result")
            ?.optJSONObject(0)
            ?: return emptyList()
        val timestamps = result.optJSONArray("timestamp") ?: return emptyList()
        val quote = result
            .optJSONObject("indicators")
            ?.optJSONArray("quote")
            ?.optJSONObject(0)
            ?: return emptyList()
        val opens = quote.optJSONArray("open") ?: return emptyList()
        val highs = quote.optJSONArray("high") ?: return emptyList()
        val lows = quote.optJSONArray("low") ?: return emptyList()
        val closes = quote.optJSONArray("close") ?: return emptyList()
        val volumes = quote.optJSONArray("volume")

        val count = listOf(
            timestamps.length(),
            opens.length(),
            highs.length(),
            lows.length(),
            closes.length(),
        ).minOrNull() ?: 0

        return buildList {
            for (index in 0 until count) {
                if (
                    timestamps.isNull(index) ||
                    opens.isNull(index) ||
                    highs.isNull(index) ||
                    lows.isNull(index) ||
                    closes.isNull(index)
                ) continue
                val epochSeconds = timestamps.optLong(index, 0L)
                val open = opens.optDouble(index, Double.NaN)
                val high = highs.optDouble(index, Double.NaN)
                val low = lows.optDouble(index, Double.NaN)
                val close = closes.optDouble(index, Double.NaN)
                if (
                    epochSeconds <= 0L ||
                    !open.isFinite() || !high.isFinite() ||
                    !low.isFinite() || !close.isFinite() ||
                    open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0
                ) continue
                val epochMillis = epochSeconds * 1_000L
                Instant.ofEpochMilli(epochMillis).atZone(taipeiZone).toLocalDate()
                add(
                    TaiwanDailyBar(
                        epochMillis = epochMillis,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = volumes
                            ?.takeIf { index < it.length() && !it.isNull(index) }
                            ?.optLong(index, 0L)
                            ?.coerceAtLeast(0L)
                            ?: 0L,
                    ),
                )
            }
        }
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 6_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 SaiETF/1.0",
            )
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CACHE_MILLIS = 30L * 60L * 1_000L
    }
}
