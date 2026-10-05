package tw.saietf.app

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.YearMonth
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tw.saietf.core.database.BackupRepository
import tw.saietf.core.database.DividendRepository
import tw.saietf.core.database.LedgerRepository
import tw.saietf.core.database.PerformanceHistoryRepository
import tw.saietf.core.finance.LedgerEntryKind
import tw.saietf.core.market.HoldingCost
import tw.saietf.core.market.MarketBatch
import tw.saietf.core.market.MarketDataCenter
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.PortfolioMarketValuation
import tw.saietf.core.market.PortfolioMarketValuator
import tw.saietf.core.market.QuoteQuality
import tw.saietf.core.model.TradeMode

class MainActivity : Activity() {
    private companion object {
        const val REQUEST_EXPORT_BACKUP = 4101
        const val REQUEST_IMPORT_BACKUP = 4102
    }

    private val ledgerExecutor = Executors.newSingleThreadExecutor()
    private val marketScheduler = Executors.newSingleThreadScheduledExecutor()
    private val valuator = PortfolioMarketValuator()
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private val displayScale: Float
        get() = when (
            getSharedPreferences("saietf-display", MODE_PRIVATE).getString("scale", "standard")
        ) {
            "compact" -> 0.90f
            "large" -> 1.15f
            else -> 1.00f
        }

    private val displaySpacingScale: Float
        get() = when (
            getSharedPreferences("saietf-display", MODE_PRIVATE).getString("spacing", "standard")
        ) {
            "tight" -> 0.75f
            "roomy" -> 1.30f
            else -> 1.00f
        }


    private val repository: LedgerRepository
        get() = (application as SaiEtfApplication).ledgerRepository

    private val marketDataCenter: MarketDataCenter
        get() = (application as SaiEtfApplication).marketDataCenter

    private val performanceHistoryRepository: PerformanceHistoryRepository
        get() = (application as SaiEtfApplication).performanceHistoryRepository

    private val dividendRepository: DividendRepository
        get() = (application as SaiEtfApplication).dividendRepository

    private val backupRepository: BackupRepository
        get() = (application as SaiEtfApplication).backupRepository

    private val intradayHistoryProvider: YahooIntradayHistoryProvider
        get() = (application as SaiEtfApplication).intradayHistoryProvider

    private enum class TrendRange(val label: String) {
        DAY("日"),
        WEEK("週"),
        MONTH("月"),
        YEAR("年"),
    }

    private enum class MarketWallMode(val label: String) {
        COMPACT("精簡"),
        DETAIL("詳細"),
    }

    private enum class MarketWallSort(val label: String) {
        SYMBOL("代號"),
        CHANGE_PCT("漲跌%"),
        PRICE("價格"),
    }

    @Volatile
    private var marketPollingActive = false

    private var marketWallDialog: AlertDialog? = null
    private var marketWallMode: MarketWallMode = MarketWallMode.COMPACT
    private var marketWallSort: MarketWallSort = MarketWallSort.CHANGE_PCT
    private var marketWallDescending: Boolean = true
    private var marketWallRender: (() -> Unit)? = null

    private var performanceDialog: AlertDialog? = null
    private var performanceDialogContent: LinearLayout? = null
    private var performanceRange: TrendRange = TrendRange.DAY

    @Volatile
    private var pollGeneration = 0L

    @Volatile
    private var latestLedgerSnapshot: LedgerRepository.DashboardSnapshot? = null

    @Volatile
    private var latestMarketBatch: MarketBatch? = null

    @Volatile
    private var latestValuation: PortfolioMarketValuation? = null

    @Volatile
    private var latestPreviousDayPnl: Long? = null

    @Volatile
    private var latestIntradayPointCount: Int = 0

    @Volatile
    private var lastHistoryWriteEpochMillis: Long = 0L

    @Volatile
    private var pendingBackupJson: String? = null

    private lateinit var totalAssetValue: TextView
    private lateinit var investmentCostValue: TextView
    private lateinit var pnlValue: TextView
    private lateinit var holdingsCountValue: TextView
    private lateinit var ledgerStatusValue: TextView
    private lateinit var marketStatusValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(248, 250, 252))
            setPadding(dp(20), dp(28), dp(20), dp(28))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        root.addView(
            TextView(this).apply {
                text = FirstVersionContract.appDisplayName
                textSize = 28f
                setTextColor(Color.rgb(15, 23, 42))
                gravity = Gravity.START
                setPadding(0, 0, 0, dp(4))
            },
        )
        root.addView(
            TextView(this).apply {
                text = FirstVersionContract.releaseLine
                textSize = 15f
                setTextColor(Color.rgb(71, 85, 105))
                setPadding(0, 0, 0, dp(4))
            },
        )
        root.addView(
            TextView(this).apply {
                text = FirstVersionContract.phaseLine
                textSize = 13f
                setTextColor(Color.rgb(100, 116, 139))
                setPadding(0, 0, 0, dp(18))
            },
        )

        root.addView(sectionTitle("資產儀表板"))

        val totalAsset = buildMetricCard(
            "總資產",
            "行情載入中",
            "完整行情覆蓋後才發布總市值",
        )
        totalAssetValue = totalAsset.second
        root.addView(totalAsset.first)

        val investmentCost = buildMetricCard(
            "帳務投入成本",
            "NT$ 0",
            "Room Ledger → Finance Lock 真實投影",
        )
        investmentCostValue = investmentCost.second
        root.addView(investmentCost.first)

        val pnl = buildMetricCard(
            "昨日 / 今日 / 總損益",
            "— / — / —",
            "三者為不同數據；昨日取上一交易日快照",
        )
        pnlValue = pnl.second
        pnl.first.isClickable = true
        pnl.first.isFocusable = true
        pnl.first.setOnClickListener { showDailyPerformanceDialog() }
        root.addView(pnl.first)

        val holdingCount = buildMetricCard(
            "持股檔數",
            "0 檔",
            "由不可變交易 Ledger 推導",
        )
        holdingsCountValue = holdingCount.second
        root.addView(holdingCount.first)

        ledgerStatusValue = statusText("帳務資料載入中…")
        marketStatusValue = statusText("行情中心等待持股資料…")
        root.addView(ledgerStatusValue)
        root.addView(marketStatusValue)

        root.addView(sectionTitle("功能入口"))
        FirstVersionContract.landingCards.forEach { card ->
            root.addView(buildLandingCard(card))
        }

        setContentView(
            ScrollView(this).apply {
                addView(root)
            },
        )

        refreshDashboard()
    }

    @Deprecated("Legacy activity result API retained for document compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQUEST_EXPORT_BACKUP -> {
                val json = pendingBackupJson ?: return
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("無法開啟輸出檔")
                }.onSuccess {
                    pendingBackupJson = null
                    Toast.makeText(this, "SaiETF 備份已匯出", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "備份匯出失敗：${error.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }

            REQUEST_IMPORT_BACKUP -> {
                ledgerExecutor.execute {
                    runCatching {
                        val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                            it.readText()
                        } ?: error("無法讀取備份檔")
                        raw to backupRepository.inspectJson(raw)
                    }.onSuccess { (raw, inspection) ->
                        runOnUiThread {
                            confirmBackupRestore(raw, inspection)
                        }
                    }.onFailure { error ->
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "備份檢查失敗：${error.message ?: "未知錯誤"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        marketPollingActive = true
        val generation = ++pollGeneration
        scheduleMarketRefresh(0L, generation)
    }

    override fun onPause() {
        marketPollingActive = false
        pollGeneration++
        super.onPause()
    }

    override fun onDestroy() {
        marketPollingActive = false
        pollGeneration++
        ledgerExecutor.shutdown()
        marketScheduler.shutdownNow()
        super.onDestroy()
    }

    private fun refreshDashboard() {
        ledgerExecutor.execute {
            runCatching { repository.loadDashboard() }
                .onSuccess { snapshot ->
                    latestLedgerSnapshot = snapshot
                    val today = LocalDate.now(taipeiZone).toString()
                    latestPreviousDayPnl = runCatching {
                        performanceHistoryRepository.previousTradingDay(today)?.dailyMarketPnL
                    }.getOrNull()
                    latestIntradayPointCount = runCatching {
                        performanceHistoryRepository.intradayPointCount(today)
                    }.getOrDefault(0)
                    runOnUiThread { applyLedgerSnapshot(snapshot) }
                    if (marketPollingActive) {
                        scheduleMarketRefresh(0L, pollGeneration)
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        ledgerStatusValue.text = "帳務載入失敗：${error.message ?: "未知錯誤"}"
                    }
                }
        }
    }

    private fun applyLedgerSnapshot(snapshot: LedgerRepository.DashboardSnapshot) {
        investmentCostValue.text = formatTwd(snapshot.totalInvestmentCost)
        holdingsCountValue.text = "${snapshot.holdingCount} 檔"
        ledgerStatusValue.text =
            "Ledger ${snapshot.ledgerCount} 筆｜已實現損益 ${formatSignedTwd(snapshot.realizedNetPnL)}"

        if (snapshot.holdings.isEmpty()) {
            totalAssetValue.text = "NT$ 0"
            pnlValue.text =
                "${latestPreviousDayPnl?.let(::formatSignedTwd) ?: "—"} / NT$ 0 / NT$ 0"
            marketStatusValue.text = "目前沒有持股，不需抓取行情"
            latestMarketBatch = null
            latestValuation = valuator.value(emptyList(), emptyMap())
        } else if (latestMarketBatch == null) {
            totalAssetValue.text = "行情載入中"
            pnlValue.text =
                "${latestPreviousDayPnl?.let(::formatSignedTwd) ?: "—"} / — / —"
            marketStatusValue.text = "行情中心準備更新 ${snapshot.holdings.size} 檔持股"
        }
    }

    private fun scheduleMarketRefresh(delayMillis: Long, generation: Long) {
        if (!marketPollingActive || generation != pollGeneration) return
        marketScheduler.schedule(
            {
                if (!marketPollingActive || generation != pollGeneration) return@schedule
                refreshMarketOnce(generation)
            },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun refreshMarketOnce(generation: Long) {
        if (!marketPollingActive || generation != pollGeneration) return

        val snapshot = runCatching { repository.loadDashboard() }.getOrNull()
            ?: latestLedgerSnapshot
        if (snapshot == null) {
            scheduleMarketRefresh(1_000L, generation)
            return
        }
        latestLedgerSnapshot = snapshot

        val symbols = snapshot.holdings.map { it.symbol }.toSet()
        if (symbols.isEmpty()) {
            runOnUiThread { applyLedgerSnapshot(snapshot) }
            scheduleMarketRefresh(30_000L, generation)
            return
        }

        val now = System.currentTimeMillis()
        val taipeiNow = Instant.ofEpochMilli(now).atZone(taipeiZone)
        val currentTaipeiDate = taipeiNow.toLocalDate().toString()
        val tradingSession = isTaipeiTradingSession(taipeiNow.dayOfWeek, taipeiNow.toLocalTime())

        val batch = marketDataCenter.refresh(
            symbols = symbols,
            nowEpochMillis = now,
            currentTaipeiDate = currentTaipeiDate,
            tradingSessionActive = tradingSession,
        )
        val valuation = valuator.value(
            holdings = snapshot.holdings.map {
                HoldingCost(
                    symbol = it.symbol,
                    shares = it.shares,
                    investmentCost = it.investmentCost,
                )
            },
            quotes = batch.quotes,
        )
        latestMarketBatch = batch
        latestValuation = valuation

        val freshCurrentSession = valuation.isComplete &&
            valuation.todayPnl != null &&
            batch.quotes.size == symbols.size &&
            batch.quotes.values.all { quote ->
                quote.quality != QuoteQuality.STALE &&
                    taipeiDateOf(quote.asOfEpochMillis) == currentTaipeiDate
            }

        if (
            freshCurrentSession &&
            now - lastHistoryWriteEpochMillis >= PerformanceHistoryRepository.INTRADAY_BUCKET_MILLIS
        ) {
            runCatching {
                performanceHistoryRepository.recordFreshValuation(
                    taipeiDate = currentTaipeiDate,
                    capturedAtEpochMillis = now,
                    totalMarketValue = valuation.totalMarketValue ?: 0L,
                    totalInvestmentCost = snapshot.totalInvestmentCost,
                    realizedNetPnL = snapshot.realizedNetPnL,
                    dailyMarketPnL = valuation.todayPnl ?: 0L,
                    totalUnrealizedProfit = valuation.totalPnl ?: 0.0,
                    quotedHoldingCount = valuation.quotedHoldingCount,
                    expectedHoldingCount = valuation.expectedHoldingCount,
                    recordIntraday = tradingSession,
                )
            }.onSuccess { history ->
                lastHistoryWriteEpochMillis = now
                latestPreviousDayPnl = history.previousTradingDayPnl
                latestIntradayPointCount = history.currentDayPointCount
            }
        }

        runOnUiThread {
            applyLedgerSnapshot(snapshot)
            applyMarketValuation(
                batch = batch,
                valuation = valuation,
                tradingSession = tradingSession,
                freshCurrentSession = freshCurrentSession,
            )
        }

        val nextDelay = if (tradingSession) 1_000L else 30_000L
        scheduleMarketRefresh(nextDelay, generation)
    }

    private fun applyMarketValuation(
        batch: MarketBatch,
        valuation: PortfolioMarketValuation,
        tradingSession: Boolean,
        freshCurrentSession: Boolean,
    ) {
        if (valuation.expectedHoldingCount == 0) return

        if (valuation.isComplete) {
            totalAssetValue.text = formatTwd(valuation.totalMarketValue ?: 0L)
            val previousText = latestPreviousDayPnl?.let(::formatSignedTwd) ?: "—"
            val todayText = if (freshCurrentSession) {
                valuation.todayPnl?.let(::formatSignedTwd) ?: "—"
            } else {
                "—"
            }
            pnlValue.text =
                "$previousText / $todayText / " +
                    "${valuation.totalPnl?.let(::formatSignedTwd) ?: "—"}"
        } else {
            totalAssetValue.text =
                "行情 ${valuation.quotedHoldingCount}/${valuation.expectedHoldingCount}｜暫不結算"
            pnlValue.text = "— / — / —"
        }

        val sources = batch.quotes.values
            .map { it.source }
            .distinct()
            .joinToString(" + ") {
                when (it) {
                    MarketSource.TWSE_MIS -> "TWSE MIS"
                    MarketSource.YAHOO -> "Yahoo"
                }
            }
            .ifBlank { "無可用來源" }

        val mode = if (tradingSession) "盤中 1 秒" else "非盤中 30 秒"
        val stale = if (batch.staleQuotes.isNotEmpty()) {
            "｜舊盤 ${batch.staleQuotes.size} 檔未納入"
        } else {
            ""
        }
        val trend = if (latestIntradayPointCount > 0) {
            "｜今日走勢 ${latestIntradayPointCount} 點"
        } else {
            ""
        }
        marketStatusValue.text =
            "行情中心：$sources｜$mode｜覆蓋 ${valuation.quotedHoldingCount}/" +
                "${valuation.expectedHoldingCount}$stale$trend"
        marketWallRender?.invoke()
    }

    private fun taipeiDateOf(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(taipeiZone).toLocalDate().toString()

    private fun isTaipeiTradingSession(day: DayOfWeek, time: LocalTime): Boolean {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        return !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(13, 30))
    }

    private fun showDailyPerformanceDialog() {
        showPerformanceDialog(TrendRange.DAY)
    }

    private data class PerformanceRender(
        val range: TrendRange,
        val series: List<PortfolioTrendPoint>,
        val chartStart: Long,
        val chartEnd: Long,
        val chartStartLabel: String,
        val chartEndLabel: String,
        val summary: String,
    )

    private fun showPerformanceDialog(range: TrendRange) {
        performanceRange = range

        val existing = performanceDialog
        if (existing?.isShowing == true) {
            refreshPerformanceDialog(existing)
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("資產走勢 / 損益統計")
            .setView(
                ScrollView(this).apply {
                    addView(content)
                },
            )
            .setPositiveButton("關閉", null)
            .create()

        dialog.setOnDismissListener {
            if (performanceDialog === dialog) {
                performanceDialog = null
                performanceDialogContent = null
            }
        }
        performanceDialog = dialog
        performanceDialogContent = content
        dialog.show()
        refreshPerformanceDialog(dialog)
    }

    private fun refreshPerformanceDialog(dialog: AlertDialog) {
        val requestedRange = performanceRange
        ledgerExecutor.execute {
            val render = buildPerformanceRender(requestedRange)
            runOnUiThread {
                if (performanceDialog !== dialog || !dialog.isShowing) return@runOnUiThread
                if (performanceRange != render.range) {
                    refreshPerformanceDialog(dialog)
                    return@runOnUiThread
                }

                val content = performanceDialogContent ?: return@runOnUiThread
                content.removeAllViews()

                val rangeRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    TrendRange.entries.forEach { option ->
                        addView(
                            Button(this@MainActivity).apply {
                                text = if (option == render.range) "● ${option.label}" else option.label
                                isEnabled = option != render.range
                                setOnClickListener {
                                    performanceRange = option
                                    refreshPerformanceDialog(dialog)
                                }
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ),
                        )
                    }
                }

                content.addView(rangeRow)
                content.addView(
                    PortfolioTrendView(this@MainActivity).apply {
                        setPadding(dp(6), dp(10), dp(6), dp(4))
                        setSeries(
                            values = render.series,
                            startEpochMillis = render.chartStart,
                            endEpochMillis = render.chartEnd,
                            startLabel = render.chartStartLabel,
                            endLabel = render.chartEndLabel,
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(230),
                    ),
                )
                content.addView(
                    TextView(this@MainActivity).apply {
                        text = render.summary
                        textSize = 14f
                        setTextColor(Color.rgb(51, 65, 85))
                        setPadding(0, dp(8), 0, dp(8))
                    },
                )
                dialog.setTitle("資產走勢 / 損益統計｜${render.range.label}")
            }
        }
    }

    private fun buildPerformanceRender(range: TrendRange): PerformanceRender {
        val today = LocalDate.now(taipeiZone)
        val todayText = today.toString()
        val sessionStart = today
            .atTime(9, 0)
            .atZone(taipeiZone)
            .toInstant()
            .toEpochMilli()
        val sessionEnd = today
            .atTime(13, 30)
            .atZone(taipeiZone)
            .toInstant()
            .toEpochMilli()

        if (range == TrendRange.DAY) {
            val holdings = (latestLedgerSnapshot ?: repository.loadDashboard())
                .holdings
                .associate { it.symbol to it.shares }
            val marketHistory = runCatching {
                intradayHistoryProvider.fetchPortfolioSeries(
                    holdings = holdings,
                    taipeiDate = todayText,
                )
            }.getOrDefault(emptyList())
            val localHistory = runCatching {
                performanceHistoryRepository.intradayPoints(todayText)
            }.getOrDefault(emptyList())

            val sourceLabel: String
            val series = if (marketHistory.size >= 2) {
                sourceLabel = "Yahoo 1 分行情"
                marketHistory.map {
                    PortfolioTrendPoint(
                        epochMillis = it.epochMillis,
                        value = it.totalMarketValue,
                    )
                }
            } else {
                sourceLabel = "本機盤中紀錄"
                localHistory.map {
                    PortfolioTrendPoint(
                        epochMillis = it.bucketEpochMillis,
                        value = it.totalMarketValue,
                    )
                }
            }.filter { it.epochMillis in sessionStart..sessionEnd }

            val values = series.map { it.value }
            val open = values.firstOrNull()
            val latest = values.lastOrNull()
            val high = values.maxOrNull()
            val low = values.minOrNull()
            val daySnapshot = runCatching {
                performanceHistoryRepository.dailyRange(todayText, todayText).lastOrNull()
            }.getOrNull()

            val summary = buildString {
                append("日｜台股交易時段 09:00–13:30")
                append("\n資料：$sourceLabel｜${series.size} 點")
                append("\n開盤 ${open?.let(::formatTwd) ?: "—"}")
                append("｜最新 ${latest?.let(::formatTwd) ?: "—"}")
                append("\n高 ${high?.let(::formatTwd) ?: "—"}")
                append("｜低 ${low?.let(::formatTwd) ?: "—"}")
                daySnapshot?.let {
                    append("\n\n$todayText｜當日 ${formatSignedTwd(it.dailyMarketPnL)}")
                    append("\n總市值 ${formatTwd(it.totalMarketValue)}")
                    append("｜持有總損益 ${formatSignedTwd(it.totalUnrealizedProfit)}")
                }
            }
            return PerformanceRender(
                range = range,
                series = series,
                chartStart = sessionStart,
                chartEnd = sessionEnd,
                chartStartLabel = "09:00",
                chartEndLabel = "13:30",
                summary = summary,
            )
        }

        val startDate = when (range) {
            TrendRange.WEEK -> today.minusDays(6)
            TrendRange.MONTH -> today.minusMonths(1).plusDays(1)
            TrendRange.YEAR -> today.minusYears(1).plusDays(1)
            TrendRange.DAY -> today
        }
        val startText = startDate.toString()
        val rows = runCatching {
            performanceHistoryRepository.dailyRange(startText, todayText)
        }.getOrDefault(emptyList())
        val stats = runCatching {
            performanceHistoryRepository.dailyStats(startText, todayText)
        }.getOrNull()
        val series = rows.map { row ->
            val epoch = LocalDate.parse(row.taipeiDate)
                .atTime(13, 30)
                .atZone(taipeiZone)
                .toInstant()
                .toEpochMilli()
            PortfolioTrendPoint(
                epochMillis = epoch,
                value = row.totalMarketValue,
            )
        }
        val values = series.map { it.value }
        val summary = buildString {
            append("${range.label}｜$startText ～ $todayText")
            append("\n資料：每日收盤快照｜交易日 ${rows.size} 天")
            append("\n起點 ${values.firstOrNull()?.let(::formatTwd) ?: "—"}")
            append("｜最新 ${values.lastOrNull()?.let(::formatTwd) ?: "—"}")
            append("\n高 ${values.maxOrNull()?.let(::formatTwd) ?: "—"}")
            append("｜低 ${values.minOrNull()?.let(::formatTwd) ?: "—"}")
            if (stats != null && stats.sampleCount > 0) {
                append("\n\n期間損益統計")
                append("\n合計 ${formatSignedTwd(stats.totalDailyPnl)}")
                append("｜平均 ${formatSignedTwd(stats.averageDailyPnl)}")
                append("\n最佳 ${stats.bestDayPnl?.let(::formatSignedTwd) ?: "—"}")
                append("｜最差 ${stats.worstDayPnl?.let(::formatSignedTwd) ?: "—"}")
            }
        }

        return PerformanceRender(
            range = range,
            series = series,
            chartStart = startDate.atStartOfDay(taipeiZone).toInstant().toEpochMilli(),
            chartEnd = today.plusDays(1).atStartOfDay(taipeiZone).toInstant().toEpochMilli() - 1L,
            chartStartLabel = if (range == TrendRange.YEAR) startDate.toString() else startDate.toString().substring(5),
            chartEndLabel = if (range == TrendRange.YEAR) today.toString() else todayText.substring(5),
            summary = summary,
        )
    }

    private fun confirmBackupRestore(
        raw: String,
        inspection: BackupRepository.Inspection,
    ) {
        val createdAt = Instant.ofEpochMilli(inspection.createdAtEpochMillis)
            .atZone(taipeiZone)
            .toLocalDateTime()
        val hashPreview = inspection.payloadSha256.take(12)
        val message = buildString {
            append("備份時間 $createdAt")
            append("\n格式 v${inspection.formatVersion}｜DB v${inspection.databaseSchemaVersion}")
            append("\nSHA-256 $hashPreview… 已驗證")
            append("\n\n交易 ${inspection.ledgerCount} 筆")
            append("｜每日快照 ${inspection.dailySnapshotCount} 筆")
            append("\n盤中走勢 ${inspection.intradayPointCount} 點")
            append("｜股息 ${inspection.dividendCount} 筆")
            append("\n\n還原仍遵守不可變 Ledger 規則；目前帳務已有交易時會拒絕覆寫。")
        }

        AlertDialog.Builder(this)
            .setTitle("確認備份還原")
            .setMessage(message)
            .setNegativeButton("取消", null)
            .setPositiveButton("確認還原") { _, _ ->
                ledgerExecutor.execute {
                    runCatching { backupRepository.restoreJson(raw) }
                        .onSuccess { restored ->
                            latestMarketBatch = null
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "還原完成：交易 ${restored.ledgerCount}、股息 ${restored.dividendCount}",
                                    Toast.LENGTH_LONG,
                                ).show()
                                refreshDashboard()
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "還原失敗：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
            .show()
    }

    private fun showBackupCenter() {
        AlertDialog.Builder(this)
            .setTitle("資料備份")
            .setMessage(
                "備份包含交易 Ledger、每日損益、盤中走勢與股息資料，" +
                    "並附 SHA-256 校驗。為避免覆寫不可變帳務，還原僅允許沒有交易紀錄的帳務。",
            )
            .setNegativeButton("關閉", null)
            .setNeutralButton("還原備份") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                startActivityForResult(intent, REQUEST_IMPORT_BACKUP)
            }
            .setPositiveButton("匯出備份") { _, _ ->
                ledgerExecutor.execute {
                    runCatching { backupRepository.exportJson() }
                        .onSuccess { json ->
                            pendingBackupJson = json
                            runOnUiThread {
                                val fileName =
                                    "SaiETF-backup-${LocalDate.now(taipeiZone)}.json"
                                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_TITLE, fileName)
                                }
                                startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "備份建立失敗：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
            .show()
    }

    private fun showDividendCenter() {
        ledgerExecutor.execute {
            val rows = runCatching { dividendRepository.recent(120) }.getOrDefault(emptyList())
            val monthKey = LocalDate.now(taipeiZone).toString().substring(0, 7)
            val monthRows = rows.filter { it.exDateTaipei.startsWith(monthKey) }
            val monthEstimated = monthRows.sumOf { it.estimatedCash }
            val monthConfirmed = monthRows
                .filter { it.status == DividendRepository.Status.CONFIRMED }
                .sumOf { it.estimatedCash }
            val body = if (rows.isEmpty()) {
                "目前沒有股息紀錄。可先登錄預告，待資訊確定後用相同代號與除息日更新。"
            } else {
                buildString {
                    append("$monthKey｜本月 ${monthRows.size} 筆")
                    append("｜預估 ${formatTwd(monthEstimated)}")
                    append("｜已確認 ${formatTwd(monthConfirmed)}")
                    append("\n\n")
                    append(
                        rows.joinToString("\n\n") { row ->
                            val status = if (row.status == DividendRepository.Status.CONFIRMED) {
                                "已確認"
                            } else {
                                "預告"
                            }
                            buildString {
                                append("${row.symbol}｜$status｜除息 ${row.exDateTaipei}")
                                append("\n每股 ${"%.4f".format(Locale.US, row.cashPerShare)}")
                                append("｜持股 ${row.sharesAtEntry} 股")
                                append("｜估算 ${formatTwd(row.estimatedCash)}")
                                row.recordDateTaipei?.let { append("\n股權登記 $it") }
                                row.paymentDateTaipei?.let { append("｜發放 $it") }
                            }
                        },
                    )
                }
            }

            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("股息中心")
                    .setMessage(body)
                    .setNegativeButton("關閉", null)
                    .setNeutralButton("月曆") { _, _ ->
                        showDividendCalendarDialog()
                    }
                    .setPositiveButton("新增 / 更新") { _, _ ->
                        showDividendEntryDialog()
                    }
                    .show()
            }
        }
    }

    private fun showDividendCalendarDialog() {
        ledgerExecutor.execute {
            val rows = runCatching { dividendRepository.recent(120) }.getOrDefault(emptyList())
            runOnUiThread {
                var month = YearMonth.now(taipeiZone)
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), 0)
                }
                val dialog = AlertDialog.Builder(this)
                    .setTitle("股息月曆")
                    .setView(
                        ScrollView(this).apply {
                            addView(content)
                        },
                    )
                    .setPositiveButton("關閉", null)
                    .create()

                fun render() {
                    content.removeAllViews()
                    val monthRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(
                            Button(this@MainActivity).apply {
                                text = "←"
                                setOnClickListener {
                                    month = month.minusMonths(1)
                                    render()
                                }
                            },
                        )
                        addView(
                            TextView(this@MainActivity).apply {
                                text = month.toString()
                                textSize = 18f
                                gravity = Gravity.CENTER
                                setTextColor(Color.rgb(15, 23, 42))
                            },
                            LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                1f,
                            ),
                        )
                        addView(
                            Button(this@MainActivity).apply {
                                text = "→"
                                setOnClickListener {
                                    month = month.plusMonths(1)
                                    render()
                                }
                            },
                        )
                    }
                    content.addView(monthRow)

                    val monthRows = rows
                        .filter { it.exDateTaipei.startsWith(month.toString()) }
                        .sortedBy { it.exDateTaipei }
                    val monthTotal = monthRows.sumOf { it.estimatedCash }
                    content.addView(
                        TextView(this@MainActivity).apply {
                            text = "本月 ${monthRows.size} 筆｜預估 ${formatTwd(monthTotal)}"
                            textSize = 14f
                            setTextColor(Color.rgb(71, 85, 105))
                            setPadding(0, dp(8), 0, dp(8))
                        },
                    )
                    if (monthRows.isEmpty()) {
                        content.addView(
                            TextView(this@MainActivity).apply {
                                text = "本月目前沒有股息事件"
                                textSize = 14f
                                setTextColor(Color.rgb(100, 116, 139))
                                setPadding(0, dp(8), 0, dp(8))
                            },
                        )
                    } else {
                        monthRows.forEach { row ->
                            content.addView(
                                TextView(this@MainActivity).apply {
                                    val status = if (row.status == DividendRepository.Status.CONFIRMED) {
                                        "已確認"
                                    } else {
                                        "預告"
                                    }
                                    text = "${row.exDateTaipei}｜${row.symbol}｜$status｜${formatTwd(row.estimatedCash)}"
                                    textSize = 14f
                                    setTextColor(Color.rgb(15, 23, 42))
                                    setPadding(0, dp(6), 0, dp(6))
                                },
                            )
                        }
                    }
                    dialog.setTitle("股息月曆｜$month")
                }

                render()
                dialog.show()
            }
        }
    }

    private fun showDividendEntryDialog() {
        val statusSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("預告", "已確認"),
            )
        }
        val symbol = input("代號，例如 0050")
        val cashPerShare = input("每股現金股利").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val exDate = dateInput("除息日", LocalDate.now(taipeiZone).toString())
        val recordDate = dateInput("股權登記日（可留空）", "")
        val paymentDate = dateInput("發放日（可留空）", "")

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(label("狀態"))
            addView(statusSpinner)
            addView(symbol)
            addView(cashPerShare)
            addView(exDate)
            addView(recordDate)
            addView(paymentDate)
        }

        AlertDialog.Builder(this)
            .setTitle("股息新增 / 更新")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存") { _, _ ->
                val command = runCatching {
                    DividendRepository.UpsertCommand(
                        symbol = symbol.text.toString(),
                        exDateTaipei = exDate.text.toString().trim(),
                        recordDateTaipei = recordDate.text.toString().trim().takeIf { it.isNotEmpty() },
                        paymentDateTaipei = paymentDate.text.toString().trim().takeIf { it.isNotEmpty() },
                        cashPerShare = cashPerShare.text.toString().trim().toDouble(),
                        status = if (statusSpinner.selectedItemPosition == 0) {
                            DividendRepository.Status.ANNOUNCED
                        } else {
                            DividendRepository.Status.CONFIRMED
                        },
                    )
                }.getOrElse { error ->
                    Toast.makeText(
                        this,
                        "股息欄位格式錯誤：${error.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG,
                    ).show()
                    return@setPositiveButton
                }

                ledgerExecutor.execute {
                    runCatching { dividendRepository.upsert(command) }
                        .onSuccess {
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "股息資料已儲存，可用相同代號與除息日再次更新",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                showDividendCenter()
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "股息未儲存：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
            .show()
    }

    private fun showTransactionHistoryDialog(
        pageIndex: Int = 0,
        pageSize: Int = 10,
    ) {
        ledgerExecutor.execute {
            val page = runCatching {
                repository.transactionPage(pageIndex = pageIndex, pageSize = pageSize)
            }.getOrElse { error ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "交易紀錄讀取失敗：${error.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@execute
            }

            val body = if (page.rows.isEmpty()) {
                "目前沒有交易紀錄。"
            } else {
                page.rows.joinToString("\n\n") { row ->
                    val side = if (row.side == LedgerEntryKind.BUY) "買進" else "賣出"
                    val mode = if (row.tradeMode == TradeMode.ROUND_LOT) "整股" else "零股"
                    buildString {
                        append("${row.tradeDateTaipei}｜$side｜${row.symbol}")
                        append("\n${row.shares} 股 × ${"%.2f".format(Locale.US, row.price)}｜$mode")
                        append("\n手續費 ${row.fee?.let(::formatTwd) ?: "—"}")
                        if (row.side == LedgerEntryKind.SELL) {
                            append("｜證交稅 ${row.tax?.let(::formatTwd) ?: "—"}")
                        }
                        row.note?.takeIf { it.isNotBlank() }?.let {
                            append("\n備註 $it")
                        }
                    }
                }
            }

            runOnUiThread {
                val sizeSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(10, 20, 50),
                    )
                    setSelection(listOf(10, 20, 50).indexOf(page.pageSize))
                }
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(6), dp(18), 0)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "第 ${page.pageIndex + 1} / ${page.totalPages} 頁｜共 ${page.totalCount} 筆"
                            textSize = 14f
                            setTextColor(Color.rgb(71, 85, 105))
                        },
                    )
                    addView(sizeSpinner)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = body
                            textSize = 14f
                            setTextColor(Color.rgb(15, 23, 42))
                            setPadding(0, dp(10), 0, dp(10))
                        },
                    )
                }

                val dialog = AlertDialog.Builder(this)
                    .setTitle("交易紀錄")
                    .setView(
                        ScrollView(this).apply {
                            addView(content)
                        },
                    )
                    .setNeutralButton("上一頁") { _, _ ->
                        showTransactionHistoryDialog(
                            pageIndex = (page.pageIndex - 1).coerceAtLeast(0),
                            pageSize = sizeSpinner.selectedItem as Int,
                        )
                    }
                    .setNegativeButton("關閉", null)
                    .setPositiveButton("下一頁") { _, _ ->
                        showTransactionHistoryDialog(
                            pageIndex = (page.pageIndex + 1).coerceAtMost(page.totalPages - 1),
                            pageSize = sizeSpinner.selectedItem as Int,
                        )
                    }
                    .create()

                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = page.pageIndex > 0
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                        page.pageIndex + 1 < page.totalPages
                    sizeSpinner.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(
                                parent: android.widget.AdapterView<*>?,
                                view: View?,
                                position: Int,
                                id: Long,
                            ) {
                                val selected = listOf(10, 20, 50)[position]
                                if (selected != page.pageSize) {
                                    dialog.dismiss()
                                    showTransactionHistoryDialog(0, selected)
                                }
                            }

                            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                        }
                }
                dialog.show()
            }
        }
    }

    private fun showTradeDialog() {
        val sideSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("買進", "賣出"),
            )
        }
        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("整股", "零股"),
            )
        }
        val symbol = input("代號，例如 0050")
        val shares = input("股數").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val price = input("成交價").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val fee = input("實際手續費（可留空）").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val tax = input("實際證交稅（賣出可留空）").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val tradeDate = input("交易日期 YYYY-MM-DD").apply {
            setText(LocalDate.now(taipeiZone).toString())
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(label("買賣別"))
            addView(sideSpinner)
            addView(label("交易模式"))
            addView(modeSpinner)
            addView(symbol)
            addView(shares)
            addView(price)
            addView(fee)
            addView(tax)
            addView(tradeDate)
        }

        AlertDialog.Builder(this)
            .setTitle("新增交易")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("寫入 Ledger") { _, _ ->
                val command = runCatching {
                    LedgerRepository.AddTradeCommand(
                        side = if (sideSpinner.selectedItemPosition == 0) {
                            LedgerEntryKind.BUY
                        } else {
                            LedgerEntryKind.SELL
                        },
                        symbol = symbol.text.toString(),
                        shares = shares.text.toString().trim().toLong(),
                        price = price.text.toString().trim().toDouble(),
                        tradeMode = if (modeSpinner.selectedItemPosition == 0) {
                            TradeMode.ROUND_LOT
                        } else {
                            TradeMode.ODD_LOT
                        },
                        tradeDateTaipei = tradeDate.text.toString().trim(),
                        actualFee = fee.text.toString().trim().takeIf { it.isNotEmpty() }?.toLong(),
                        actualTax = tax.text.toString().trim().takeIf { it.isNotEmpty() }?.toLong(),
                    )
                }.getOrElse { error ->
                    Toast.makeText(this, "欄位格式錯誤：${error.message}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }

                ledgerExecutor.execute {
                    runCatching { repository.addTrade(command) }
                        .onSuccess { snapshot ->
                            latestLedgerSnapshot = snapshot
                            latestMarketBatch = null
                            runOnUiThread {
                                applyLedgerSnapshot(snapshot)
                                Toast.makeText(this, "交易已寫入不可變 Ledger", Toast.LENGTH_SHORT).show()
                            }
                            if (marketPollingActive) {
                                scheduleMarketRefresh(0L, pollGeneration)
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "交易未寫入：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
            .show()
    }

    private fun showHoldingsAnalysisDialog() {
        val valuation = latestValuation
        if (valuation == null || !valuation.isComplete || valuation.totalMarketValue == null) {
            Toast.makeText(
                this,
                "持股分析需等待全部持股取得有效行情",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val total = valuation.totalMarketValue ?: return
        if (total <= 0L) {
            Toast.makeText(this, "目前沒有可分析的持股市值", Toast.LENGTH_SHORT).show()
            return
        }

        val rows = valuation.holdings
            .filter { (it.marketValue ?: 0L) > 0L }
            .sortedByDescending { it.marketValue ?: 0L }

        val weights = rows.map { row ->
            (row.marketValue ?: 0L).toDouble() / total.toDouble()
        }
        val top1 = weights.firstOrNull()?.times(100.0) ?: 0.0
        val top3 = weights.take(3).sum().times(100.0)

        val body = buildString {
            append("總市值 ${formatTwd(total)}")
            append("\nTop 1 集中度 ${"%.1f".format(Locale.US, top1)}%")
            append("｜Top 3 ${"%.1f".format(Locale.US, top3)}%")
            append("\n\n")
            append(
                rows.mapIndexed { index, row ->
                    val marketValue = row.marketValue ?: 0L
                    val weight = weights.getOrElse(index) { 0.0 } * 100.0
                    buildString {
                        append("${row.symbol}｜${"%.1f".format(Locale.US, weight)}%")
                        append("｜${formatTwd(marketValue)}")
                        append("\n今日 ${row.todayPnl?.let(::formatSignedTwd) ?: "—"}")
                        append("｜持有總損益 ${row.totalPnl?.let(::formatSignedTwd) ?: "—"}")
                    }
                }.joinToString("\n\n"),
            )
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(
                AllocationBarView(this@MainActivity).apply {
                    setPadding(0, dp(6), 0, dp(6))
                    setWeights(weights.map { it.toFloat() })
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(54),
                ),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = body
                    textSize = 14f
                    setTextColor(Color.rgb(15, 23, 42))
                    setPadding(0, dp(10), 0, dp(10))
                },
            )
        }

        AlertDialog.Builder(this)
            .setTitle("持股分析 / 資產配置")
            .setView(
                ScrollView(this).apply {
                    addView(content)
                },
            )
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showHoldingsDialog() {
        val snapshot = latestLedgerSnapshot
        val valuation = latestValuation
        if (snapshot == null) {
            Toast.makeText(this, "持股資料尚未載入", Toast.LENGTH_SHORT).show()
            return
        }

        val body = if (snapshot.holdings.isEmpty()) {
            "目前沒有持股。"
        } else {
            snapshot.holdings.joinToString("\n\n") { holding ->
                val market = valuation?.holdings?.firstOrNull { it.symbol == holding.symbol }
                val quote = market?.quote
                buildString {
                    append("${holding.symbol}｜${holding.shares} 股")
                    append("\n投入成本 ${formatTwd(holding.investmentCost)}")
                    if (quote != null) {
                        append("\n現價 ${quote.price}｜${sourceName(quote.source)}")
                        append("\n市值 ${market.marketValue?.let(::formatTwd) ?: "—"}")
                        append("\n今日 ${market.todayPnl?.let(::formatSignedTwd) ?: "—"}")
                        append("｜總損益 ${market.totalPnl?.let(::formatSignedTwd) ?: "—"}")
                    } else {
                        append("\n行情待更新")
                    }
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("持股清單")
            .setMessage(body)
            .setNeutralButton("個股明細") { _, _ ->
                showHoldingSelectorDialog()
            }
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showHoldingSelectorDialog() {
        val snapshot = latestLedgerSnapshot
        if (snapshot == null || snapshot.holdings.isEmpty()) {
            Toast.makeText(this, "目前沒有可查看的持股", Toast.LENGTH_SHORT).show()
            return
        }

        val symbols = snapshot.holdings.map { it.symbol }.sorted()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                symbols,
            )
        }

        AlertDialog.Builder(this)
            .setTitle("選擇持股")
            .setView(spinner)
            .setNegativeButton("取消", null)
            .setPositiveButton("查看") { _, _ ->
                showHoldingDetailDialog(spinner.selectedItem.toString())
            }
            .show()
    }

    private fun showHoldingDetailDialog(symbol: String) {
        val holding = latestLedgerSnapshot
            ?.holdings
            ?.firstOrNull { it.symbol == symbol }
        if (holding == null) {
            Toast.makeText(this, "找不到 $symbol 的持股資料", Toast.LENGTH_SHORT).show()
            return
        }
        val market = latestValuation
            ?.holdings
            ?.firstOrNull { it.symbol == symbol }
        val quote = market?.quote

        val body = buildString {
            append("$symbol｜${holding.shares} 股")
            append("\n投入成本 ${formatTwd(holding.investmentCost)}")
            if (holding.shares > 0L) {
                append("\n平均成本 ${formatTwd(holding.investmentCost / holding.shares.toDouble())}")
            }
            if (quote != null) {
                append("\n\n現價 ${"%.2f".format(Locale.US, quote.price)}")
                append("｜來源 ${sourceName(quote.source)}")
                append("\n市值 ${market.marketValue?.let(::formatTwd) ?: "—"}")
                append("\n今日損益 ${market.todayPnl?.let(::formatSignedTwd) ?: "—"}")
                append("｜持有總損益 ${market.totalPnl?.let(::formatSignedTwd) ?: "—"}")
                append("\n品質 ${quote.quality.name}")
                append("｜時間 ${Instant.ofEpochMilli(quote.asOfEpochMillis).atZone(taipeiZone).toLocalTime()}")
            } else {
                append("\n\n行情待更新")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("持股明細｜$symbol")
            .setMessage(body)
            .setNegativeButton("返回持股") { _, _ ->
                showHoldingsDialog()
            }
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showMarketWall() {
        if (marketWallDialog?.isShowing == true) {
            return
        }

        val initialBatch = latestMarketBatch
        if (initialBatch == null || initialBatch.quotes.isEmpty()) {
            Toast.makeText(this, "目前沒有可顯示的行情", Toast.LENGTH_SHORT).show()
            return
        }

        var mode = marketWallMode
        var sort = marketWallSort
        var descending = marketWallDescending

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("行情牆")
            .setView(
                ScrollView(this).apply {
                    addView(controls)
                },
            )
            .setPositiveButton("關閉", null)
            .create()

        fun render() {
            val batch = latestMarketBatch
                ?.takeIf { it.quotes.isNotEmpty() }
                ?: initialBatch

            val rows = batch.quotes.values.toList()
                .sortedWith(
                    Comparator { left, right ->
                        val result = when (sort) {
                            MarketWallSort.SYMBOL -> left.symbol.compareTo(right.symbol)
                            MarketWallSort.PRICE -> left.price.compareTo(right.price)
                            MarketWallSort.CHANGE_PCT -> {
                                val leftPct = left.previousClose
                                    ?.takeIf { it > 0.0 }
                                    ?.let { (left.price - it) / it * 100.0 }
                                    ?: Double.NEGATIVE_INFINITY
                                val rightPct = right.previousClose
                                    ?.takeIf { it > 0.0 }
                                    ?.let { (right.price - it) / it * 100.0 }
                                    ?: Double.NEGATIVE_INFINITY
                                leftPct.compareTo(rightPct)
                            }
                        }
                        if (descending) -result else result
                    },
                )

            val body = rows.joinToString("\n\n") { quote ->
                val previous = quote.previousClose
                val change = previous?.let { quote.price - it }
                val pct = previous
                    ?.takeIf { it > 0.0 }
                    ?.let { (quote.price - it) / it * 100.0 }
                buildString {
                    append("${quote.symbol} ${quote.name}")
                    append("\n現價 ${"%.2f".format(Locale.US, quote.price)}")
                    if (change != null && pct != null) {
                        append("｜${if (change > 0) "+" else ""}${"%.2f".format(Locale.US, change)}")
                        append(" (${if (pct > 0) "+" else ""}${"%.2f".format(Locale.US, pct)}%)")
                    }
                    if (mode == MarketWallMode.DETAIL) {
                        append("\n開 ${quote.open?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜高 ${quote.high?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜低 ${quote.low?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("\n來源 ${sourceName(quote.source)}｜品質 ${quote.quality.name}")
                        append("｜時間 ${Instant.ofEpochMilli(quote.asOfEpochMillis).atZone(taipeiZone).toLocalTime()}")
                    }
                }
            }

            dialog.setTitle("行情牆｜${mode.label}｜${sort.label}")
            controls.removeAllViews()

            val latestAsOf = batch.quotes.values.maxOfOrNull { it.asOfEpochMillis }
            controls.addView(
                TextView(this).apply {
                    text = buildString {
                        append("行情 ${batch.quotes.size} 檔")
                        latestAsOf?.let {
                            append("｜更新 ")
                            append(Instant.ofEpochMilli(it).atZone(taipeiZone).toLocalTime())
                        }
                        if (batch.staleQuotes.isNotEmpty()) {
                            append("｜舊盤 ${batch.staleQuotes.size} 檔")
                        }
                    }
                    textSize = 12f
                    setTextColor(Color.rgb(100, 116, 139))
                    setPadding(0, 0, 0, dp(6))
                },
            )

            val modeRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                MarketWallMode.entries.forEach { option ->
                    addView(
                        Button(this@MainActivity).apply {
                            text = if (option == mode) "● ${option.label}" else option.label
                            isEnabled = option != mode
                            setOnClickListener {
                                mode = option
                                marketWallMode = option
                                render()
                            }
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                }
            }
            controls.addView(modeRow)

            val sortRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                MarketWallSort.entries.forEach { option ->
                    addView(
                        Button(this@MainActivity).apply {
                            text = if (option == sort) "● ${option.label}" else option.label
                            isEnabled = option != sort
                            setOnClickListener {
                                sort = option
                                marketWallSort = option
                                render()
                            }
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                }
                addView(
                    Button(this@MainActivity).apply {
                        text = if (descending) "↓" else "↑"
                        setOnClickListener {
                            descending = !descending
                            marketWallDescending = descending
                            render()
                        }
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = "刷新"
                        setOnClickListener {
                            if (marketPollingActive) {
                                scheduleMarketRefresh(0L, pollGeneration)
                                Toast.makeText(
                                    this@MainActivity,
                                    "已要求行情中心立即刷新",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
            controls.addView(sortRow)
            controls.addView(
                TextView(this).apply {
                    text = body
                    textSize = 14f
                    setTextColor(Color.rgb(15, 23, 42))
                    setPadding(0, dp(10), 0, dp(10))
                },
            )
        }

        dialog.setOnDismissListener {
            if (marketWallDialog === dialog) {
                marketWallDialog = null
                marketWallRender = null
            }
        }
        marketWallDialog = dialog
        render()
        dialog.show()
        marketWallRender = {
            if (marketWallDialog === dialog && dialog.isShowing) {
                render()
            }
        }
    }

    private fun sourceName(source: MarketSource): String =
        when (source) {
            MarketSource.TWSE_MIS -> "TWSE MIS"
            MarketSource.YAHOO -> "Yahoo"
        }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 18f * displayScale
        setTextColor(Color.rgb(15, 23, 42))
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun statusText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f * displayScale
        setTextColor(Color.rgb(100, 116, 139))
        setPadding(0, 0, 0, dp(8))
    }

    private fun buildMetricCard(
        title: String,
        value: String,
        note: String,
    ): Pair<LinearLayout, TextView> {
        val valueView = cardText(value, 24f, Color.rgb(15, 23, 42))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }
            addView(cardText(title, 15f, Color.rgb(71, 85, 105)))
            addView(valueView)
            addView(cardText(note, 13f, Color.rgb(100, 116, 139)))
        }
        return card to valueView
    }

    private fun buildLandingCard(card: FirstVersionContract.LandingCard): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }
            addView(cardText(card.title, 17f, Color.rgb(15, 23, 42)))
            addView(cardText(card.body, 14f, Color.rgb(51, 65, 85)))
            addView(cardText(card.status, 13f, Color.rgb(100, 116, 139)))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when (card.title) {
                    "交易新增" -> showTradeDialog()
                    "交易紀錄" -> showTransactionHistoryDialog()
                    "持股分析" -> showHoldingsAnalysisDialog()
                    "持股清單" -> showHoldingsDialog()
                    "行情牆" -> showMarketWall()
                    "股息" -> showDividendCenter()
                    "資料備份" -> showBackupCenter()
                    "顯示設定" -> showDisplaySettingsDialog()
                    "卡片間距" -> showSpacingSettingsDialog()
                    "系統狀態" -> showSystemStatusDialog()
                }
            }
        }

    private fun showDisplaySettingsDialog() {
        val labels = listOf("精簡", "標準", "放大")
        val keys = listOf("compact", "standard", "large")
        val preferences = getSharedPreferences("saietf-display", MODE_PRIVATE)
        val currentKey = preferences.getString("scale", "standard") ?: "standard"
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            setSelection(keys.indexOf(currentKey).coerceAtLeast(0))
        }

        AlertDialog.Builder(this)
            .setTitle("顯示設定")
            .setMessage("調整共用標題與卡片文字比例；套用後立即重建畫面。")
            .setView(spinner)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢復標準") { _, _ ->
                preferences.edit().putString("scale", "standard").apply()
                recreate()
            }
            .setPositiveButton("套用") { _, _ ->
                preferences.edit()
                    .putString("scale", keys[spinner.selectedItemPosition])
                    .apply()
                recreate()
            }
            .show()
    }

    private fun showSystemStatusDialog() {
        val snapshot = latestLedgerSnapshot
        val valuation = latestValuation
        val batch = latestMarketBatch
        val sources = batch?.quotes?.values
            ?.map { sourceName(it.source) }
            ?.distinct()
            ?.joinToString(" + ")
            ?.ifBlank { "無" }
            ?: "無"
        val latestAsOf = batch?.quotes?.values?.maxOfOrNull { it.asOfEpochMillis }
        val quoteAgeSeconds = latestAsOf?.let {
            ((System.currentTimeMillis() - it).coerceAtLeast(0L) / 1_000L)
        }
        val staleSymbols = batch?.staleQuotes
            ?.keys
            ?.sorted()
            ?.joinToString(", ")
            ?.ifBlank { "無" }
            ?: "無"
        val body = buildString {
            append("版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            append("\n套件 ${BuildConfig.APPLICATION_ID}")
            append("\n\nLedger ${snapshot?.ledgerCount ?: 0} 筆")
            append("｜持股 ${snapshot?.holdingCount ?: 0} 檔")
            append("\n行情覆蓋 ${valuation?.quotedHoldingCount ?: 0}/${valuation?.expectedHoldingCount ?: 0}")
            append("｜來源 $sources")
            append("\n舊盤 ${batch?.staleQuotes?.size ?: 0} 檔")
            append("｜今日走勢 $latestIntradayPointCount 點")
            append("\n行情輪詢 ${if (marketPollingActive) "執行中" else "暫停"}")
            append("\n最新行情 ${latestAsOf?.let { Instant.ofEpochMilli(it).atZone(taipeiZone).toLocalTime() } ?: "—"}")
            append("｜距今 ${quoteAgeSeconds?.let { "${it}s" } ?: "—"}")
            append("\n舊盤標的 $staleSymbols")
            append("\n\n升級保護：固定 applicationId、固定開發簽章、versionCode 遞增、Room migration Gate")
        }

        AlertDialog.Builder(this)
            .setTitle("系統狀態 / 診斷")
            .setMessage(body)
            .setNegativeButton("重新整理") { _, _ ->
                refreshDashboard()
            }
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showSpacingSettingsDialog() {
        val labels = listOf("緊湊", "標準", "寬鬆")
        val keys = listOf("tight", "standard", "roomy")
        val preferences = getSharedPreferences("saietf-display", MODE_PRIVATE)
        val currentKey = preferences.getString("spacing", "standard") ?: "standard"
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            setSelection(keys.indexOf(currentKey).coerceAtLeast(0))
        }

        AlertDialog.Builder(this)
            .setTitle("卡片間距")
            .setMessage("調整首頁資產卡片與功能卡片的內距與卡片間距。")
            .setView(spinner)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢復標準") { _, _ ->
                preferences.edit().putString("spacing", "standard").apply()
                recreate()
            }
            .setPositiveButton("套用") { _, _ ->
                preferences.edit()
                    .putString("spacing", keys[spinner.selectedItemPosition])
                    .apply()
                recreate()
            }
            .show()
    }

    private fun dateInput(
        hintValue: String,
        initialValue: String,
    ): EditText = input(hintValue).apply {
        setText(initialValue)
        isFocusable = false
        isClickable = true
        setOnClickListener {
            val seed = text.toString().trim()
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now(taipeiZone)
            DatePickerDialog(
                this@MainActivity,
                { _, year, month, day ->
                    setText(LocalDate.of(year, month + 1, day).toString())
                },
                seed.year,
                seed.monthValue - 1,
                seed.dayOfMonth,
            ).show()
        }
        setOnLongClickListener {
            setText("")
            true
        }
    }

    private fun input(hintValue: String): EditText = EditText(this).apply {
        hint = hintValue
        textSize = 15f
        setTextColor(Color.rgb(15, 23, 42))
        setHintTextColor(Color.rgb(148, 163, 184))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.rgb(71, 85, 105))
        setPadding(0, dp(8), 0, 0)
    }

    private fun cardText(textValue: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp * displayScale
        setTextColor(color)
        setPadding(0, 0, 0, dp(4))
    }

    private fun formatTwd(value: Long): String =
        "NT$ " + NumberFormat.getIntegerInstance(Locale.TAIWAN).format(value)

    private fun formatTwd(value: Double): String =
        formatTwd(value.toLong())

    private fun formatSignedTwd(value: Long): String {
        val sign = if (value > 0L) "+" else ""
        return sign + formatTwd(value)
    }

    private fun formatSignedTwd(value: Double): String =
        formatSignedTwd(value.toLong())

    private fun displayDp(value: Int): Int = dp((value * displaySpacingScale).toInt().coerceAtLeast(1))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
