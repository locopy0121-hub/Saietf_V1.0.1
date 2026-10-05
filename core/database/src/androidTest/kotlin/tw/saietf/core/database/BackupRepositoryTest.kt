package tw.saietf.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRepositoryTest {
    @Test
    fun backupRoundTripPreservesFormatAndRejectsInvalidVersion() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = SaiEtfDatabase.buildInMemoryForTests(context)
        val target = SaiEtfDatabase.buildInMemoryForTests(context)
        try {
            val sourceBackup = BackupRepository(source, LedgerRepository(source))
            val targetBackup = BackupRepository(target, LedgerRepository(target))

            val raw = sourceBackup.exportJson()
            val result = targetBackup.restoreJson(raw)

            assertEquals(0, result.ledgerCount)
            assertEquals(0, result.dailySnapshotCount)
            assertEquals(0, result.intradayPointCount)
            assertEquals(0, result.dividendCount)

            val invalidVersion = raw.replace("""formatVersion": 1""", """formatVersion": 9""")
            assertThrows(IllegalArgumentException::class.java) {
                targetBackup.restoreJson(invalidVersion)
            }
        } finally {
            source.close()
            target.close()
        }
    }
}
