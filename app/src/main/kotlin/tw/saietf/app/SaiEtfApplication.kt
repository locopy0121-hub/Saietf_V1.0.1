package tw.saietf.app

import android.app.Application
import tw.saietf.core.database.LedgerRepository
import tw.saietf.core.database.SaiEtfDatabase

class SaiEtfApplication : Application() {
    val database: SaiEtfDatabase by lazy {
        SaiEtfDatabase.build(this)
    }

    val ledgerRepository: LedgerRepository by lazy {
        LedgerRepository(database)
    }

    companion object {
        const val DEFAULT_LOCALE_TAG = "zh-Hant-TW"
    }
}
