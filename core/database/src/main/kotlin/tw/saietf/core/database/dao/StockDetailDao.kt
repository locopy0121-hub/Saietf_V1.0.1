package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.saietf.core.database.entity.AfterHoursEntity
import tw.saietf.core.database.entity.DividendReferenceEntity
import tw.saietf.core.database.entity.EtfComponentEntity
import tw.saietf.core.database.entity.InstitutionalTradingEntity
import tw.saietf.core.database.entity.MonthlyRevenueEntity
import tw.saietf.core.database.entity.QuarterlyFinancialEntity

@Dao
interface StockDetailDao {
    @Upsert
    suspend fun upsertInstitutional(rows: List<InstitutionalTradingEntity>)

    @Query("SELECT * FROM institutional_trading WHERE symbol = :symbol ORDER BY dataDate DESC")
    fun observeInstitutional(symbol: String): Flow<List<InstitutionalTradingEntity>>

    @Query("SELECT * FROM institutional_trading WHERE symbol = :symbol ORDER BY dataDate DESC LIMIT 1")
    suspend fun latestInstitutional(symbol: String): InstitutionalTradingEntity?

    @Upsert
    suspend fun upsertMonthlyRevenue(rows: List<MonthlyRevenueEntity>)

    @Query("SELECT * FROM monthly_revenue WHERE symbol = :symbol ORDER BY period DESC")
    fun observeMonthlyRevenue(symbol: String): Flow<List<MonthlyRevenueEntity>>

    @Query("SELECT * FROM monthly_revenue WHERE symbol = :symbol ORDER BY period DESC LIMIT 1")
    suspend fun latestMonthlyRevenue(symbol: String): MonthlyRevenueEntity?

    @Upsert
    suspend fun upsertQuarterlyFinancial(rows: List<QuarterlyFinancialEntity>)

    @Query("SELECT * FROM quarterly_financial WHERE symbol = :symbol ORDER BY period DESC")
    fun observeQuarterlyFinancial(symbol: String): Flow<List<QuarterlyFinancialEntity>>

    @Query("SELECT * FROM quarterly_financial WHERE symbol = :symbol ORDER BY period DESC LIMIT 1")
    suspend fun latestQuarterlyFinancial(symbol: String): QuarterlyFinancialEntity?

    @Upsert
    suspend fun upsertEtfComponents(rows: List<EtfComponentEntity>)

    @Query("SELECT * FROM etf_components WHERE symbol = :symbol ORDER BY period DESC, weightPct DESC")
    fun observeEtfComponents(symbol: String): Flow<List<EtfComponentEntity>>

    @Query("SELECT MAX(fetchedAtEpochMillis) FROM etf_components WHERE symbol = :symbol")
    suspend fun latestEtfComponentFetchedAt(symbol: String): Long?

    @Upsert
    suspend fun upsertDividendReference(rows: List<DividendReferenceEntity>)

    @Query("SELECT * FROM dividend_reference WHERE symbol = :symbol ORDER BY exDateTaipei DESC")
    fun observeDividendReference(symbol: String): Flow<List<DividendReferenceEntity>>

    @Query("SELECT * FROM dividend_reference WHERE symbol = :symbol ORDER BY exDateTaipei DESC LIMIT 1")
    suspend fun latestDividendReference(symbol: String): DividendReferenceEntity?

    @Upsert
    suspend fun upsertAfterHours(rows: List<AfterHoursEntity>)

    @Query("SELECT * FROM after_hours_market WHERE symbol = :symbol ORDER BY dataDate DESC")
    fun observeAfterHours(symbol: String): Flow<List<AfterHoursEntity>>

    @Query("SELECT * FROM after_hours_market WHERE symbol = :symbol ORDER BY dataDate DESC LIMIT 1")
    suspend fun latestAfterHours(symbol: String): AfterHoursEntity?
}
