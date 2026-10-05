package tw.saietf.app

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class TaiwanInstrumentProfile(
    val symbol: String,
    val companyName: String,
    val shortName: String,
    val market: String,
    val industry: String?,
    val paidInCapitalTwd: Long?,
    val issuedCommonShares: Long?,
    val englishShortName: String?,
    val phone: String?,
    val website: String?,
    val listingDate: String?,
    val parValueText: String?,
    val chairman: String?,
    val generalManager: String?,
    val address: String?,
    val source: String,
)

class TaiwanInstrumentInfoProvider {
    private data class Endpoint(
        val market: String,
        val source: String,
        val url: String,
    )

    private val endpoints = listOf(
        Endpoint(
            market = "上市",
            source = "TWSE OpenAPI / MOPS",
            url = "https://openapi.twse.com.tw/v1/opendata/t187ap03_L",
        ),
        Endpoint(
            market = "上櫃",
            source = "TPEx OpenAPI / MOPS",
            url = "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap03_O",
        ),
    )

    @Volatile
    private var cachedAtEpochMillis: Long = 0L

    @Volatile
    private var cachedProfiles: Map<String, TaiwanInstrumentProfile> = emptyMap()

    fun fetchProfile(symbol: String): TaiwanInstrumentProfile? {
        val normalized = symbol.trim().uppercase(Locale.US)
        if (normalized.isBlank()) return null

        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now - cachedAtEpochMillis < CACHE_MILLIS) {
                return cachedProfiles[normalized]
            }
        }

        val profiles = linkedMapOf<String, TaiwanInstrumentProfile>()
        endpoints.forEach { endpoint ->
            runCatching {
                parseEndpoint(endpoint)
            }.getOrDefault(emptyList()).forEach { profile ->
                profiles.putIfAbsent(profile.symbol, profile)
            }
        }

        synchronized(this) {
            cachedAtEpochMillis = now
            cachedProfiles = profiles.toMap()
        }
        return profiles[normalized]
    }

    private fun parseEndpoint(endpoint: Endpoint): List<TaiwanInstrumentProfile> {
        val body = httpGet(endpoint.url)
        val rows = JSONArray(body)
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val symbol = row.textOf("公司代號", "股票代號", "證券代號")
                    ?.trim()
                    ?.uppercase(Locale.US)
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                val companyName = row.textOf("公司名稱", "名稱")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                val shortName = row.textOf("公司簡稱", "證券名稱")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: companyName
                add(
                    TaiwanInstrumentProfile(
                        symbol = symbol,
                        companyName = companyName,
                        shortName = shortName,
                        market = endpoint.market,
                        industry = row.textOf("產業別")?.clean(),
                        paidInCapitalTwd = row.textOf("實收資本額")
                            ?.numericLongOrNull(),
                        issuedCommonShares = row.textOf(
                            "已發行普通股數或TDR原發行股數",
                            "已發行普通股數",
                        )?.numericLongOrNull(),
                        englishShortName = row.textOf("英文簡稱")?.clean(),
                        phone = row.textOf("總機電話", "電話")?.clean(),
                        website = row.textOf("網址", "公司網址")?.clean(),
                        listingDate = row.textOf("上市日期", "上櫃日期")
                            ?.clean(),
                        parValueText = row.textOf("普通股每股面額")
                            ?.clean(),
                        chairman = row.textOf("董事長")?.clean(),
                        generalManager = row.textOf("總經理")?.clean(),
                        address = row.textOf("住址", "公司地址")?.clean(),
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

    private fun String.clean(): String? =
        trim().takeIf { it.isNotBlank() && it != "-" }

    private fun String.numericLongOrNull(): Long? =
        replace(",", "")
            .trim()
            .toLongOrNull()
            ?.takeIf { it >= 0L }

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
