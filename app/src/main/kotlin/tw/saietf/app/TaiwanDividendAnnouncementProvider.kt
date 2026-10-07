package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TaiwanDividendAnnouncement(
    val symbol: String,
    val name: String,
    val exDateTaipei: String,
    val cashPerShare: Double?,
    val cashText: String,
    val source: String,
)

class TaiwanDividendAnnouncementProvider {
    fun fetchForSymbols(symbols: Set<String>): List<TaiwanDividendAnnouncement> {
        val normalized = symbols
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotBlank() }
            .toSet()
        if (normalized.isEmpty()) return emptyList()

        return parseTwse(httpGet(TWSE_URL))
            .filter { it.symbol in normalized }
            .sortedWith(compareBy<TaiwanDividendAnnouncement> { it.exDateTaipei }.thenBy { it.symbol })
    }

    internal fun parseTwse(body: String): List<TaiwanDividendAnnouncement> {
        val root = JSONObject(body)
        val table = root.optJSONArray("tables")
            ?.optJSONObject(0)
            ?: root
        val fields = table.optJSONArray("fields")
            ?: root.optJSONArray("fields")
            ?: JSONArray()
        val rows = table.optJSONArray("data")
            ?: root.optJSONArray("data")
            ?: JSONArray()

        val dateIndex = fieldIndex(fields, "除權除息日期", "除權息日期")
        val symbolIndex = fieldIndex(fields, "股票代號", "證券代號")
        val nameIndex = fieldIndex(fields, "名稱", "股票名稱", "證券名稱")
        val cashIndex = fieldIndex(fields, "現金股利")
        if (dateIndex < 0 || symbolIndex < 0 || cashIndex < 0) return emptyList()

        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONArray(index) ?: continue
                val symbol = row.optString(symbolIndex)
                    .trim()
                    .uppercase(Locale.US)
                    .takeIf { it.isNotBlank() }
                    ?: continue
                val exDate = normalizeTaipeiDate(row.optString(dateIndex)) ?: continue
                val cashText = row.optString(cashIndex).trim()
                add(
                    TaiwanDividendAnnouncement(
                        symbol = symbol,
                        name = if (nameIndex >= 0) row.optString(nameIndex).trim() else "",
                        exDateTaipei = exDate,
                        cashPerShare = cashText
                            .replace(",", "")
                            .trim()
                            .toDoubleOrNull()
                            ?.takeIf { it.isFinite() && it > 0.0 },
                        cashText = cashText.ifBlank { "待公告" },
                        source = SOURCE,
                    ),
                )
            }
        }
    }

    private fun fieldIndex(fields: JSONArray, vararg candidates: String): Int {
        for (index in 0 until fields.length()) {
            val value = fields.optString(index)
                .replace(Regex("<[^>]+>"), "")
                .replace("\n", "")
                .trim()
            if (candidates.any { candidate -> value.contains(candidate) }) return index
        }
        return -1
    }

    private fun normalizeTaipeiDate(raw: String): String? {
        val value = raw.trim()
        Regex("""(\d{2,3})年(\d{1,2})月(\d{1,2})日""")
            .find(value)
            ?.destructured
            ?.let { (rocYear, month, day) ->
                return runCatching {
                    LocalDate.of(rocYear.toInt() + 1911, month.toInt(), day.toInt()).toString()
                }.getOrNull()
            }

        val parts = value.replace("-", "/").split("/")
        if (parts.size == 3) {
            return runCatching {
                val year = parts[0].trim().toInt()
                val gregorianYear = if (year < 1911) year + 1911 else year
                LocalDate.of(
                    gregorianYear,
                    parts[1].trim().toInt(),
                    parts[2].trim().toInt(),
                ).toString()
            }.getOrNull()
        }
        return null
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 7_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 SaiETF/1.0",
            )
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("TWSE HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val SOURCE = "TWSE 除權除息預告表 TWT48U"
        const val TWSE_URL = "https://www.twse.com.tw/exchangeReport/TWT48U?response=json"
    }
}
