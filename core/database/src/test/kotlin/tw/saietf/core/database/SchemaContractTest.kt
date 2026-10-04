package tw.saietf.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.saietf.core.database.dao.LedgerDao
import tw.saietf.core.database.entity.DailySnapshotEntity
import tw.saietf.core.database.entity.LedgerEntryEntity
import tw.saietf.core.database.entity.ModelAllocationEntity
import tw.saietf.core.database.entity.PortfolioEntity

class SchemaContractTest {
    @Test
    fun ledgerPersistsIdempotencyCorrectionAndTimeBoundaries() {
        val fields = LedgerEntryEntity::class.java.declaredFields.map { it.name }.toSet()

        assertTrue("idempotencyKey" in fields)
        assertTrue("correctionOfEntryId" in fields)
        assertTrue("correctionReason" in fields)
        assertTrue("occurredAtEpochMillis" in fields)
        assertTrue("tradeDateTaipei" in fields)
        assertTrue("actualFee" in fields)
        assertTrue("actualTax" in fields)
    }

    @Test
    fun schemaContainsActualModelAndSnapshotRecords() {
        assertEquals(
            setOf("id", "name", "kind", "createdAtEpochMillis", "archivedAtEpochMillis"),
            PortfolioEntity::class.java.declaredFields.map { it.name }.toSet(),
        )
        assertTrue(ModelAllocationEntity::class.java.declaredFields.any { it.name == "basisPoints" })
        assertTrue(DailySnapshotEntity::class.java.declaredFields.any { it.name == "sourceRevision" })
        assertTrue(DailySnapshotEntity::class.java.declaredFields.any { it.name == "taipeiDate" })
    }

    @Test
    fun ledgerDaoExposesNoMutationOfExistingRows() {
        val methodNames = LedgerDao::class.java.declaredMethods.map { it.name }
        assertFalse(methodNames.any { it.startsWith("update", ignoreCase = true) })
        assertFalse(methodNames.any { it.startsWith("delete", ignoreCase = true) })
    }
}
