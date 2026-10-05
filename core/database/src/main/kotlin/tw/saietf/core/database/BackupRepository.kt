package tw.saietf.core.database

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.DividendEventEntity
import tw.saietf.core.database.entity.IntradayPortfolioPointEntity
import tw.saietf.core.database.entity.LedgerEntryEntity

class BackupRepository(
    private val database: SaiEtfDatabase,
    private val ledgerRepository: LedgerRepository,
) {
    data class RestoreResult(
        val ledgerCount: Int,
        val dailySnapshotCount: Int,
        val intradayPointCount: Int,
        val dividendCount: Int,
    )

    fun exportJson(): String {
        ledgerRepository.loadDashboard()
        val portfolioId = LedgerRepository.DEFAULT_PORTFOLIO_ID
        val payload = JSONObject().apply {
            put("databaseSchemaVersion", database.openHelper.readableDatabase.version)
            put("portfolioId", portfolioId)
            put("ledger", JSONArray().apply {
                database.ledgerDao().listChronological(portfolioId).forEach { put(it.toJson()) }
            })
            put("dailySnapshots", JSONArray().apply {
                database.dailySnapshotDao().recentBlocking(portfolioId, 100_000)
                    .asReversed()
                    .forEach { put(it.toJson()) }
            })
            put("intradayPoints", JSONArray().apply {
                database.intradayPortfolioPointDao().allBlocking(portfolioId)
                    .forEach { put(it.toJson()) }
            })
            put("dividends", JSONArray().apply {
                database.dividendEventDao().allBlocking(portfolioId)
                    .forEach { put(it.toJson()) }
            })
        }.toString()

        return JSONObject().apply {
            put("formatVersion", FORMAT_VERSION)
            put("createdAtEpochMillis", System.currentTimeMillis())
            put("payloadSha256", sha256(payload))
            put("payload", payload)
        }.toString(2)
    }

    fun restoreJson(raw: String): RestoreResult {
        val envelope = JSONObject(raw)
        require(envelope.getInt("formatVersion") == FORMAT_VERSION) {
            "不支援的備份格式"
        }
        val payloadText = envelope.getString("payload")
        val expectedHash = envelope.getString("payloadSha256")
        require(sha256(payloadText).equals(expectedHash, ignoreCase = true)) {
            "備份檔校驗失敗"
        }
        val payload = JSONObject(payloadText)
        require(payload.getInt("databaseSchemaVersion") <= SaiEtfDatabase.SCHEMA_VERSION) {
            "備份資料庫版本高於目前 App"
        }

        ledgerRepository.loadDashboard()
        require(database.ledgerDao().countBlocking(LedgerRepository.DEFAULT_PORTFOLIO_ID) == 0L) {
            "為避免覆寫不可變 Ledger，還原僅允許在沒有交易紀錄的帳務中執行"
        }

        val ledger = payload.getJSONArray("ledger")
        val daily = payload.getJSONArray("dailySnapshots")
        val intraday = payload.getJSONArray("intradayPoints")
        val dividends = payload.getJSONArray("dividends")

        database.runInTransaction {
            for (index in 0 until ledger.length()) {
                database.ledgerDao().insertBlocking(ledger.getJSONObject(index).toLedger())
            }
            for (index in 0 until daily.length()) {
                database.dailySnapshotDao().upsertBlocking(daily.getJSONObject(index).toDaily())
            }
            for (index in 0 until intraday.length()) {
                database.intradayPortfolioPointDao().upsertBlocking(
                    intraday.getJSONObject(index).toIntraday(),
                )
            }
            for (index in 0 until dividends.length()) {
                database.dividendEventDao().upsertBlocking(dividends.getJSONObject(index).toDividend())
            }
        }

        return RestoreResult(
            ledgerCount = ledger.length(),
            dailySnapshotCount = daily.length(),
            intradayPointCount = intraday.length(),
            dividendCount = dividends.length(),
        )
    }

    private fun LedgerEntryEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("portfolioId", portfolioId)
        put("idempotencyKey", idempotencyKey)
        put("entryType", entryType)
        put("symbol", symbol)
        put("shares", shares)
        put("price", price)
        putNullable("tradeMode", tradeMode)
        putNullable("actualFee", actualFee)
        putNullable("actualTax", actualTax)
        put("occurredAtEpochMillis", occurredAtEpochMillis)
        put("tradeDateTaipei", tradeDateTaipei)
        putNullable("note", note)
        putNullable("correctionOfEntryId", correctionOfEntryId)
        putNullable("correctionReason", correctionReason)
        put("createdAtEpochMillis", createdAtEpochMillis)
    }

    private fun DailySnapshotEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("portfolioId", portfolioId)
        put("taipeiDate", taipeiDate)
        put("sourceRevision", sourceRevision)
        put("capturedAtEpochMillis", capturedAtEpochMillis)
        put("totalMarketValue", totalMarketValue)
        put("totalInvestmentCost", totalInvestmentCost)
        put("totalNetLiquidationValue", totalNetLiquidationValue)
        put("totalUnrealizedProfit", totalUnrealizedProfit)
        put("realizedNetPnL", realizedNetPnL)
        put("totalDividendsReceived", totalDividendsReceived)
        put("comprehensivePnL", comprehensivePnL)
        put("dailyMarketPnL", dailyMarketPnL)
    }

    private fun IntradayPortfolioPointEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("portfolioId", portfolioId)
        put("taipeiDate", taipeiDate)
        put("bucketEpochMillis", bucketEpochMillis)
        put("capturedAtEpochMillis", capturedAtEpochMillis)
        put("totalMarketValue", totalMarketValue)
        put("todayPnl", todayPnl)
        put("totalPnl", totalPnl)
        put("quotedHoldingCount", quotedHoldingCount)
        put("expectedHoldingCount", expectedHoldingCount)
        put("sourceRevision", sourceRevision)
    }

    private fun DividendEventEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("portfolioId", portfolioId)
        put("symbol", symbol)
        put("exDateTaipei", exDateTaipei)
        putNullable("recordDateTaipei", recordDateTaipei)
        putNullable("paymentDateTaipei", paymentDateTaipei)
        put("cashPerShare", cashPerShare)
        put("status", status)
        put("sharesAtEntry", sharesAtEntry)
        put("estimatedCash", estimatedCash)
        put("createdAtEpochMillis", createdAtEpochMillis)
        put("updatedAtEpochMillis", updatedAtEpochMillis)
    }

    private fun JSONObject.toLedger() = LedgerEntryEntity(
        id = getString("id"),
        portfolioId = getString("portfolioId"),
        idempotencyKey = getString("idempotencyKey"),
        entryType = getString("entryType"),
        symbol = getString("symbol"),
        shares = getLong("shares"),
        price = getDouble("price"),
        tradeMode = nullableString("tradeMode"),
        actualFee = nullableLong("actualFee"),
        actualTax = nullableLong("actualTax"),
        occurredAtEpochMillis = getLong("occurredAtEpochMillis"),
        tradeDateTaipei = getString("tradeDateTaipei"),
        note = nullableString("note"),
        correctionOfEntryId = nullableString("correctionOfEntryId"),
        correctionReason = nullableString("correctionReason"),
        createdAtEpochMillis = getLong("createdAtEpochMillis"),
    )

    private fun JSONObject.toDaily() = DailySnapshotEntity(
        id = getString("id"),
        portfolioId = getString("portfolioId"),
        taipeiDate = getString("taipeiDate"),
        sourceRevision = getString("sourceRevision"),
        capturedAtEpochMillis = getLong("capturedAtEpochMillis"),
        totalMarketValue = getLong("totalMarketValue"),
        totalInvestmentCost = getDouble("totalInvestmentCost"),
        totalNetLiquidationValue = getLong("totalNetLiquidationValue"),
        totalUnrealizedProfit = getDouble("totalUnrealizedProfit"),
        realizedNetPnL = getDouble("realizedNetPnL"),
        totalDividendsReceived = getLong("totalDividendsReceived"),
        comprehensivePnL = getDouble("comprehensivePnL"),
        dailyMarketPnL = getLong("dailyMarketPnL"),
    )

    private fun JSONObject.toIntraday() = IntradayPortfolioPointEntity(
        id = getString("id"),
        portfolioId = getString("portfolioId"),
        taipeiDate = getString("taipeiDate"),
        bucketEpochMillis = getLong("bucketEpochMillis"),
        capturedAtEpochMillis = getLong("capturedAtEpochMillis"),
        totalMarketValue = getLong("totalMarketValue"),
        todayPnl = getLong("todayPnl"),
        totalPnl = getDouble("totalPnl"),
        quotedHoldingCount = getInt("quotedHoldingCount"),
        expectedHoldingCount = getInt("expectedHoldingCount"),
        sourceRevision = getString("sourceRevision"),
    )

    private fun JSONObject.toDividend() = DividendEventEntity(
        id = getString("id"),
        portfolioId = getString("portfolioId"),
        symbol = getString("symbol"),
        exDateTaipei = getString("exDateTaipei"),
        recordDateTaipei = nullableString("recordDateTaipei"),
        paymentDateTaipei = nullableString("paymentDateTaipei"),
        cashPerShare = getDouble("cashPerShare"),
        status = getString("status"),
        sharesAtEntry = getLong("sharesAtEntry"),
        estimatedCash = getLong("estimatedCash"),
        createdAtEpochMillis = getLong("createdAtEpochMillis"),
        updatedAtEpochMillis = getLong("updatedAtEpochMillis"),
    )

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.nullableLong(key: String): Long? =
        if (isNull(key)) null else getLong(key)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val FORMAT_VERSION = 1
    }
}
