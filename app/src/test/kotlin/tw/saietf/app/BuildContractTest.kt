package tw.saietf.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildContractTest {
    @Test
    fun `installed app exposes the approved identity and locale`() {
        assertEquals("tw.saietf.app", BuildConfig.APPLICATION_ID)
        assertEquals("1.0.30", BuildConfig.VERSION_NAME)
        assertEquals(10030, BuildConfig.VERSION_CODE)
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
    fun `v1030 keeps display settings backup preflight and upgrade contracts`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = File("src/main/kotlin/tw/saietf/app/MainActivity.kt").readText()
        val gradle = File("build.gradle.kts").readText()
        val providers = File("src/main/kotlin/tw/saietf/app/AndroidMarketProviders.kt").readText()

        assertTrue(manifest.contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
        assertTrue(manifest.contains("android.permission.INTERNET"))
        assertTrue(activity.contains("showTradeDialog"))
        assertTrue(activity.contains("ledgerRepository"))
        assertTrue(activity.contains("performanceHistoryRepository"))
        assertTrue(activity.contains("showDailyPerformanceDialog"))
        assertTrue(activity.contains("PortfolioTrendView"))
        assertTrue(activity.contains("TrendRange.WEEK"))
        assertTrue(activity.contains("TrendRange.MONTH"))
        assertTrue(activity.contains("TrendRange.YEAR"))
        assertTrue(activity.contains("09:00"))
        assertTrue(activity.contains("13:30"))
        assertTrue(activity.contains("showTransactionHistoryDialog"))
        assertTrue(activity.contains("showDividendCenter"))
        assertTrue(activity.contains("DatePickerDialog"))
        assertTrue(activity.contains("showBackupCenter"))
        assertTrue(activity.contains("ACTION_CREATE_DOCUMENT"))
        assertTrue(activity.contains("ACTION_OPEN_DOCUMENT"))
        assertTrue(activity.contains("showHoldingsAnalysisDialog"))
        assertTrue(activity.contains("Top 3"))
        assertTrue(activity.contains("MarketWallMode.DETAIL"))
        assertTrue(activity.contains("MarketWallSort.CHANGE_PCT"))
        assertTrue(activity.contains("marketWallDialog?.isShowing == true"))
        assertTrue(activity.contains("controls.removeAllViews()"))
        assertTrue(activity.contains("marketWallMode"))
        assertTrue(activity.contains("marketWallSort"))
        assertTrue(activity.contains("marketWallDescending"))
        assertTrue(activity.contains("marketWallRender"))
        assertTrue(activity.contains("marketWallRender?.invoke()"))
        assertTrue(activity.contains("showHoldingSelectorDialog"))
        assertTrue(activity.contains("showHoldingDetailDialog"))
        assertTrue(activity.contains("平均成本"))
        assertTrue(activity.contains("latestAsOf"))
        assertTrue(activity.contains("已要求行情中心立即刷新"))
        assertTrue(activity.contains("monthEstimated"))
        assertTrue(activity.contains("monthConfirmed"))
        assertTrue(activity.contains("showDividendCalendarDialog"))
        assertTrue(activity.contains("YearMonth.now"))
        assertTrue(activity.contains("本月目前沒有股息事件"))
        assertTrue(activity.contains("backupRepository.inspectJson"))
        assertTrue(activity.contains("confirmBackupRestore"))
        assertTrue(activity.contains("SHA-256"))
        assertTrue(activity.contains("showDisplaySettingsDialog"))
        assertTrue(activity.contains("displayScale"))
        assertTrue(activity.contains("saietf-display"))
        assertTrue(!activity.contains("showMarketWall(option, sort, descending)"))
        assertTrue(activity.contains("listOf(10, 20, 50)"))
        assertTrue(providers.contains("YahooIntradayHistoryProvider"))
        assertTrue(providers.contains("interval=1m"))
        assertTrue(gradle.contains("saietf-development.jks"))
    }

    @Test
    fun `third stage exposes real ledger entry and truthful market placeholders`() {
        assertEquals("SaiETF 資產管家", FirstVersionContract.appDisplayName)
        assertEquals("第二十一階段 1.0.30｜顯示設定｜字級比例持久化", FirstVersionContract.releaseLine)
        assertEquals("本機優先｜TWSE MIS → Yahoo｜Finance Lock 不變", FirstVersionContract.phaseLine)
        assertEquals(
            listOf("總資產", "帳務投入成本", "昨日 / 今日 / 總損益", "持股檔數"),
            FirstVersionContract.dashboardMetrics.map { it.title },
        )
        assertEquals(
            listOf("交易新增", "交易紀錄", "持股分析", "持股清單", "行情牆", "股息", "資料備份", "顯示設定"),
            FirstVersionContract.landingCards.map { it.title },
        )
        assertTrue(
            FirstVersionContract.dashboardMetrics
                .single { it.title == "總資產" }
                .note
                .contains("禁止用部分報價冒充總資產"),
        )
        assertTrue(
            FirstVersionContract.landingCards
                .single { it.title == "交易新增" }
                .status
                .contains("Ledger Repository"),
        )
    }
}
