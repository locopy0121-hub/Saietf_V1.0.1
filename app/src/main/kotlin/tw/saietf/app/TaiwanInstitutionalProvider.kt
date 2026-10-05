package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TaiwanInstitutionalFlow(
    val symbol: String,
    val taipeiDate: String,
    val foreignNetShares: Long?,
    val investmentTrustNetShares: Long?,
    val dealerNetShares: Long?,
    val source: String,
)

class TaiwanInstitutionalProvider {
    private val taipeiZone = ZoneId.of("Asia/Taipei")
    private val cache = linkedMapOf<String, Pair<Long, TaiwanInstitutionalFlow?>>()

    fun fetchLatest(symbol: String): TaiwanInstitutionalFlow? {
        val normalized = symbol.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return null
        val now = System.currentTimeMillis()
        synchronized(cache) {
            cache[normalized]?.takeIf { now - it.first < CACHE_MILLIS }?.let { return it.second }
        }

        val today = LocalDate.now(taipeiZone)
        var found: TaiwanInstitutionalFlow? = null
        for (offset in 0L..9L) {
            val date = today.minusDays(offset)
            found = runCatching { fetchTwse(normalized, date) }.getOrNull()
                ?: runCatching { fetchTpex(normalized, date) }.getOrNull()
            if (found != null) break
        }
        synchronized(cache) {
            cache[normalized] = now to found
        }
        return found
    }

    private fun fetchTwse(symbol: String, date: LocalDate): TaiwanInstitutionalFlow? {
        val ymd = date.format(DateTimeFormatter.BASIC_ISO_DATE)
        val body = httpGet(
            "https://www.twse.com.tw/rwd/zh/fund/T86?date=$ymd&selectType=ALL&response=json",
        )
        val root = JSONObject(body)
        val fields = root.optJSONArray("fields") ?: return null
        val data = root.optJSONArray("data") ?: return null
        return findRow(
            symbol = symbol,
            date = date,
            fields = fields,
            data = data,
            source = "TWSE 三大法人 T86",
        )
    }

    private fun fetchTpex(symbol: String, date: LocalDate): TaiwanInstitutionalFlow? {
        val dateText = date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.US))
        val body = httpGet(
            "https://www.tpex.org.tw/www/zh-tw/3insti/dailyTrade" +
                "?date=$dateText&type=Daily&sect=EW&response=json",
        )
        val root = JSONObject(body)
        val tables = root.optJSONArray("tables") ?: return null
        for (index in 0 until tables.length()) {
            val table = tables.optJSONObject(index) ?: continue
            val fields = table.optJSONArray("fields") ?: continue
            val data = table.optJSONArray("data") ?: continue
            val row = findRow(
                symbol = symbol,
                date = date,
                fields = fields,
                data = data,
                source = "TPEx 三大法人",
            )
            if (row != null) return row
        }
        return null
    }

    private fun findRow(
        symbol: String,
        date: LocalDate,
        fields: JSONArray,
        data: JSONArray,
        source: String,
    ): TaiwanInstitutionalFlow? {
        val names = (0 until fields.length()).map { fields.optString(it).replace("\n", "").trim() }
        val symbolIndex = names.indexOfFirst {
            it.contains("證券代號") || it.contains("股票代號") || it == "代號"
        }
        val foreignIndex = names.indexOfFirst {
            it.contains("外陸資買賣超") || it.contains("外資及陸資買賣超")
        }
        val trustIndex = names.indexOfFirst { it.contains("投信買賣超") }
        val dealerIndex = names.indexOfFirst {
            it.contains("自營商買賣超") && !it.contains("自行買賣") && !it.contains("避險")
        }
        if (symbolIndex < 0) return null

        for (rowIndex in 0 until data.length()) {
            val row = data.optJSONArray(rowIndex) ?: continue
            if (row.optString(symbolIndex).trim().uppercase(Locale.US) != symbol) continue
            return TaiwanInstitutionalFlow(
                symbol = symbol,
                taipeiDate = date.toString(),
                foreignNetShares = row.longAtOrNull(foreignIndex),
                investmentTrustNetShares = row.longAtOrNull(trustIndex),
                dealerNetShares = row.longAtOrNull(dealerIndex),
                source = source,
            )
        }
        return null
    }

    private fun JSONArray.longAtOrNull(index: Int): Long? {
        if (index < 0 || index >= length() || isNull(index)) return null
        return optString(index)
            .replace(",", "")
            .replace("+", "")
            .trim()
            .toLongOrNull()
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_500
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
        private const val CACHE_MILLIS = 30L * 60L * 1_000L
    }
}
