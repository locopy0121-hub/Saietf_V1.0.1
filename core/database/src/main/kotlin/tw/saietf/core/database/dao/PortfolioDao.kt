package tw.saietf.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tw.saietf.core.database.entity.ModelAllocationEntity
import tw.saietf.core.database.entity.PortfolioEntity

@Dao
interface PortfolioDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(portfolio: PortfolioEntity)

    @Query("SELECT * FROM portfolios WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): PortfolioEntity?

    @Query(
        """
        SELECT * FROM portfolios
        WHERE archivedAtEpochMillis IS NULL
        ORDER BY createdAtEpochMillis ASC, id ASC
        """,
    )
    fun observeActive(): Flow<List<PortfolioEntity>>

    @Query(
        """
        UPDATE portfolios
        SET archivedAtEpochMillis = :archivedAtEpochMillis
        WHERE id = :id AND archivedAtEpochMillis IS NULL
        """,
    )
    suspend fun archive(id: String, archivedAtEpochMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModelAllocations(allocations: List<ModelAllocationEntity>)

    @Query("DELETE FROM model_allocations WHERE portfolioId = :portfolioId")
    suspend fun deleteModelAllocations(portfolioId: String)

    @Query(
        """
        SELECT * FROM model_allocations
        WHERE portfolioId = :portfolioId
        ORDER BY displayOrder ASC, symbol ASC
        """,
    )
    fun observeModelAllocations(portfolioId: String): Flow<List<ModelAllocationEntity>>
}
