package tw.saietf.app

import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import tw.saietf.core.database.SaiEtfDatabase
import tw.saietf.core.database.entity.AfterHoursEntity
import tw.saietf.core.database.entity.DividendReferenceEntity
import tw.saietf.core.database.entity.EtfComponentEntity
import tw.saietf.core.database.entity.InstitutionalTradingEntity
import tw.saietf.core.database.entity.MonthlyRevenueEntity
import tw.saietf.core.database.entity.QuarterlyFinancialEntity

enum class StockDetailDataset {
    INSTITUTIONAL,
    MONTHLY_REVENUE,
    QUARTERLY_FINANCIAL,
    ETF_COMPONENT,
    DIVIDEND_REFERENCE,
    AFTER_HOURS,
}

enum class BatchDataQuality {
    VERIFIED,
    ESTIMATED,
    UNKNOWN,
}

enum class BatchFreshness {
    FRESH,
    STALE,
    EXPIRED,
}

data class StockDetailTtlPolicy(
    val institutionalMillis: Long = 24L * 60L * 60L * 1_000L,
    val monthlyRevenueMillis: Long = 24L * 60L * 60L * 1_000L,
    val quarterlyFinancialMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    val etfComponentMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    val dividendReferenceMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    val afterHoursMillis: Long = 24L * 60L * 60L * 1_000L,
) {
    fun ttl(dataset: StockDetailDataset): Long =
        when (dataset) {
            StockDetailDataset.INSTITUTIONAL -> institutionalMillis
            StockDetailDataset.MONTHLY_REVENUE -> monthlyRevenueMillis
            StockDetailDataset.QUARTERLY_FINANCIAL -> quarterlyFinancialMillis
            StockDetailDataset.ETF_COMPONENT -> etfComponentMillis
            StockDetailDataset.DIVIDEND_REFERENCE -> dividendReferenceMillis
            StockDetailDataset.AFTER_HOURS -> afterHoursMillis
        }
}

class StockDetailRepository(
    database: SaiEtfDatabase,
    private val ttlPolicy: StockDetailTtlPolicy = StockDetailTtlPolicy(),
) {
    private val dao = database.stockDetailDao()

    fun observeInstitutional(symbol: String): Flow<List<InstitutionalTradingEntity>> =
        dao.observeInstitutional(normalizeSymbol(symbol))

    fun observeMonthlyRevenue(symbol: String): Flow<List<MonthlyRevenueEntity>> =
        dao.observeMonthlyRevenue(normalizeSymbol(symbol))

    fun observeQuarterlyFinancial(symbol: String): Flow<List<QuarterlyFinancialEntity>> =
        dao.observeQuarterlyFinancial(normalizeSymbol(symbol))

    fun observeEtfComponents(symbol: String): Flow<List<EtfComponentEntity>> =
        dao.observeEtfComponents(normalizeSymbol(symbol))

    fun observeDividendReference(symbol: String): Flow<List<DividendReferenceEntity>> =
        dao.observeDividendReference(normalizeSymbol(symbol))

    fun observeAfterHours(symbol: String): Flow<List<AfterHoursEntity>> =
        dao.observeAfterHours(normalizeSymbol(symbol))

    suspend fun needsRefresh(
        symbol: String,
        dataset: StockDetailDataset,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalized = normalizeSymbol(symbol)
        val fetchedAt = when (dataset) {
            StockDetailDataset.INSTITUTIONAL ->
                dao.latestInstitutional(normalized)?.fetchedAtEpochMillis
            StockDetailDataset.MONTHLY_REVENUE ->
                dao.latestMonthlyRevenue(normalized)?.fetchedAtEpochMillis
            StockDetailDataset.QUARTERLY_FINANCIAL ->
                dao.latestQuarterlyFinancial(normalized)?.fetchedAtEpochMillis
            StockDetailDataset.ETF_COMPONENT ->
                dao.latestEtfComponentFetchedAt(normalized)
            StockDetailDataset.DIVIDEND_REFERENCE ->
                dao.latestDividendReference(normalized)?.fetchedAtEpochMillis
            StockDetailDataset.AFTER_HOURS ->
                dao.latestAfterHours(normalized)?.fetchedAtEpochMillis
        } ?: return true

        return nowEpochMillis - fetchedAt >= ttlPolicy.ttl(dataset)
    }

    suspend fun upsertInstitutional(rows: List<InstitutionalTradingEntity>) =
        dao.upsertInstitutional(rows)

    suspend fun upsertMonthlyRevenue(rows: List<MonthlyRevenueEntity>) =
        dao.upsertMonthlyRevenue(rows)

    suspend fun upsertQuarterlyFinancial(rows: List<QuarterlyFinancialEntity>) =
        dao.upsertQuarterlyFinancial(rows)

    suspend fun upsertEtfComponents(rows: List<EtfComponentEntity>) =
        dao.upsertEtfComponents(rows)

    suspend fun upsertDividendReference(rows: List<DividendReferenceEntity>) =
        dao.upsertDividendReference(rows)

    suspend fun upsertAfterHours(rows: List<AfterHoursEntity>) =
        dao.upsertAfterHours(rows)

    fun canonicalRevision(vararg parts: Any?): String {
        val canonical = parts.joinToString("\u001F") { value ->
            value?.toString()?.trim().orEmpty()
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun freshness(
        fetchedAtEpochMillis: Long,
        dataset: StockDetailDataset,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): BatchFreshness {
        val age = (nowEpochMillis - fetchedAtEpochMillis).coerceAtLeast(0L)
        val ttl = ttlPolicy.ttl(dataset)
        return when {
            age <= ttl -> BatchFreshness.FRESH
            age <= ttl * 2 -> BatchFreshness.STALE
            else -> BatchFreshness.EXPIRED
        }
    }

    private fun normalizeSymbol(symbol: String): String =
        symbol.trim().uppercase(Locale.US).also {
            require(it.isNotBlank()) { "symbol 不可為空白" }
        }
}
