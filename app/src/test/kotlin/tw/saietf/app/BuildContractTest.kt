package tw.saietf.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildContractTest {
    @Test
    fun `installed app exposes the approved identity and locale`() {
        assertEquals("tw.saietf.app", BuildConfig.APPLICATION_ID)
        assertEquals("1.0.2", BuildConfig.VERSION_NAME)
        assertEquals(10002, BuildConfig.VERSION_CODE)
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
    fun `next stage apk keeps a launchable activity`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `next stage landing screen exposes dashboard and actionable entries`() {
        assertEquals("SaiETF 資產管家", FirstVersionContract.appDisplayName)
        assertEquals("第二階段 1.0.2｜主頁雛型｜Room 帳務串接", FirstVersionContract.releaseLine)
        assertEquals("本機優先｜台股 ETF｜Finance Lock 不變", FirstVersionContract.phaseLine)
        assertEquals(
            listOf("總資產", "昨日 / 今日 / 總損益", "持股檔數"),
            FirstVersionContract.dashboardMetrics.map { it.title },
        )
        assertEquals(
            listOf("交易新增", "持股清單", "行情牆", "股息", "資料備份"),
            FirstVersionContract.landingCards.map { it.title },
        )
        assertTrue(
            FirstVersionContract.dashboardMetrics
                .single { it.title == "昨日 / 今日 / 總損益" }
                .note
                .contains("不互相加總混用"),
        )
        assertTrue(
            FirstVersionContract.landingCards
                .single { it.title == "持股清單" }
                .status
                .contains("UI 重算"),
        )
    }
}
