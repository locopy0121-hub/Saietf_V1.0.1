package tw.saietf.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildContractTest {
    @Test
    fun `installed app exposes the approved identity and locale`() {
        assertEquals("tw.saietf.app", BuildConfig.APPLICATION_ID)
        assertEquals("1.0.51", BuildConfig.VERSION_NAME)
        assertEquals(10051, BuildConfig.VERSION_CODE)
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
    fun `v1051 keeps real daily K line enriched Taiwan company profiles and upgrade contracts`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val activity = File("src/main/kotlin/tw/saietf/app/MainActivity.kt").readText()
        val gradle = File("build.gradle.kts").readText()
        val providers = File("src/main/kotlin/tw/saietf/app/AndroidMarketProviders.kt").readText()
        val taiwanProfiles = File("src/main/kotlin/tw/saietf/app/TaiwanInstrumentInfoProvider.kt").readText()
        val dailyHistory = File("src/main/kotlin/tw/saietf/app/TaiwanDailyHistoryProvider.kt").readText()
        val kLineView = File("src/main/kotlin/tw/saietf/app/TaiwanKLineView.kt").readText()

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
        assertTrue(activity.contains("pageBuyCount"))
        assertTrue(activity.contains("pageSellCount"))
        assertTrue(activity.contains("pageGrossAmount"))
        assertTrue(activity.contains("showDividendCenter"))
        assertTrue(activity.contains("DatePickerDialog"))
        assertTrue(activity.contains("showBackupCenter"))
        assertTrue(activity.contains("ACTION_CREATE_DOCUMENT"))
        assertTrue(activity.contains("ACTION_OPEN_DOCUMENT"))
        assertTrue(activity.contains("showHoldingsAnalysisDialog"))
        assertTrue(activity.contains("Top 3"))
        assertTrue(activity.contains("MarketWallMode.DETAIL_LIST"))
        assertTrue(activity.contains("MarketWallMode.LARGE_LIST"))
        assertTrue(activity.contains("MarketWallMode.GRID"))
        assertTrue(activity.contains("MarketWallMode.MULTI_TREND"))
        assertTrue(activity.contains("MarketWallMode.TREND_SIGNAL"))
        assertTrue(activity.contains("chunked(3)"))
        assertTrue(activity.contains("MarketWallSort.CHANGE_PCT"))
        assertTrue(activity.contains("marketWallDialog?.isShowing == true"))
        assertTrue(activity.contains("controls.removeAllViews()"))
        assertTrue(activity.contains("marketWallMode"))
        assertTrue(activity.contains("marketWallSort"))
        assertTrue(activity.contains("marketWallDescending"))
        assertTrue(activity.contains("marketWallRender"))
        assertTrue(activity.contains("marketWallRender?.invoke()"))
        assertTrue(activity.contains("marketWallDialog?.dismiss()"))
        assertTrue(activity.contains("holdingDetailDialog?.dismiss()"))
        assertTrue(activity.contains("holdingDetailDialog = dialog"))
        assertTrue(activity.contains("setPositiveButton(\"寫入 Ledger\", null)"))
        assertTrue(activity.contains("setPositiveButton(\"儲存\", null)"))
        assertTrue(activity.contains("toLongOrNull()"))
        assertTrue(activity.contains("toDoubleOrNull()"))
        assertTrue(activity.contains("val tradeDate = dateInput(\"交易日期\""))
        assertTrue(activity.contains("交易日期不可晚於今天"))
        assertTrue(activity.contains("availableShares"))
        assertTrue(activity.contains("selectedSide == LedgerEntryKind.SELL"))
        assertTrue(activity.contains("超過目前持有"))
        assertTrue(activity.contains("股權登記日不可早於除息日"))
        assertTrue(activity.contains("發放日不可早於股權登記日"))
        assertTrue(activity.contains("saveButton.isEnabled = false"))
        assertTrue(activity.contains("saveButton.isEnabled = true"))
        assertTrue(activity.contains("performanceDialog?.dismiss()"))
        assertTrue(activity.contains("performanceDialogContent = null"))
        assertTrue(activity.contains("每日損益紀錄"))
        assertTrue(activity.contains("render.records"))
        assertTrue(activity.contains("takeLast(12)"))
        assertTrue(activity.contains("showHoldingSelectorDialog"))
        assertTrue(activity.contains("showHoldingDetailDialog"))
        assertTrue(activity.contains("平均成本") || activity.contains("\"均價\""))
        assertTrue(activity.contains("InstrumentInfoTab"))
        assertTrue(activity.contains("buildInstrumentMetricCard"))
        assertTrue(activity.contains("\"均價\""))
        assertTrue(activity.contains("\"市值\""))
        assertTrue(activity.contains("InstrumentInfoTab.COMPONENTS"))
        assertTrue(activity.contains("InstrumentInfoTab.FINANCIAL"))
        assertTrue(activity.contains("taiwanInstrumentInfoProvider"))
        assertTrue(activity.contains("台股基本資料"))
        assertTrue(activity.contains("實收資本額"))
        assertTrue(taiwanProfiles.contains("openapi.twse.com.tw"))
        assertTrue(taiwanProfiles.contains("tpex.org.tw"))
        assertTrue(taiwanProfiles.contains("公司代號"))
        assertTrue(taiwanProfiles.contains("實收資本額"))
        assertTrue(taiwanProfiles.contains("issuedCommonShares"))
        assertTrue(taiwanProfiles.contains("已發行普通股數"))
        assertTrue(activity.contains("已發行普通股"))
        assertTrue(activity.contains("公司聯絡資料"))
        assertTrue(activity.contains("taiwanDailyHistoryProvider"))
        assertTrue(activity.contains("TaiwanKLineView"))
        assertTrue(activity.contains("日 K｜近 1 年真實歷史資料"))
        assertTrue(dailyHistory.contains("interval=1d&range=1y"))
        assertTrue(dailyHistory.contains("TaiwanDailyBar"))
        assertTrue(kLineView.contains("drawRect"))
        assertTrue(taiwanProfiles.contains("issuedCommonShares"))
        assertTrue(taiwanProfiles.contains("已發行普通股數"))
        assertTrue(activity.contains("已發行普通股"))
        assertTrue(activity.contains("公司聯絡資料"))
        assertTrue(activity.contains("上一檔"))
        assertTrue(activity.contains("下一檔"))
        assertTrue(activity.contains("latestAsOf"))
        assertTrue(activity.contains("advancingCount"))
        assertTrue(activity.contains("decliningCount"))
        assertTrue(activity.contains("unchangedCount"))
        assertTrue(activity.contains("已要求行情中心立即刷新"))
        assertTrue(activity.contains("monthEstimated"))
        assertTrue(activity.contains("monthConfirmed"))
        assertTrue(activity.contains("monthConfirmedRows"))
        assertTrue(activity.contains("monthAnnouncedRows"))
        assertTrue(activity.contains("monthConfirmedCash"))
        assertTrue(activity.contains("showDividendCalendarDialog"))
        assertTrue(activity.contains("YearMonth.now"))
        assertTrue(activity.contains("本月目前沒有股息事件"))
        assertTrue(activity.contains("backupRepository.inspectJson"))
        assertTrue(activity.contains("confirmBackupRestore"))
        assertTrue(activity.contains("SHA-256"))
        assertTrue(activity.contains("showDisplaySettingsDialog"))
        assertTrue(activity.contains("displayScale"))
        assertTrue(activity.contains("saietf-display"))
        assertTrue(activity.contains("showSpacingSettingsDialog"))
        assertTrue(activity.contains("displaySpacingScale"))
        assertTrue(activity.contains("displayDp"))
        assertTrue(activity.contains("showSystemStatusDialog"))
        assertTrue(activity.contains("requestImmediateMarketRefresh"))
        assertTrue(activity.contains("showResetDisplaySettingsConfirmation"))
        assertTrue(activity.contains("確認恢復"))
        assertTrue(activity.contains("已要求行情中心立即更新"))
        assertTrue(activity.contains("系統狀態 / 診斷"))
        assertTrue(activity.contains("行情覆蓋"))
        assertTrue(activity.contains("quoteAgeSeconds"))
        assertTrue(activity.contains("staleSymbols"))
        assertTrue(activity.contains("showQuoteDiagnosticsDialog"))
        assertTrue(activity.contains("行情來源 / 品質診斷"))
        assertTrue(activity.contains("複製診斷"))
        assertTrue(activity.contains("setPrimaryClip"))
        assertTrue(!activity.contains("showMarketWall(option, sort, descending)"))
        assertTrue(activity.contains("listOf(10, 20, 50)"))
        assertTrue(providers.contains("YahooIntradayHistoryProvider"))
        assertTrue(providers.contains("interval=1m"))
        assertTrue(gradle.contains("saietf-development.jks"))
    }

    @Test
    fun `third stage exposes real ledger entry and truthful market placeholders`() {
        assertEquals("SaiETF 資產管家", FirstVersionContract.appDisplayName)
        assertEquals("第四十二階段 1.0.51｜個股走勢｜真實日 K 與一年歷史行情", FirstVersionContract.releaseLine)
        assertEquals("本機優先｜TWSE MIS → Yahoo｜Finance Lock 不變", FirstVersionContract.phaseLine)
        assertEquals(
            listOf("總資產", "帳務投入成本", "昨日 / 今日 / 總損益", "持股檔數"),
            FirstVersionContract.dashboardMetrics.map { it.title },
        )
        assertEquals(
            listOf("交易新增", "交易紀錄", "持股分析", "持股清單", "行情牆", "股息", "資料備份", "顯示設定", "卡片間距", "系統狀態", "立即更新行情", "介面恢復標準"),
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
