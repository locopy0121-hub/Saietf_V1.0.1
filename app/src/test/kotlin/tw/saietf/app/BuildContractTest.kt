package tw.saietf.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildContractTest {
    @Test
    fun `installed app exposes the approved identity and locale`() {
        assertEquals("tw.saietf.app", BuildConfig.APPLICATION_ID)
        assertEquals("1.0.1", BuildConfig.VERSION_NAME)
        assertEquals(10001, BuildConfig.VERSION_CODE)
        assertEquals("zh-Hant-TW", SaiEtfApplication.DEFAULT_LOCALE_TAG)
    }

    @Test
    fun `build graph contains every approved module`() {
        assertEquals(
            setOf(
                ":app",
                ":core:model",
                ":core:finance",
                ":core:database",
                ":core:market",
                ":core:designsystem",
                ":feature:dashboard",
                ":feature:portfolios",
                ":feature:transactions",
                ":feature:dividends",
                ":feature:editor",
                ":feature:settings",
                ":platform:surfaces",
            ),
            BuildContract.includedModules,
        )
    }

    @Test
    fun `first version apk has a launchable activity`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `first version landing screen exposes approved modules without formulas`() {
        assertEquals("SaiETF 資產管家", FirstVersionContract.appDisplayName)
        assertEquals("第一版 1.0.1｜本機優先｜台股 ETF", FirstVersionContract.releaseLine)
        assertEquals(
            listOf(
                "帳務核心：TF Asset V3.7.8 Finance Lock 已鎖定",
                "資料層：Room 本機帳務資料庫 V1 已建立",
                "行情牆：預留台股 / ETF 即時行情入口",
                "股息：預留自動更新與待確認登錄入口",
                "建置：GitHub Actions 產出可安裝 APK",
            ),
            FirstVersionContract.landingSections,
        )
    }
}
