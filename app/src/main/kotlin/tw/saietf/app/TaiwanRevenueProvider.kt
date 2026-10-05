package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TaiwanRevenueSnapshot(
    val symbol: String,
    val yearMonth: String?,
    val currentMonthRevenueTwd: Long?,
    val previousMonthRevenueTwd: Long?,
    val lastYearMonthRevenueTwd: Long?,
    val monthOverMonthPct: Double?,
    val yearOverYearPct: Double?,
    val accumulatedRevenueTwd: Long?,
    val source: String,
)

class TaiwanRevenueProvider {
    private data class Endpoint(
        val source: String,
        val url: String,
    )

    private val endpoints = listOf(
        Endpoint(
            source = "TWSE OpenAPI 月營收",
            url = "https://openapi.twse.com.tw/v1/opendata/t187ap05_L",
        ),
        Endpoint(
            source = "TPEx OpenAPI 月營收",
            url = "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O",
        ),
    )

    @Volatile private var cachedAtEpochMillis: Long = 0L
    @Volatile private var cachedRows: Map<String, TaiwanRevenueSnapshot> = emptyMap()

    fun fetch(symbol: String): TaiwanRevenueSnapshot? {
        val normalized = symbol.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return null
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - cachedAtEpochMillis < CACHE_MILLIS) {
                return cachedRows[normalized]
            }
        }

        val rows = linkedMapOf<String, TaiwanRevenueSnapshot>()
        endpoints.forEach { endpoint ->
            runCatching { parseEndpoint(endpoint) }
                .getOrDefault(emptyList())
                .forEach { row -> rows.putIfAbsent(row.symbol, row) }
        }
        synchronized(this) {
            cachedAtEpochMillis = now
            cachedRows = rows.toMap()
        }
        return rows[normalized]
    }

    private fun parseEndpoint(endpoint: Endpoint): List<TaiwanRevenueSnapshot> {
        val body = httpGet(endpoint.url)
        val array = JSONArray(body)
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val symbol = row.textOf("公司代號", "證券代號", "股票代號")
                    ?.trim()
                    ?.uppercase(Locale.US)
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                add(
                    TaiwanRevenueSnapshot(
                        symbol = symbol,
                        yearMonth = row.textOf("資料年月", "年月")?.clean(),
                        currentMonthRevenueTwd = row.textOf("當月營收")?.numberLongOrNull(),
                        previousMonthRevenueTwd = row.textOf("上月營收")?.numberLongOrNull(),
                        lastYearMonthRevenueTwd = row.textOf("去年當月營收")?.numberLongOrNull(),
                        monthOverMonthPct = row.textOf(
                            "上月比較增減(%)",
                            "上月比較增減％",
                        )?.numberDoubleOrNull(),
                        yearOverYearPct = row.textOf(
                            "去年同月增減(%)",
                            "去年同月增減％",
                        )?.numberDoubleOrNull(),
                        accumulatedRevenueTwd = row.textOf(
                            "當月累計營收",
                            "本年累計營收",
                        )?.numberLongOrNull(),
                        source = endpoint.source,
                    ),
                )
            }
        }
    }

    private fun JSONObject.textOf(vararg keys: String): String? {
        keys.forEach { key ->
            if (has(key) && !isNull(key)) {
                val value = optString(key).trim()
                if (value.isNotBlank() && value != "-") return value
            }
        }
        return null
    }

    private fun String.clean(): String? = trim().takeIf { it.isNotBlank() && it != "-" }

    private fun String.numberLongOrNull(): Long? =
        replace(",", "").replace("+", "").trim().toLongOrNull()

    private fun String.numberDoubleOrNull(): Double? =
        replace(",", "").replace("%", "").replace("％", "").replace("+", "")
            .trim().toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 5_000
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
        private const val CACHE_MILLIS = 6L * 60L * 60L * 1_000L
    }
}
