package tw.saietf.app

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
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

class MainActivity : ComponentActivity() {
    private companion object {
        const val REQUEST_EXPORT_BACKUP = 4101
        const val REQUEST_IMPORT_BACKUP = 4102
    }

    private val ledgerExecutor = Executors.newSingleThreadExecutor()
    private val marketScheduler = Executors.newSingleThreadScheduledExecutor()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val valuator = PortfolioMarketValuator()
    private val taiwanInstrumentInfoProvider = TaiwanInstrumentInfoProvider()
    private val taiwanDailyHistoryProvider = TaiwanDailyHistoryProvider()
    private val taiwanInstitutionalProvider = TaiwanInstitutionalProvider()
    private val taiwanRevenueProvider = TaiwanRevenueProvider()
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

    private val fugleApiKeyStore: FugleApiKeyStore
        get() = (application as SaiEtfApplication).fugleApiKeyStore

    private val fugleStreamingController: FugleStreamingController
        get() = (application as SaiEtfApplication).fugleStreamingController

    private val marketPersistenceController: MarketPersistenceController
        get() = (application as SaiEtfApplication).marketPersistenceController

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
        DETAIL_LIST("詳細條列"),
        LARGE_LIST("大字條列"),
        GRID("簡易方格"),
        MULTI_TREND("多筆走勢"),
        TREND_SIGNAL("趨勢摘要"),
    }

    private enum class MarketWallSort(val label: String) {
        SYMBOL("代號"),
        CHANGE_PCT("漲跌%"),
        PRICE("價格"),
    }

    private enum class InstrumentInfoTab(val label: String) {
        DETAIL("明細"),
        TREND("走勢"),
        TECHNICAL("技術"),
        COMPONENTS("成分"),
        INSTITUTIONAL("法人"),
        FINANCIAL("財務"),
        AFTER_HOURS("盤後"),
        DATA("數據"),
    }

    private enum class MainTab(val label: String) {
        HOME("首頁"),
        MARKET("行情"),
        TRADE("交易"),
        DIVIDEND("股息"),
        SETTINGS("設定"),
    }

    @Volatile
    private var marketPollingActive = false

    private var marketWallDialog: AlertDialog? = null
    private var holdingDetailDialog: AlertDialog? = null
    private var marketWallMode: MarketWallMode = MarketWallMode.DETAIL_LIST
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
    private lateinit var pageContent: LinearLayout
    private lateinit var bottomNavigation: LinearLayout
    private var selectedMainTab: MainTab = MainTab.HOME
    private var homeHoldingsContainer: LinearLayout? = null
    private var marketQuotesContainer: LinearLayout? = null
    private var activeInstrumentSymbol: String? = null
    private var instrumentReturnTab: MainTab = MainTab.MARKET
    private var instrumentSelectedTab: InstrumentInfoTab = InstrumentInfoTab.DETAIL
    private var instrumentTabContentView: TextView? = null
    private var instrumentChartHost: LinearLayout? = null
    private val instrumentTabButtons = linkedMapOf<InstrumentInfoTab, Button>()
    private var instrumentProfilePage: TaiwanInstrumentProfile? = null
    private var instrumentDailyBarsPage: List<TaiwanDailyBar> = emptyList()
    private var instrumentInstitutionalPage: TaiwanInstitutionalFlow? = null
    private var instrumentRevenuePage: TaiwanRevenueSnapshot? = null
    private var instrumentProfileLoading = false
    private var instrumentProfileLoaded = false
    private var instrumentHistoryLoading = false
    private var instrumentHistoryLoaded = false
    private var instrumentInstitutionalLoading = false
    private var instrumentInstitutionalLoaded = false
    private var instrumentRevenueLoading = false
    private var instrumentRevenueLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installBackNavigation()

        window.statusBarColor = SaiTheme.PAGE_BACKGROUND
        window.navigationBarColor = SaiTheme.CARD
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SaiTheme.PAGE_BACKGROUND)
            fitsSystemWindows = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            addView(pageContent)
        }

        bottomNavigation = buildBottomNavigation()
        shell.addView(scroll)
        shell.addView(bottomNavigation)
        setContentView(shell)

        renderMainTab(MainTab.HOME)
        marketPersistenceController
        bindMarketStateFlow()
        refreshDashboard()
    }

    private fun buildBottomNavigation(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = SaiTheme.rounded(
                fill = SaiTheme.CARD,
                radiusDp = 22f,
                density = resources.displayMetrics.density,
                strokeColor = SaiTheme.BORDER,
                strokeDp = 1,
            )
            setPadding(displayDp(8), displayDp(8), displayDp(8), displayDp(10))
            elevation = dp(12).toFloat()
            MainTab.entries.forEach { tab ->
                addView(
                    TextView(this@MainActivity).apply {
                        text = tab.label
                        gravity = Gravity.CENTER
                        textSize = 12f * displayScale
                        setTextColor(SaiTheme.MUTED)
                        isClickable = true
                        isFocusable = true
                        contentDescription = "切換至${tab.label}"
                        setOnClickListener {
                            if (selectedMainTab != tab) {
                                renderMainTab(tab)
                                refreshDashboard()
                            }
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dp(54),
                            1f,
                        ).apply {
                            marginStart = displayDp(2)
                            marginEnd = displayDp(2)
                        }
                    },
                )
            }
        }

    private fun renderMainTab(tab: MainTab) {
        selectedMainTab = tab
        activeInstrumentSymbol = null
        homeHoldingsContainer = null
        marketQuotesContainer = null
        instrumentTabButtons.clear()
        pageContent.removeAllViews()
        when (tab) {
            MainTab.HOME -> renderHomePage()
            MainTab.MARKET -> renderMarketPage()
            MainTab.TRADE -> renderTradePage()
            MainTab.DIVIDEND -> renderDividendPage()
            MainTab.SETTINGS -> renderSettingsPage()
        }
        updateBottomNavigationSelection()
    }

    private fun updateBottomNavigationSelection() {
        MainTab.entries.forEachIndexed { index, tab ->
            val item = bottomNavigation.getChildAt(index) as? TextView ?: return@forEachIndexed
            val active = tab == selectedMainTab
            item.text = if (active) "●  ${tab.label}" else tab.label
            item.setTextColor(
                if (active) SaiTheme.BRAND else SaiTheme.MUTED,
            )
            item.setTypeface(
                item.typeface,
                if (active) {
                    android.graphics.Typeface.BOLD
                } else {
                    android.graphics.Typeface.NORMAL
                },
            )
            item.isSelected = active
            item.background = SaiTheme.rounded(
                fill = if (active) SaiTheme.BRAND_SOFT else SaiTheme.CARD,
                radiusDp = 17f,
                density = resources.displayMetrics.density,
                strokeColor = if (active) SaiTheme.BORDER else SaiTheme.CARD,
                strokeDp = 1,
            )
        }
    }

    private fun addPageHeader(
        title: String,
        subtitle: String,
    ) {
        val density = resources.displayMetrics.density
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.rounded(
                fill = SaiTheme.CARD,
                radiusDp = 24f,
                density = density,
                strokeColor = SaiTheme.BORDER,
                strokeDp = 1,
            )
            elevation = dp(1).toFloat()
            setPadding(dp(18), dp(15), dp(18), dp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(16)
            }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "SaiETF"
                            textSize = 12f * displayScale
                            setTextColor(SaiTheme.BRAND)
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            text = "  台股資產管家  •  ${BuildConfig.VERSION_NAME}"
                            textSize = 12f * displayScale
                            setTextColor(SaiTheme.MUTED)
                        },
                    )
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 27f * displayScale
                    setTextColor(SaiTheme.TEXT)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(6), 0, 0)
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f * displayScale
                    setTextColor(SaiTheme.TEXT_SECONDARY)
                    setPadding(0, dp(5), 0, 0)
                    setLineSpacing(0f, 1.08f)
                },
            )
            addView(
                View(this@MainActivity).apply {
                    setBackgroundColor(SaiTheme.BRAND_SOFT)
                    layoutParams = LinearLayout.LayoutParams(
                        dp(48),
                        dp(3),
                    ).apply {
                        topMargin = dp(10)
                    }
                },
            )
        }
        pageContent.addView(header)
    }

    private fun renderHomePage() {
        addPageHeader(
            "SaiETF",
            "台股 / ETF 資產總覽｜${BuildConfig.VERSION_NAME}",
        )

        val totalAsset = buildMetricCard(
            "總資產",
            "行情載入中",
            "完整行情覆蓋後才發布總市值",
        )
        totalAssetValue = totalAsset.second
        pageContent.addView(totalAsset.first)

        val investmentCost = buildMetricCard(
            "帳務投入成本",
            "NT$ 0",
            "Room Ledger → Finance Lock 真實投影",
        )
        investmentCostValue = investmentCost.second
        pageContent.addView(investmentCost.first)

        val pnl = buildMetricCard(
            "昨日 / 今日 / 總損益",
            "— / — / —",
            "昨日、今日與持有總損益為不同數據",
        )
        pnlValue = pnl.second
        pnl.first.isClickable = true
        pnl.first.isFocusable = true
        pnl.first.setOnClickListener { showDailyPerformanceDialog() }
        pageContent.addView(pnl.first)

        val holdingCount = buildMetricCard(
            "持股檔數",
            "0 檔",
            "點擊可查看持股與個股資訊",
        )
        holdingsCountValue = holdingCount.second
        holdingCount.first.isClickable = true
        holdingCount.first.setOnClickListener { showHoldingsDialog() }
        pageContent.addView(holdingCount.first)

        ledgerStatusValue = statusText("帳務資料載入中…")
        marketStatusValue = statusText("行情中心等待持股資料…")
        pageContent.addView(ledgerStatusValue)
        pageContent.addView(marketStatusValue)

        pageContent.addView(sectionTitle("快速操作"))
        pageContent.addView(
            buildPrimaryActionCard(
                title = "查看持股",
                description = "持股清單、均價、市值與個股 8 大資訊頁",
            ) { showHoldingsDialog() },
        )
        pageContent.addView(
            buildActionCard(
                title = "行情牆",
                description = "詳細條列、大字、方格、多筆走勢與趨勢摘要",
            ) { showMarketWall() },
        )

        pageContent.addView(sectionTitle("持股快照"))
        homeHoldingsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        pageContent.addView(homeHoldingsContainer)
        renderHomeHoldingsInline()

        latestLedgerSnapshot?.let(::applyLedgerSnapshot)
    }

    private fun renderMarketPage() {
        addPageHeader(
            "市場行情",
            "單一 MarketDataCenter｜Fugle → TWSE MIS → Yahoo",
        )
        pageContent.addView(
            buildPrimaryActionCard(
                title = "行情牆",
                description = "切換多種行情牆模式與排序",
            ) { showMarketWall() },
        )
        pageContent.addView(
            buildActionCard(
                title = "個股 / ETF 資訊",
                description = "明細、走勢、技術、成分、法人、財務、盤後、數據",
            ) { showHoldingSelectorDialog() },
        )
        pageContent.addView(
            buildActionCard(
                title = "立即同步行情",
                description = "要求行情中心立即刷新；仍遵守來源限流與熔斷",
            ) { requestImmediateMarketRefresh() },
        )
        pageContent.addView(sectionTitle("目前行情"))
        pageContent.addView(buildInlineMarketSortBar())
        marketQuotesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        pageContent.addView(marketQuotesContainer)
        renderMarketQuotesInline()
    }

    private fun renderHomeHoldingsInline() {
        val container = homeHoldingsContainer ?: return
        container.removeAllViews()
        val valuation = latestValuation
        val snapshot = latestLedgerSnapshot

        if (snapshot == null || snapshot.holdings.isEmpty()) {
            container.addView(statusText("目前沒有持股"))
            return
        }

        if (valuation == null || valuation.holdings.isEmpty()) {
            snapshot.holdings
                .sortedBy { it.symbol }
                .take(8)
                .forEach { holding ->
                    container.addView(
                        buildActionCard(
                            title = holding.symbol,
                            description = "${holding.shares} 股｜成本 ${formatTwd(holding.investmentCost)}｜行情待更新",
                        ) { showInstrumentPage(holding.symbol) },
                    )
                }
            return
        }

        valuation.holdings
            .sortedByDescending { it.marketValue ?: Long.MIN_VALUE }
            .take(8)
            .forEach { row ->
                val quote = row.quote
                val priceText = quote?.price
                    ?.let { String.format(Locale.US, "%.2f", it) }
                    ?: "—"
                val marketValueText = row.marketValue?.let(::formatTwd) ?: "—"
                container.addView(
                    buildHoldingSummaryCard(
                        symbol = row.symbol,
                        name = quote?.name ?: "",
                        shares = row.shares,
                        price = priceText,
                        marketValue = marketValueText,
                        totalPnl = row.totalPnl,
                    ) { showInstrumentPage(row.symbol) },
                )
            }
    }

    private fun buildHoldingSummaryCard(
        symbol: String,
        name: String,
        shares: Long,
        price: String,
        marketValue: String,
        totalPnl: Double?,
        action: () -> Unit,
    ): LinearLayout {
        val pnlText = totalPnl?.let(::formatSignedTwd) ?: "—"
        val pnlColor = when {
            totalPnl == null || totalPnl == 0.0 -> SaiTheme.FLAT
            totalPnl > 0.0 -> SaiTheme.GAIN
            else -> SaiTheme.LOSS
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(resources.displayMetrics.density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        cardText(symbol, 18f, SaiTheme.TEXT).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                    addView(
                        cardText(name, 14f, SaiTheme.TEXT_SECONDARY).apply {
                            setPadding(displayDp(9), 0, displayDp(8), 0)
                            maxLines = 2
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText(price, 18f, SaiTheme.TEXT).apply {
                            gravity = Gravity.END
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                },
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, displayDp(8), 0, 0)
                    addView(
                        cardText("$shares 股  •  $marketValue", 12f, SaiTheme.MUTED),
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText(pnlText, 14f, pnlColor).apply {
                            gravity = Gravity.END
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                },
            )

            isClickable = true
            isFocusable = true
            contentDescription = "$symbol $name，$shares 股，現價 $price，市值 $marketValue，總損益 $pnlText"
            setOnClickListener { action() }
        }
    }

    private fun buildInlineMarketSortBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, displayDp(10))
            val density = resources.displayMetrics.density
            MarketWallSort.entries.forEachIndexed { index, option ->
                val active = marketWallSort == option
                addView(
                    TextView(this@MainActivity).apply {
                        text = if (active) {
                            option.label + if (marketWallDescending) "  ↓" else "  ↑"
                        } else {
                            option.label
                        }
                        gravity = Gravity.CENTER
                        textSize = 12f * displayScale
                        setTypeface(
                            typeface,
                            if (active) {
                                android.graphics.Typeface.BOLD
                            } else {
                                android.graphics.Typeface.NORMAL
                            },
                        )
                        setTextColor(if (active) SaiTheme.BRAND else SaiTheme.TEXT_SECONDARY)
                        background = SaiTheme.rounded(
                            fill = if (active) SaiTheme.BRAND_SOFT else SaiTheme.CARD,
                            radiusDp = 16f,
                            density = density,
                            strokeColor = if (active) SaiTheme.BRAND else SaiTheme.BORDER,
                            strokeDp = 1,
                        )
                        isClickable = true
                        isFocusable = true
                        contentDescription = if (active) {
                            "${option.label}排序，目前${if (marketWallDescending) "降冪" else "升冪"}"
                        } else {
                            "依${option.label}排序"
                        }
                        setOnClickListener {
                            if (marketWallSort == option) {
                                marketWallDescending = !marketWallDescending
                            } else {
                                marketWallSort = option
                                marketWallDescending = true
                            }
                            renderMainTab(MainTab.MARKET)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            dp(46),
                            1f,
                        ).apply {
                            if (index > 0) marginStart = displayDp(4)
                        }
                    },
                )
            }
        }

    private fun renderMarketQuotesInline() {
        val container = marketQuotesContainer ?: return
        container.removeAllViews()
        val batch = latestMarketBatch
        if (batch == null || batch.quotes.isEmpty()) {
            container.addView(statusText("目前尚無可顯示行情"))
            return
        }

        val rows = batch.quotes.values
            .sortedWith(
                Comparator { left, right ->
                    val result = when (marketWallSort) {
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
                    if (marketWallDescending) -result else result
                },
            )

        rows.take(30).forEach { quote ->
            val changePct = quote.previousClose
                ?.takeIf { it > 0.0 }
                ?.let { (quote.price - it) / it * 100.0 }
            val changeText = changePct
                ?.let { String.format(Locale.US, "%+.2f%%", it) }
                ?: "—"
            val ageSeconds =
                ((System.currentTimeMillis() - quote.receivedAtEpochMillis).coerceAtLeast(0L) / 1_000L)
            container.addView(
                buildMarketQuoteCard(
                    symbol = quote.symbol,
                    name = quote.name,
                    price = String.format(Locale.US, "%.2f", quote.price),
                    changeText = changeText,
                    source = sourceName(quote.source),
                    quality = quote.quality.name,
                    ageSeconds = ageSeconds,
                ) { showInstrumentPage(quote.symbol) },
            )
        }
    }

    private fun buildMarketQuoteCard(
        symbol: String,
        name: String,
        price: String,
        changeText: String,
        source: String,
        quality: String,
        ageSeconds: Long,
        action: () -> Unit,
    ): LinearLayout {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        cardText(symbol, 18f, SaiTheme.TEXT).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                    addView(
                        cardText(name, 15f, SaiTheme.TEXT_SECONDARY).apply {
                            maxLines = 2
                            setPadding(dp(10), 0, dp(8), 0)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText(price, 20f, SaiTheme.TEXT).apply {
                            gravity = Gravity.END
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                },
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, 0)
                    val changeColor = when {
                        changeText.startsWith("+") -> SaiTheme.GAIN
                        changeText.startsWith("-") -> SaiTheme.LOSS
                        else -> SaiTheme.FLAT
                    }
                    addView(
                        cardText(changeText, 14f, changeColor).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                    addView(
                        cardText(
                            "$source  •  $quality  •  ${ageSeconds}s",
                            12f,
                            SaiTheme.MUTED,
                        ).apply {
                            gravity = Gravity.END
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                },
            )

            isClickable = true
            isFocusable = true
            contentDescription = "$symbol $name $price，$changeText，$source $quality"
            setOnClickListener { action() }
        }
    }

    private fun refreshVisibleMarketSections() {
        when (selectedMainTab) {
            MainTab.HOME -> renderHomeHoldingsInline()
            MainTab.MARKET -> renderMarketQuotesInline()
            else -> Unit
        }
    }

    private fun renderTradePage() {
        addPageHeader(
            "帳務中心",
            "Ledger 是帳務真值來源｜交易、持股與修正皆保留稽核軌跡",
        )

        val snapshot = latestLedgerSnapshot
        pageContent.addView(sectionTitle("帳務摘要"))
        val firstRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                buildInstrumentMetricCard(
                    "有效交易",
                    snapshot?.ledgerCount?.let { "$it 筆" } ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                buildInstrumentMetricCard(
                    "持股",
                    snapshot?.holdingCount?.let { "$it 檔" } ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        }
        pageContent.addView(firstRow)

        val secondRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(12))
            addView(
                buildInstrumentMetricCard(
                    "投入成本",
                    snapshot?.totalInvestmentCost?.let(::formatTwd) ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                buildInstrumentMetricCard(
                    "已實現損益",
                    snapshot?.realizedNetPnL?.let(::formatSignedTwd) ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        }
        pageContent.addView(secondRow)

        pageContent.addView(sectionTitle("快速建檔"))
        pageContent.addView(
            buildPrimaryActionCard("＋ 新增交易", "買進 / 賣出、整股 / 零股、日期與實際費稅") {
                showTradeDialog()
            },
        )
        pageContent.addView(sectionTitle("帳務工具"))
        pageContent.addView(
            buildActionCard("交易紀錄", "分頁查看、修改與刪除修正軌跡") {
                showTransactionHistoryDialog()
            },
        )
        pageContent.addView(
            buildActionCard("持股清單", "查看目前有效持股與損益") {
                showHoldingsDialog()
            },
        )
        pageContent.addView(
            buildActionCard("持股分析", "集中度、Top 1 / Top 3 與市值分布") {
                showHoldingsAnalysisDialog()
            },
        )

        pageContent.addView(sectionTitle("最近交易"))
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText("最近交易載入中…"))
        }
        pageContent.addView(preview)
        loadTradePreview(preview)
    }

    private fun loadTradePreview(container: LinearLayout) {
        ledgerExecutor.execute {
            val page = runCatching { repository.transactionPage(0, 10) }.getOrNull()
            runOnUiThread {
                if (selectedMainTab != MainTab.TRADE) return@runOnUiThread
                container.removeAllViews()
                val rows = page?.rows.orEmpty().take(5)
                if (rows.isEmpty()) {
                    container.addView(statusText("目前沒有交易紀錄"))
                    return@runOnUiThread
                }
                rows.forEach { row ->
                    container.addView(
                        buildTradePreviewCard(
                            symbol = row.symbol,
                            tradeDate = row.tradeDateTaipei,
                            isBuy = row.side == LedgerEntryKind.BUY,
                            shares = row.shares,
                            price = row.price,
                            mode = row.tradeMode.name,
                            edited = row.isEdited,
                        ) {
                            showTransactionHistoryDialog()
                        },
                    )
                }
                if ((page?.totalCount ?: 0L) > rows.size) {
                    container.addView(
                        statusText(
                            "顯示最近 ${rows.size} 筆，共 ${page?.totalCount ?: 0L} 筆；點交易紀錄可完整分頁查看",
                        ),
                    )
                }
            }
        }
    }

    private fun buildTradePreviewCard(
        symbol: String,
        tradeDate: String,
        isBuy: Boolean,
        shares: Long,
        price: Double,
        mode: String,
        edited: Boolean,
        action: () -> Unit,
    ): LinearLayout {
        val side = if (isBuy) "買進" else "賣出"
        val sideColor = if (isBuy) SaiTheme.GAIN else SaiTheme.LOSS
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(resources.displayMetrics.density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        cardText(side, 13f, sideColor).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            background = SaiTheme.rounded(
                                fill = SaiTheme.CARD_SOFT,
                                radiusDp = 12f,
                                density = resources.displayMetrics.density,
                                strokeColor = SaiTheme.BORDER,
                                strokeDp = 1,
                            )
                            setPadding(displayDp(9), displayDp(4), displayDp(9), displayDp(4))
                        },
                    )
                    addView(
                        cardText(symbol, 18f, SaiTheme.TEXT).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(displayDp(10), 0, 0, 0)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText(
                            String.format(Locale.US, "%.2f", price),
                            17f,
                            SaiTheme.TEXT,
                        ).apply {
                            gravity = Gravity.END
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                },
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, displayDp(8), 0, 0)
                    addView(
                        cardText("$shares 股  •  $mode", 12f, SaiTheme.TEXT_SECONDARY),
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText(
                            tradeDate + if (edited) "  •  已修改" else "",
                            12f,
                            SaiTheme.MUTED,
                        ).apply {
                            gravity = Gravity.END
                        },
                    )
                },
            )

            isClickable = true
            isFocusable = true
            contentDescription =
                "$tradeDate，$symbol，$side，$shares 股，價格 " +
                    String.format(Locale.US, "%.2f", price) +
                    if (edited) "，已修改" else ""
            setOnClickListener { action() }
        }
    }

    private fun renderDividendPage() {
        addPageHeader(
            "股息",
            "預告可先登錄，待公告確定後更新",
        )

        pageContent.addView(sectionTitle("本月摘要"))
        val monthValue = TextView(this).apply {
            text = "股息摘要載入中…"
            textSize = 14f * displayScale
            setTextColor(SaiTheme.TEXT_SECONDARY)
            background = SaiTheme.softCard(resources.displayMetrics.density)
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            setLineSpacing(0f, 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(12)
            }
        }
        pageContent.addView(monthValue)

        pageContent.addView(
            buildActionCard("股息中心", "新增 / 更新、月份統計與股息紀錄") {
                showDividendCenter()
            },
        )
        pageContent.addView(
            buildActionCard("股息月曆", "依月份查看預告與已確認股息") {
                showDividendCalendarDialog()
            },
        )

        pageContent.addView(sectionTitle("近期股息"))
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText("近期股息載入中…"))
        }
        pageContent.addView(preview)
        loadDividendPreview(monthValue, preview)
    }

    private fun loadDividendPreview(
        monthValue: TextView,
        container: LinearLayout,
    ) {
        ledgerExecutor.execute {
            val rows = runCatching { dividendRepository.recent(120) }.getOrDefault(emptyList())
            val currentMonth = YearMonth.now(taipeiZone)
            val currentYear = currentMonth.year.toString()
            val monthRows = rows.filter { row ->
                val paymentMonth = row.paymentDateTaipei?.take(7)
                val effectiveMonth = paymentMonth ?: row.exDateTaipei.take(7)
                effectiveMonth == currentMonth.toString()
            }
            val monthConfirmedCash = monthRows
                .filter { it.status == DividendRepository.Status.CONFIRMED }
                .sumOf { it.estimatedCash }
            val monthAnnouncedCash = monthRows
                .filter { it.status == DividendRepository.Status.ANNOUNCED }
                .sumOf { it.estimatedCash }
            val yearConfirmedCash = rows
                .filter {
                    it.status == DividendRepository.Status.CONFIRMED &&
                        (it.paymentDateTaipei ?: it.exDateTaipei).startsWith(currentYear)
                }
                .sumOf { it.estimatedCash }

            runOnUiThread {
                if (selectedMainTab != MainTab.DIVIDEND) return@runOnUiThread
                monthValue.text =
                    "${currentMonth}｜已確認 ${formatTwd(monthConfirmedCash)}｜預告 ${formatTwd(monthAnnouncedCash)}" +
                        "\n今年已確認 ${formatTwd(yearConfirmedCash)}｜本月 ${monthRows.size} 筆"

                container.removeAllViews()
                val recent = rows.take(6)
                if (recent.isEmpty()) {
                    container.addView(statusText("目前沒有股息事件"))
                    return@runOnUiThread
                }
                recent.forEach { row ->
                    val confirmed = row.status == DividendRepository.Status.CONFIRMED
                    val payment = row.paymentDateTaipei ?: "待公告"
                    container.addView(
                        buildDividendEventCard(
                            symbol = row.symbol,
                            confirmed = confirmed,
                            cash = formatTwd(row.estimatedCash),
                            exDate = row.exDateTaipei,
                            paymentDate = payment,
                            cashPerShare = String.format(Locale.US, "%.4f", row.cashPerShare),
                        ) {
                            showDividendCenter()
                        },
                    )
                }
            }
        }
    }

    private fun buildDividendEventCard(
        symbol: String,
        confirmed: Boolean,
        cash: String,
        exDate: String,
        paymentDate: String,
        cashPerShare: String,
        action: () -> Unit,
    ): LinearLayout {
        val status = if (confirmed) "已確認" else "預告"
        val statusColor = if (confirmed) SaiTheme.LOSS else SaiTheme.ACCENT
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(resources.displayMetrics.density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        cardText(symbol, 18f, SaiTheme.TEXT).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText(status, 12f, statusColor).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            background = SaiTheme.rounded(
                                fill = if (confirmed) SaiTheme.CARD_SOFT else SaiTheme.ACCENT_SOFT,
                                radiusDp = 12f,
                                density = resources.displayMetrics.density,
                            )
                            setPadding(displayDp(9), displayDp(4), displayDp(9), displayDp(4))
                        },
                    )
                },
            )

            addView(
                cardText(cash, 21f, SaiTheme.TEXT).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, displayDp(7), 0, displayDp(6))
                },
            )

            addView(
                cardText(
                    "除息 $exDate  •  發放 $paymentDate",
                    12f,
                    SaiTheme.TEXT_SECONDARY,
                ),
            )
            addView(
                cardText(
                    "每股 $cashPerShare",
                    12f,
                    SaiTheme.MUTED,
                ).apply {
                    setPadding(0, displayDp(3), 0, 0)
                },
            )

            isClickable = true
            isFocusable = true
            contentDescription = "$symbol，$status，$cash，除息 $exDate，發放 $paymentDate，每股 $cashPerShare"
            setOnClickListener { action() }
        }
    }

    private fun renderSettingsPage() {
        addPageHeader(
            "設定",
            "介面、行情、資料備份與系統診斷",
        )

        val fugleHealth = fugleStreamingController.health()
        pageContent.addView(
            buildSettingsStatusCard(
                version = BuildConfig.VERSION_NAME,
                fugleConfigured = fugleApiKeyStore.hasKey(),
                circuitState = fugleHealth.circuitState.name,
                availability = fugleHealth.availability.name,
            ),
        )

        pageContent.addView(sectionTitle("介面"))
        pageContent.addView(buildActionCard("顯示設定", "調整全域文字比例") {
            showDisplaySettingsDialog()
        })
        pageContent.addView(buildActionCard("卡片間距", "緊湊 / 標準 / 寬鬆") {
            showSpacingSettingsDialog()
        })
        pageContent.addView(buildActionCard("恢復介面標準", "清除顯示比例與間距偏好") {
            showResetDisplaySettingsConfirmation()
        })

        pageContent.addView(sectionTitle("行情"))
        pageContent.addView(buildActionCard("Fugle 即時行情", "安全設定或更換 API Key") {
            showFugleSettingsDialog()
        })
        pageContent.addView(buildActionCard("立即更新行情", "立即要求行情中心同步") {
            requestImmediateMarketRefresh()
        })

        pageContent.addView(sectionTitle("資料與系統"))
        pageContent.addView(buildActionCard("資料備份", "匯出 / 還原本機 JSON 備份") {
            showBackupCenter()
        })
        pageContent.addView(buildActionCard("系統狀態", "行情來源、品質、延遲與 Provider Health") {
            showSystemStatusDialog()
        })
    }

    private fun buildSettingsStatusCard(
        version: String,
        fugleConfigured: Boolean,
        circuitState: String,
        availability: String,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.softCard(resources.displayMetrics.density)
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(12)
            }
            addView(
                cardText("系統摘要", 14f, SaiTheme.BRAND).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                },
            )
            addView(
                cardText(
                    "SaiETF $version  •  Room v5",
                    16f,
                    SaiTheme.TEXT,
                ).apply {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, displayDp(4), 0, displayDp(4))
                },
            )
            addView(
                cardText(
                    "Fugle ${if (fugleConfigured) "已設定" else "未設定"}  •  $circuitState / $availability",
                    12f,
                    SaiTheme.MUTED,
                ),
            )
        }

    private fun buildPrimaryActionCard(
        title: String,
        description: String,
        action: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.rounded(
                fill = SaiTheme.ACCENT,
                radiusDp = 20f,
                density = resources.displayMetrics.density,
            )
            elevation = dp(2).toFloat()
            setPadding(displayDp(18), displayDp(16), displayDp(18), displayDp(16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        cardText(title, 18f, Color.WHITE).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText("›", 22f, Color.WHITE).apply {
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(
                            displayDp(28),
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
            )
            addView(
                cardText(description, 13f, Color.rgb(237, 233, 254)).apply {
                    setPadding(0, displayDp(3), displayDp(28), 0)
                },
            )
            isClickable = true
            isFocusable = true
            contentDescription = "$title，$description，可點擊"
            setOnClickListener { action() }
        }

    private fun buildActionCard(
        title: String,
        description: String,
        action: () -> Unit,
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(resources.displayMetrics.density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(16), displayDp(14), displayDp(16), displayDp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        cardText(title, 17f, SaiTheme.TEXT).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                        LinearLayout.LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ),
                    )
                    addView(
                        cardText("›", 22f, SaiTheme.BRAND).apply {
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(
                            displayDp(28),
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
            )
            addView(
                cardText(description, 13f, SaiTheme.MUTED).apply {
                    setPadding(0, displayDp(3), displayDp(28), 0)
                },
            )
            isClickable = true
            isFocusable = true
            contentDescription = "$title，$description，可點擊"
            setOnClickListener { action() }
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

    private fun installBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (activeInstrumentSymbol != null) {
                        activeInstrumentSymbol = null
                        renderMainTab(instrumentReturnTab)
                        refreshDashboard()
                        return
                    }
                    if (selectedMainTab != MainTab.HOME) {
                        renderMainTab(MainTab.HOME)
                        refreshDashboard()
                        return
                    }

                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            },
        )
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
        fugleStreamingController.pause()
        uiScope.launch(Dispatchers.IO) {
            marketPersistenceController.flushNow()
        }
        super.onPause()
    }

    override fun onDestroy() {
        marketPollingActive = false
        pollGeneration++
        marketWallRender = null
        marketWallDialog?.dismiss()
        marketWallDialog = null
        holdingDetailDialog?.dismiss()
        holdingDetailDialog = null
        performanceDialog?.dismiss()
        performanceDialog = null
        performanceDialogContent = null
        ledgerExecutor.shutdown()
        marketScheduler.shutdownNow()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun bindMarketStateFlow() {
        uiScope.launch {
            marketDataCenter.quotesState.collectLatest { state ->
                if (!marketPollingActive || state.isEmpty()) return@collectLatest
                val snapshot = latestLedgerSnapshot ?: return@collectLatest
                val symbols = snapshot.holdings.map { it.symbol }.toSet()
                if (symbols.isEmpty()) return@collectLatest

                val now = System.currentTimeMillis()
                val taipeiNow = Instant.ofEpochMilli(now).atZone(taipeiZone)
                val currentTaipeiDate = taipeiNow.toLocalDate().toString()
                val tradingSession =
                    isTaipeiTradingSession(taipeiNow.dayOfWeek, taipeiNow.toLocalTime())

                val requested = state.filterKeys { it in symbols }
                if (requested.isEmpty()) return@collectLatest
                val stale = requested.filterValues { quote ->
                    quote.quality == QuoteQuality.STALE ||
                        taipeiDateOf(quote.sourceTimestampEpochMillis) != currentTaipeiDate
                }
                val usable = requested.filterKeys { it !in stale.keys }
                if (usable.isEmpty()) return@collectLatest

                val batch = MarketBatch(
                    quotes = usable,
                    staleQuotes = stale,
                    unresolvedSymbols = symbols - usable.keys,
                    sourcesTried = emptyList(),
                    refreshedAtEpochMillis = now,
                    providerHealth = marketDataCenter.providerHealthSnapshot(now),
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
                    batch.quotes.size == symbols.size &&
                    batch.quotes.values.all { quote ->
                        quote.quality == QuoteQuality.LIVE &&
                            taipeiDateOf(quote.sourceTimestampEpochMillis) == currentTaipeiDate
                    }

                applyMarketValuation(
                    batch = batch,
                    valuation = valuation,
                    tradingSession = tradingSession,
                    freshCurrentSession = freshCurrentSession,
                )
                refreshVisibleMarketSections()
            }
        }
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
        refreshVisibleMarketSections()
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
        fugleStreamingController.updateSymbols(symbols)
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
                    MarketSource.FUGLE -> "Fugle"
                    MarketSource.TWSE_MIS -> "TWSE MIS"
                    MarketSource.YAHOO -> "Yahoo"
                    MarketSource.CACHE -> "Cache"
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
        val records: List<String>,
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
                content.addView(cardText("每日損益紀錄", 16f, Color.rgb(15, 23, 42)))
                if (render.records.isEmpty()) {
                    content.addView(
                        cardText(
                            "目前沒有可顯示的每日紀錄",
                            13f,
                            SaiTheme.MUTED,
                        ),
                    )
                } else {
                    render.records.forEach { row ->
                        content.addView(
                            cardText(row, 13f, Color.rgb(51, 65, 85)).apply {
                                setPadding(0, dp(6), 0, dp(6))
                            },
                        )
                    }
                }
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
                records = daySnapshot?.let {
                    listOf(
                        "${it.taipeiDate}｜今日 ${formatSignedTwd(it.dailyMarketPnL)}" +
                            "｜總損益 ${formatSignedTwd(it.totalUnrealizedProfit)}" +
                            "｜市值 ${formatTwd(it.totalMarketValue)}",
                    )
                }.orEmpty(),
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
            records = rows
                .takeLast(12)
                .asReversed()
                .map {
                    "${it.taipeiDate}｜今日 ${formatSignedTwd(it.dailyMarketPnL)}" +
                        "｜總損益 ${formatSignedTwd(it.totalUnrealizedProfit)}" +
                        "｜市值 ${formatTwd(it.totalMarketValue)}"
                },
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
                    val monthConfirmedRows = monthRows
                        .filter { it.status == DividendRepository.Status.CONFIRMED }
                    val monthAnnouncedRows = monthRows
                        .filter { it.status == DividendRepository.Status.ANNOUNCED }
                    val monthConfirmedCash = monthConfirmedRows.sumOf { it.estimatedCash }
                    content.addView(
                        TextView(this@MainActivity).apply {
                            text = buildString {
                                append("本月 ${monthRows.size} 筆｜預估 ${formatTwd(monthTotal)}")
                                append("\n已確認 ${monthConfirmedRows.size} 筆 / ${formatTwd(monthConfirmedCash)}")
                                append("｜預告 ${monthAnnouncedRows.size} 筆")
                            }
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

        val dialog = AlertDialog.Builder(this)
            .setTitle("股息新增 / 更新")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("儲存", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val symbolValue = symbol.text.toString().trim()
                val cashValue = cashPerShare.text.toString().trim().toDoubleOrNull()
                val exDateText = exDate.text.toString().trim()
                val recordDateText = recordDate.text.toString().trim()
                val paymentDateText = paymentDate.text.toString().trim()
                val exDateValue = runCatching { LocalDate.parse(exDateText) }.getOrNull()
                val recordDateValue = recordDateText
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val paymentDateValue = paymentDateText
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

                val validationError = when {
                    symbolValue.isEmpty() -> "請輸入 ETF / 股票代號"
                    cashValue == null || !cashValue.isFinite() || cashValue <= 0.0 ->
                        "每股現金股利必須大於 0"
                    exDateValue == null -> "除息日格式必須為 YYYY-MM-DD"
                    recordDateText.isNotEmpty() && recordDateValue == null ->
                        "股權登記日格式必須為 YYYY-MM-DD"
                    paymentDateText.isNotEmpty() && paymentDateValue == null ->
                        "發放日格式必須為 YYYY-MM-DD"
                    recordDateValue != null && exDateValue != null &&
                        recordDateValue.isBefore(exDateValue) ->
                        "股權登記日不可早於除息日"
                    paymentDateValue != null && exDateValue != null &&
                        paymentDateValue.isBefore(exDateValue) ->
                        "發放日不可早於除息日"
                    recordDateValue != null && paymentDateValue != null &&
                        paymentDateValue.isBefore(recordDateValue) ->
                        "發放日不可早於股權登記日"
                    else -> null
                }
                if (validationError != null) {
                    Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val validCash = cashValue ?: return@setOnClickListener
                val validExDate = exDateValue ?: return@setOnClickListener
                val command = DividendRepository.UpsertCommand(
                    symbol = symbolValue,
                    exDateTaipei = validExDate.toString(),
                    recordDateTaipei = recordDateValue?.toString(),
                    paymentDateTaipei = paymentDateValue?.toString(),
                    cashPerShare = validCash,
                    status = if (statusSpinner.selectedItemPosition == 0) {
                        DividendRepository.Status.ANNOUNCED
                    } else {
                        DividendRepository.Status.CONFIRMED
                    },
                )

                saveButton.isEnabled = false
                ledgerExecutor.execute {
                    runCatching { dividendRepository.upsert(command) }
                        .onSuccess {
                            runOnUiThread {
                                dialog.dismiss()
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
                                saveButton.isEnabled = true
                                Toast.makeText(
                                    this,
                                    "股息未儲存：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
        }
        dialog.show()
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

            val pageBuyCount = page.rows.count { it.side == LedgerEntryKind.BUY }
            val pageSellCount = page.rows.count { it.side == LedgerEntryKind.SELL }
            val pageGrossAmount = page.rows.sumOf { row ->
                (row.shares.toDouble() * row.price).toLong()
            }

            runOnUiThread {
                val pageSizes = listOf(10, 20, 50)
                val sizeSpinner = Spinner(this).apply {
                    adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        pageSizes,
                    )
                    setSelection(pageSizes.indexOf(page.pageSize))
                }
                var historyDialog: AlertDialog? = null
                val content = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(6), dp(18), 0)
                    addView(
                        TextView(this@MainActivity).apply {
                            text = buildString {
                                append("第 ${page.pageIndex + 1} / ${page.totalPages} 頁｜共 ${page.totalCount} 筆")
                                append("\n本頁 買進 $pageBuyCount｜賣出 $pageSellCount")
                                append("｜成交額 ${formatTwd(pageGrossAmount)}")
                                append("\n修改 / 刪除採 Ledger 修正紀錄，不直接覆寫原始帳務")
                            }
                            textSize = 14f
                            setTextColor(Color.rgb(71, 85, 105))
                        },
                    )
                    addView(sizeSpinner)
                }

                if (page.rows.isEmpty()) {
                    content.addView(
                        TextView(this@MainActivity).apply {
                            text = "目前沒有交易紀錄。"
                            textSize = 14f
                            setTextColor(Color.rgb(100, 116, 139))
                            setPadding(0, dp(12), 0, dp(12))
                        },
                    )
                } else {
                    page.rows.forEach { row ->
                        val side = if (row.side == LedgerEntryKind.BUY) "買進" else "賣出"
                        val mode = if (row.tradeMode == TradeMode.ROUND_LOT) "整股" else "零股"
                        val card = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setBackgroundColor(Color.WHITE)
                            setPadding(dp(12), dp(10), dp(12), dp(10))
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ).apply {
                                topMargin = dp(8)
                            }
                        }
                        card.addView(
                            cardText(
                                buildString {
                                    append("${row.tradeDateTaipei}｜$side｜${row.symbol}")
                                    if (row.isEdited) append("｜已修改")
                                },
                                15f,
                                Color.rgb(15, 23, 42),
                            ),
                        )
                        card.addView(
                            cardText(
                                "${row.shares} 股 × ${"%.2f".format(Locale.US, row.price)}｜$mode",
                                14f,
                                Color.rgb(51, 65, 85),
                            ),
                        )
                        card.addView(
                            cardText(
                                buildString {
                                    append("手續費 ${row.fee?.let(::formatTwd) ?: "—"}")
                                    if (row.side == LedgerEntryKind.SELL) {
                                        append("｜證交稅 ${row.tax?.let(::formatTwd) ?: "—"}")
                                    }
                                    row.note?.takeIf { it.isNotBlank() }?.let {
                                        append("\n備註 $it")
                                    }
                                },
                                13f,
                                Color.rgb(100, 116, 139),
                            ),
                        )
                        card.addView(
                            LinearLayout(this).apply {
                                orientation = LinearLayout.HORIZONTAL
                                addView(
                                    Button(this@MainActivity).apply {
                                        text = "修改"
                                        setOnClickListener {
                                            historyDialog?.dismiss()
                                            showEditTransactionDialog(
                                                row = row,
                                                returnPageIndex = page.pageIndex,
                                                returnPageSize = page.pageSize,
                                            )
                                        }
                                    },
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    ),
                                )
                                addView(
                                    Button(this@MainActivity).apply {
                                        text = "刪除"
                                        setOnClickListener {
                                            historyDialog?.dismiss()
                                            showDeleteTransactionConfirmation(
                                                row = row,
                                                returnPageIndex = page.pageIndex,
                                                returnPageSize = page.pageSize,
                                            )
                                        }
                                    },
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    ).apply {
                                        marginStart = dp(8)
                                    },
                                )
                            },
                        )
                        content.addView(card)
                    }
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
                historyDialog = dialog

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
                                val selected = pageSizes[position]
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

    private fun showEditTransactionDialog(
        row: LedgerRepository.TransactionRow,
        returnPageIndex: Int,
        returnPageSize: Int,
    ) {
        val sideSpinner = optionSpinner(
            listOf("買進", "賣出"),
            if (row.side == LedgerEntryKind.BUY) 0 else 1,
        )
        val modeSpinner = optionSpinner(
            listOf("整股", "零股"),
            if (row.tradeMode == TradeMode.ROUND_LOT) 0 else 1,
        )
        val symbol = input("代號，例如 0050").apply { setText(row.symbol) }
        val shares = input("股數").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(row.shares.toString())
        }
        val price = input("成交價").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(Locale.US, row.price))
        }
        val fee = input("實際手續費（可留空）").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(row.fee?.toString().orEmpty())
        }
        val tax = input("實際證交稅（賣出可留空）").apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(row.tax?.toString().orEmpty())
        }
        val tradeDate = dateInput("交易日期", row.tradeDateTaipei)
        val note = input("備註（可留空）").apply {
            setText(row.note.orEmpty())
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(
                cardText(
                    "修改會新增一筆修正紀錄，原始 Ledger 保留不變。",
                    13f,
                    Color.rgb(100, 116, 139),
                ),
            )
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
            addView(note)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("修改交易｜${row.symbol}")
            .setView(form)
            .setNegativeButton("取消") { _, _ ->
                showTransactionHistoryDialog(returnPageIndex, returnPageSize)
            }
            .setPositiveButton("儲存修改", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val symbolValue = symbol.text.toString().trim()
                val selectedSide = if (sideSpinner.selectedItemPosition == 0) {
                    LedgerEntryKind.BUY
                } else {
                    LedgerEntryKind.SELL
                }
                val sharesValue = shares.text.toString().trim().toLongOrNull()
                val priceValue = price.text.toString().trim().toDoubleOrNull()
                val feeText = fee.text.toString().trim()
                val taxText = tax.text.toString().trim()
                val feeValue = feeText.takeIf { it.isNotEmpty() }?.toLongOrNull()
                val taxValue = taxText.takeIf { it.isNotEmpty() }?.toLongOrNull()
                val tradeDateText = tradeDate.text.toString().trim()
                val tradeDateValue = runCatching { LocalDate.parse(tradeDateText) }.getOrNull()

                val validationError = when {
                    symbolValue.isEmpty() -> "請輸入 ETF / 股票代號"
                    sharesValue == null || sharesValue <= 0L -> "股數必須為大於 0 的整數"
                    priceValue == null || !priceValue.isFinite() || priceValue <= 0.0 ->
                        "成交價必須大於 0"
                    feeText.isNotEmpty() && feeValue == null -> "手續費必須為整數"
                    feeValue != null && feeValue < 0L -> "手續費不可小於 0"
                    taxText.isNotEmpty() && taxValue == null -> "證交稅必須為整數"
                    taxValue != null && taxValue < 0L -> "證交稅不可小於 0"
                    tradeDateValue == null -> "交易日期格式必須為 YYYY-MM-DD"
                    tradeDateValue != null && tradeDateValue.isAfter(LocalDate.now(taipeiZone)) ->
                        "交易日期不可晚於今天"
                    else -> null
                }
                if (validationError != null) {
                    Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val command = LedgerRepository.AddTradeCommand(
                    side = selectedSide,
                    symbol = symbolValue,
                    shares = sharesValue ?: return@setOnClickListener,
                    price = priceValue ?: return@setOnClickListener,
                    tradeMode = if (modeSpinner.selectedItemPosition == 0) {
                        TradeMode.ROUND_LOT
                    } else {
                        TradeMode.ODD_LOT
                    },
                    tradeDateTaipei = tradeDateValue?.toString()
                        ?: return@setOnClickListener,
                    actualFee = feeValue,
                    actualTax = taxValue,
                    note = note.text.toString().trim().takeIf { it.isNotEmpty() },
                )

                saveButton.isEnabled = false
                ledgerExecutor.execute {
                    runCatching { repository.correctTrade(row.id, command) }
                        .onSuccess { snapshot ->
                            latestLedgerSnapshot = snapshot
                            latestMarketBatch = null
                            runOnUiThread {
                                dialog.dismiss()
                                applyLedgerSnapshot(snapshot)
                                Toast.makeText(
                                    this,
                                    "交易已修改；原始 Ledger 已保留修正軌跡",
                                    Toast.LENGTH_LONG,
                                ).show()
                                showTransactionHistoryDialog(returnPageIndex, returnPageSize)
                            }
                            if (marketPollingActive) {
                                scheduleMarketRefresh(0L, pollGeneration)
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                saveButton.isEnabled = true
                                Toast.makeText(
                                    this,
                                    "交易修改失敗：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
        }
        dialog.show()
        styleDialog(dialog, accent = true)
    }

    private fun showDeleteTransactionConfirmation(
        row: LedgerRepository.TransactionRow,
        returnPageIndex: Int,
        returnPageSize: Int,
    ) {
        val side = if (row.side == LedgerEntryKind.BUY) "買進" else "賣出"
        AlertDialog.Builder(this)
            .setTitle("刪除交易")
            .setMessage(
                "${row.tradeDateTaipei}｜$side｜${row.symbol}\n" +
                    "${row.shares} 股 × ${"%.2f".format(Locale.US, row.price)}\n\n" +
                    "刪除後畫面與資產計算會排除此筆交易；" +
                    "底層仍以修正標記保留原始 Ledger 稽核軌跡。",
            )
            .setNegativeButton("取消") { _, _ ->
                showTransactionHistoryDialog(returnPageIndex, returnPageSize)
            }
            .setPositiveButton("確認刪除") { _, _ ->
                ledgerExecutor.execute {
                    runCatching { repository.deleteTrade(row.id) }
                        .onSuccess { snapshot ->
                            latestLedgerSnapshot = snapshot
                            latestMarketBatch = null
                            runOnUiThread {
                                applyLedgerSnapshot(snapshot)
                                Toast.makeText(
                                    this,
                                    "交易已刪除；原始 Ledger 稽核軌跡已保留",
                                    Toast.LENGTH_LONG,
                                ).show()
                                showTransactionHistoryDialog(returnPageIndex, returnPageSize)
                            }
                            if (marketPollingActive) {
                                scheduleMarketRefresh(0L, pollGeneration)
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "交易刪除失敗：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                                showTransactionHistoryDialog(returnPageIndex, returnPageSize)
                            }
                        }
                }
            }
            .show()
    }

    private fun showTradeDialog() {
        val sideSpinner = optionSpinner(listOf("買進", "賣出"))
        val modeSpinner = optionSpinner(listOf("整股", "零股"))
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
        val tradeDate = dateInput("交易日期", LocalDate.now(taipeiZone).toString())

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

        val dialog = AlertDialog.Builder(this)
            .setTitle("新增交易")
            .setView(form)
            .setNegativeButton("取消", null)
            .setPositiveButton("寫入 Ledger", null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val symbolValue = symbol.text.toString().trim()
                val selectedSide = if (sideSpinner.selectedItemPosition == 0) {
                    LedgerEntryKind.BUY
                } else {
                    LedgerEntryKind.SELL
                }
                val availableShares = latestLedgerSnapshot
                    ?.holdings
                    ?.firstOrNull { it.symbol.equals(symbolValue, ignoreCase = true) }
                    ?.shares
                    ?: 0L
                val sharesValue = shares.text.toString().trim().toLongOrNull()
                val priceValue = price.text.toString().trim().toDoubleOrNull()
                val feeText = fee.text.toString().trim()
                val taxText = tax.text.toString().trim()
                val feeValue = feeText.takeIf { it.isNotEmpty() }?.toLongOrNull()
                val taxValue = taxText.takeIf { it.isNotEmpty() }?.toLongOrNull()
                val tradeDateText = tradeDate.text.toString().trim()
                val tradeDateValue = runCatching { LocalDate.parse(tradeDateText) }.getOrNull()

                val validationError = when {
                    symbolValue.isEmpty() -> "請輸入 ETF / 股票代號"
                    sharesValue == null || sharesValue <= 0L -> "股數必須為大於 0 的整數"
                    selectedSide == LedgerEntryKind.SELL && sharesValue > availableShares ->
                        "賣出股數 $sharesValue 超過目前持有 $availableShares 股"
                    priceValue == null || !priceValue.isFinite() || priceValue <= 0.0 ->
                        "成交價必須大於 0"
                    feeText.isNotEmpty() && feeValue == null -> "手續費必須為整數"
                    feeValue != null && feeValue < 0L -> "手續費不可小於 0"
                    taxText.isNotEmpty() && taxValue == null -> "證交稅必須為整數"
                    taxValue != null && taxValue < 0L -> "證交稅不可小於 0"
                    tradeDateValue == null -> "交易日期格式必須為 YYYY-MM-DD"
                    tradeDateValue != null && tradeDateValue.isAfter(LocalDate.now(taipeiZone)) ->
                        "交易日期不可晚於今天"
                    else -> null
                }
                if (validationError != null) {
                    Toast.makeText(this, validationError, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                val validShares = sharesValue ?: return@setOnClickListener
                val validPrice = priceValue ?: return@setOnClickListener
                val validTradeDate = tradeDateValue ?: return@setOnClickListener
                val command = LedgerRepository.AddTradeCommand(
                    side = selectedSide,
                    symbol = symbolValue,
                    shares = validShares,
                    price = validPrice,
                    tradeMode = if (modeSpinner.selectedItemPosition == 0) {
                        TradeMode.ROUND_LOT
                    } else {
                        TradeMode.ODD_LOT
                    },
                    tradeDateTaipei = validTradeDate.toString(),
                    actualFee = feeValue,
                    actualTax = taxValue,
                )

                saveButton.isEnabled = false
                ledgerExecutor.execute {
                    runCatching { repository.addTrade(command) }
                        .onSuccess { snapshot ->
                            latestLedgerSnapshot = snapshot
                            latestMarketBatch = null
                            runOnUiThread {
                                dialog.dismiss()
                                applyLedgerSnapshot(snapshot)
                                Toast.makeText(this, "交易已寫入不可變 Ledger", Toast.LENGTH_SHORT).show()
                            }
                            if (marketPollingActive) {
                                scheduleMarketRefresh(0L, pollGeneration)
                            }
                        }
                        .onFailure { error ->
                            runOnUiThread {
                                saveButton.isEnabled = true
                                Toast.makeText(
                                    this,
                                    "交易未寫入：${error.message ?: "未知錯誤"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                }
            }
        }
        dialog.show()
        styleDialog(dialog, accent = true)
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
                showInstrumentPage(spinner.selectedItem.toString())
            }
            .show()
    }

    private fun showInstrumentPage(symbol: String) {
        val holding = latestLedgerSnapshot
            ?.holdings
            ?.firstOrNull { it.symbol == symbol }
        if (holding == null) {
            Toast.makeText(this, "找不到 $symbol 的持股資料", Toast.LENGTH_SHORT).show()
            return
        }

        instrumentReturnTab = selectedMainTab
        activeInstrumentSymbol = symbol
        instrumentSelectedTab = InstrumentInfoTab.DETAIL
        instrumentProfilePage = null
        instrumentDailyBarsPage = emptyList()
        instrumentInstitutionalPage = null
        instrumentRevenuePage = null
        instrumentProfileLoading = false
        instrumentProfileLoaded = false
        instrumentHistoryLoading = false
        instrumentHistoryLoaded = false
        instrumentInstitutionalLoading = false
        instrumentInstitutionalLoaded = false
        instrumentRevenueLoading = false
        instrumentRevenueLoaded = false
        instrumentTabButtons.clear()

        homeHoldingsContainer = null
        marketQuotesContainer = null
        pageContent.removeAllViews()

        val market = latestValuation
            ?.holdings
            ?.firstOrNull { it.symbol == symbol }
        val quote = market?.quote
        val averageCost = holding.investmentCost
            .takeIf { holding.shares > 0L }
            ?.div(holding.shares.toDouble())

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                Button(this@MainActivity).apply {
                    text = "‹"
                    textSize = 22f
                    isAllCaps = false
                    stateListAnimator = null
                    setTextColor(SaiTheme.BRAND)
                    background = SaiTheme.rounded(
                        fill = SaiTheme.BRAND_SOFT,
                        radiusDp = 16f,
                        density = resources.displayMetrics.density,
                        strokeColor = SaiTheme.BORDER,
                        strokeDp = 1,
                    )
                    setOnClickListener {
                        activeInstrumentSymbol = null
                        renderMainTab(instrumentReturnTab)
                        refreshDashboard()
                    }
                },
                LinearLayout.LayoutParams(dp(52), dp(48)),
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(cardText("${quote?.name ?: symbol}  $symbol", 22f, SaiTheme.TEXT))
                    addView(
                        cardText(
                            quote?.let {
                                val previous = it.previousClose
                                val pct = previous
                                    ?.takeIf { p -> p > 0.0 }
                                    ?.let { p -> (it.price - p) / p * 100.0 }
                                buildString {
                                    append("現價 ${String.format(Locale.US, "%.2f", it.price)}")
                                    pct?.let { value ->
                                        append("  ")
                                        append(String.format(Locale.US, "%+.2f%%", value))
                                    }
                                    append("｜${sourceName(it.source)}｜${it.quality.name}")
                                }
                            } ?: "行情待更新",
                            13f,
                            Color.rgb(100, 116, 139),
                        ),
                    )
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        pageContent.addView(titleRow)

        val metricRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(10))
            addView(
                buildInstrumentMetricCard(
                    "均價",
                    averageCost?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                buildInstrumentMetricCard(
                    "市值",
                    market?.marketValue?.let(::formatTwd) ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        }
        pageContent.addView(metricRow)

        val navigationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val symbols = latestLedgerSnapshot?.holdings?.map { it.symbol }?.sorted().orEmpty()
            val index = symbols.indexOf(symbol)
            addView(
                Button(this@MainActivity).apply {
                    text = "上一檔"
                    isAllCaps = false
                    stateListAnimator = null
                    isEnabled = index > 0
                    setTextColor(if (isEnabled) SaiTheme.TEXT_SECONDARY else SaiTheme.MUTED)
                    background = SaiTheme.rounded(
                        fill = SaiTheme.CARD_SOFT,
                        radiusDp = 14f,
                        density = resources.displayMetrics.density,
                        strokeColor = SaiTheme.BORDER,
                        strokeDp = 1,
                    )
                    setOnClickListener {
                        if (index > 0) showInstrumentPage(symbols[index - 1])
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
            addView(
                Button(this@MainActivity).apply {
                    text = "下一檔"
                    isAllCaps = false
                    stateListAnimator = null
                    isEnabled = index >= 0 && index + 1 < symbols.size
                    setTextColor(if (isEnabled) SaiTheme.TEXT_SECONDARY else SaiTheme.MUTED)
                    background = SaiTheme.rounded(
                        fill = SaiTheme.CARD_SOFT,
                        radiusDp = 14f,
                        density = resources.displayMetrics.density,
                        strokeColor = SaiTheme.BORDER,
                        strokeDp = 1,
                    )
                    setOnClickListener {
                        if (index >= 0 && index + 1 < symbols.size) {
                            showInstrumentPage(symbols[index + 1])
                        }
                    }
                },
                LinearLayout.LayoutParams(0, dp(44), 1f),
            )
        }
        pageContent.addView(navigationRow)

        InstrumentInfoTab.entries.chunked(4).forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                group.forEach { tab ->
                    val tabButton = Button(this@MainActivity).apply {
                        text = tab.label
                        isAllCaps = false
                        textSize = 12f * displayScale
                        stateListAnimator = null
                        contentDescription = "個股資訊：${tab.label}"
                        setOnClickListener {
                            selectInstrumentPageTab(tab)
                        }
                    }
                    instrumentTabButtons[tab] = tabButton
                    addView(
                        tabButton,
                        LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                            marginStart = dp(1)
                            marginEnd = dp(1)
                        },
                    )
                }
            }
            pageContent.addView(row)
        }

        instrumentChartHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(4))
        }
        pageContent.addView(instrumentChartHost)

        instrumentTabContentView = TextView(this).apply {
            textSize = 14f * displayScale
            setTextColor(SaiTheme.TEXT_SECONDARY)
            background = SaiTheme.card(resources.displayMetrics.density)
            setPadding(dp(16), dp(14), dp(16), dp(22))
            minHeight = dp(132)
        }
        pageContent.addView(instrumentTabContentView)

        selectInstrumentPageTab(InstrumentInfoTab.DETAIL)
    }

    private fun selectInstrumentPageTab(tab: InstrumentInfoTab) {
        instrumentSelectedTab = tab
        updateInstrumentTabSelection()
        ensureInstrumentPageData(tab)
        renderInstrumentPageTab()
    }

    private fun updateInstrumentTabSelection() {
        instrumentTabButtons.forEach { (tab, button) ->
            val active = tab == instrumentSelectedTab
            button.isSelected = active
            button.setTextColor(
                if (active) SaiTheme.BRAND else SaiTheme.TEXT_SECONDARY,
            )
            button.background = SaiTheme.rounded(
                fill = if (active) SaiTheme.BRAND_SOFT else SaiTheme.CARD,
                radiusDp = 14f,
                density = resources.displayMetrics.density,
                strokeColor = if (active) SaiTheme.BRAND else SaiTheme.BORDER,
                strokeDp = 1,
            )
        }
    }

    private fun ensureInstrumentPageData(tab: InstrumentInfoTab) {
        val symbol = activeInstrumentSymbol ?: return
        when (tab) {
            InstrumentInfoTab.DETAIL,
            InstrumentInfoTab.COMPONENTS,
            InstrumentInfoTab.DATA,
            -> loadInstrumentProfileForPage(symbol)

            InstrumentInfoTab.TREND,
            InstrumentInfoTab.TECHNICAL,
            -> loadInstrumentHistoryForPage(symbol)

            InstrumentInfoTab.INSTITUTIONAL -> loadInstrumentInstitutionalForPage(symbol)

            InstrumentInfoTab.FINANCIAL -> {
                loadInstrumentProfileForPage(symbol)
                loadInstrumentHistoryForPage(symbol)
                loadInstrumentRevenueForPage(symbol)
            }

            InstrumentInfoTab.AFTER_HOURS -> Unit
        }
    }

    private fun loadInstrumentProfileForPage(symbol: String) {
        if (instrumentProfileLoaded || instrumentProfileLoading) return
        instrumentProfileLoading = true
        ledgerExecutor.execute {
            val result = runCatching { taiwanInstrumentInfoProvider.fetchProfile(symbol) }.getOrNull()
            runOnUiThread {
                if (activeInstrumentSymbol != symbol) return@runOnUiThread
                instrumentProfilePage = result
                instrumentProfileLoading = false
                instrumentProfileLoaded = true
                renderInstrumentPageTab()
            }
        }
    }

    private fun loadInstrumentHistoryForPage(symbol: String) {
        if (instrumentHistoryLoaded || instrumentHistoryLoading) return
        instrumentHistoryLoading = true
        ledgerExecutor.execute {
            val result = runCatching { taiwanDailyHistoryProvider.fetch(symbol) }.getOrDefault(emptyList())
            runOnUiThread {
                if (activeInstrumentSymbol != symbol) return@runOnUiThread
                instrumentDailyBarsPage = result
                instrumentHistoryLoading = false
                instrumentHistoryLoaded = true
                renderInstrumentPageTab()
            }
        }
    }

    private fun loadInstrumentInstitutionalForPage(symbol: String) {
        if (instrumentInstitutionalLoaded || instrumentInstitutionalLoading) return
        instrumentInstitutionalLoading = true
        ledgerExecutor.execute {
            val result = runCatching { taiwanInstitutionalProvider.fetchLatest(symbol) }.getOrNull()
            runOnUiThread {
                if (activeInstrumentSymbol != symbol) return@runOnUiThread
                instrumentInstitutionalPage = result
                instrumentInstitutionalLoading = false
                instrumentInstitutionalLoaded = true
                renderInstrumentPageTab()
            }
        }
    }

    private fun loadInstrumentRevenueForPage(symbol: String) {
        if (instrumentRevenueLoaded || instrumentRevenueLoading) return
        instrumentRevenueLoading = true
        ledgerExecutor.execute {
            val result = runCatching { taiwanRevenueProvider.fetch(symbol) }.getOrNull()
            runOnUiThread {
                if (activeInstrumentSymbol != symbol) return@runOnUiThread
                instrumentRevenuePage = result
                instrumentRevenueLoading = false
                instrumentRevenueLoaded = true
                renderInstrumentPageTab()
            }
        }
    }

    private fun renderInstrumentPageTab() {
        val symbol = activeInstrumentSymbol ?: return
        val content = instrumentTabContentView ?: return
        val chartHost = instrumentChartHost ?: return
        val holding = latestLedgerSnapshot?.holdings?.firstOrNull { it.symbol == symbol } ?: return
        val market = latestValuation?.holdings?.firstOrNull { it.symbol == symbol }
        val quote = market?.quote

        chartHost.removeAllViews()
        chartHost.visibility = View.GONE

        content.text = when (instrumentSelectedTab) {
            InstrumentInfoTab.DETAIL -> buildString {
                append("持有 ${holding.shares} 股｜投入成本 ${formatTwd(holding.investmentCost)}")
                append("\n今日損益 ${market?.todayPnl?.let(::formatSignedTwd) ?: "—"}")
                append("｜持有總損益 ${market?.totalPnl?.let(::formatSignedTwd) ?: "—"}")
                quote?.let {
                    append("\n\n開 ${it.open?.let { value -> String.format(Locale.US, "%.2f", value) } ?: "—"}")
                    append("｜高 ${it.high?.let { value -> String.format(Locale.US, "%.2f", value) } ?: "—"}")
                    append("｜低 ${it.low?.let { value -> String.format(Locale.US, "%.2f", value) } ?: "—"}")
                    append("\n成交量 ${it.volume ?: 0L}｜Bid ${it.bid ?: "—"}｜Ask ${it.ask ?: "—"}")
                }
                instrumentProfilePage?.let { profile ->
                    append("\n\n${profile.market}｜${profile.industry ?: "產業待資料源"}")
                    append("\n${profile.companyName}（${profile.shortName}）")
                    profile.listingDate?.let { append("｜掛牌 $it") }
                } ?: if (instrumentProfileLoading) {
                    append("\n\n公司基本資料載入中…")
                } else if (instrumentProfileLoaded) {
                    append("\n\n公司基本資料來源未回傳此代號。")
                } else {
                    Unit
                }
            }

            InstrumentInfoTab.TREND -> {
                if (instrumentDailyBarsPage.isNotEmpty()) {
                    chartHost.visibility = View.VISIBLE
                    chartHost.addView(
                        TaiwanKLineView(this).apply { setBars(instrumentDailyBarsPage) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(300),
                        ),
                    )
                    val latest = instrumentDailyBarsPage.last()
                    "日 K｜近 1 年｜${instrumentDailyBarsPage.size} 根\n" +
                        "O ${String.format(Locale.US, "%.2f", latest.open)}  " +
                        "H ${String.format(Locale.US, "%.2f", latest.high)}  " +
                        "L ${String.format(Locale.US, "%.2f", latest.low)}  " +
                        "C ${String.format(Locale.US, "%.2f", latest.close)}｜量 ${latest.volume}"
                } else if (instrumentHistoryLoading) {
                    "走勢資料載入中…"
                } else {
                    "目前沒有可核實的歷史 K 線；不產生模擬走勢。"
                }
            }

            InstrumentInfoTab.TECHNICAL -> {
                if (instrumentDailyBarsPage.isNotEmpty()) {
                    chartHost.visibility = View.VISIBLE
                    chartHost.addView(
                        TaiwanKLineView(this).apply { setBars(instrumentDailyBarsPage) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(300),
                        ),
                    )
                    val sma20 = TaiwanTechnicalEngine.sma(instrumentDailyBarsPage, 20).lastOrNull()?.value
                    val ema12 = TaiwanTechnicalEngine.ema(instrumentDailyBarsPage, 12).lastOrNull()?.value
                    val rsi14 = TaiwanTechnicalEngine.rsi(instrumentDailyBarsPage, 14).lastOrNull()?.value
                    val macd = TaiwanTechnicalEngine.macd(instrumentDailyBarsPage)
                    buildString {
                        append("技術指標｜以真實日 K 本機計算")
                        append("\nSMA20 ${sma20?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}")
                        append("｜EMA12 ${ema12?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}")
                        append("\nRSI14 ${rsi14?.let { String.format(Locale.US, "%.1f", it) } ?: "—"}")
                        append("｜MACD ${macd.macd.lastOrNull()?.value?.let { String.format(Locale.US, "%.3f", it) } ?: "—"}")
                        append("｜Signal ${macd.signal.lastOrNull()?.value?.let { String.format(Locale.US, "%.3f", it) } ?: "—"}")
                    }
                } else if (instrumentHistoryLoading) {
                    "技術指標所需 K 線載入中…"
                } else {
                    "沒有可核實 K 線，因此不計算技術指標。"
                }
            }

            InstrumentInfoTab.COMPONENTS -> {
                instrumentProfilePage?.let { profile ->
                    buildString {
                        append("標的結構｜${profile.market}")
                        append("\n產業 ${profile.industry ?: "—"}")
                        append("｜已發行普通股 ${profile.issuedCommonShares?.let { NumberFormat.getIntegerInstance(Locale.TAIWAN).format(it) } ?: "—"}")
                        append("\nETF 成分 / 權重只在專屬資料源可核實時顯示；目前不以公司股本冒充 ETF 成分。")
                    }
                } ?: if (instrumentProfileLoading) {
                    "成分 / 結構資料載入中…"
                } else {
                    "目前尚無可核實的 ETF 成分資料。"
                }
            }

            InstrumentInfoTab.INSTITUTIONAL -> {
                instrumentInstitutionalPage?.let { flow ->
                    buildString {
                        append("三大法人｜${flow.taipeiDate}")
                        append("\n外資 ${flow.foreignNetShares?.let(::formatSignedShares) ?: "—"}")
                        append("｜投信 ${flow.investmentTrustNetShares?.let(::formatSignedShares) ?: "—"}")
                        append("｜自營商 ${flow.dealerNetShares?.let(::formatSignedShares) ?: "—"}")
                        append("\n來源 ${flow.source}")
                    }
                } ?: if (instrumentInstitutionalLoading) {
                    "法人資料載入中…"
                } else {
                    "最近交易日未取得可核實三大法人資料。"
                }
            }

            InstrumentInfoTab.FINANCIAL -> {
                val profile = instrumentProfilePage
                val revenue = instrumentRevenuePage
                if (profile == null && (instrumentProfileLoading || instrumentRevenueLoading)) {
                    "財務資料載入中…"
                } else {
                    buildString {
                        profile?.let {
                            append("${it.companyName}（${it.shortName}）")
                            append("\n實收資本額 ${it.paidInCapitalTwd?.let(::formatTwd) ?: "—"}")
                            append("｜已發行普通股 ${it.issuedCommonShares?.let { value -> NumberFormat.getIntegerInstance(Locale.TAIWAN).format(value) } ?: "—"}")
                            val metrics = TaiwanCapitalMetricCalculator.calculate(
                                profile = it,
                                currentPrice = quote?.price,
                                dailyBars = instrumentDailyBarsPage,
                            )
                            append("\n公司市值 ${metrics.marketCapitalizationTwd?.let(::formatTwd) ?: "—"}")
                            append("｜一年報酬 ${metrics.oneYearReturnPct?.let { value -> String.format(Locale.US, "%.2f%%", value) } ?: "—"}")
                        }
                        revenue?.let {
                            append("\n\n月營收 ${it.yearMonth ?: "最新"}")
                            append("\n當月 ${it.currentMonthRevenueTwd?.let(::formatTwd) ?: "—"}")
                            append("｜年增 ${it.yearOverYearPct?.let { value -> String.format(Locale.US, "%.2f%%", value) } ?: "—"}")
                            append("｜月增 ${it.monthOverMonthPct?.let { value -> String.format(Locale.US, "%.2f%%", value) } ?: "—"}")
                            append("\n來源 ${it.source}")
                        }
                        if (profile == null && revenue == null) {
                            append("目前公開資料源未回傳可核實財務資料。")
                        }
                    }
                }
            }

            InstrumentInfoTab.AFTER_HOURS -> buildString {
                append("盤後摘要")
                append("\n收盤 / 最新價 ${quote?.price?.let { String.format(Locale.US, "%.2f", it) } ?: "—"}")
                append("｜成交量 ${quote?.volume ?: "—"}")
                append("\n資料時間 ${quote?.sourceTimestampEpochMillis?.let { Instant.ofEpochMilli(it).atZone(taipeiZone) } ?: "—"}")
                append("\n盤後定價成交等欄位只有來源實際提供時才顯示，不用即時價代填。")
            }

            InstrumentInfoTab.DATA -> buildString {
                append("資料治理")
                append("\n行情來源 ${quote?.let { sourceName(it.source) } ?: "—"}")
                append("｜品質 ${quote?.quality?.name ?: "—"}")
                append("｜Fallback ${quote?.fallbackLevel ?: "—"}")
                append("\nSession ${quote?.sessionDate ?: "—"}")
                append("｜Sequence ${quote?.sequence ?: "—"}")
                append("\nSource timestamp ${quote?.sourceTimestampEpochMillis ?: "—"}")
                instrumentProfilePage?.let {
                    append("\n基本資料來源 ${it.source}")
                }
            }
        }
    }

    private fun showHoldingDetailDialog(symbol: String) {
        holdingDetailDialog?.dismiss()
        holdingDetailDialog = null

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
        val averageCost = if (holding.shares > 0L) {
            holding.investmentCost / holding.shares.toDouble()
        } else {
            null
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        content.addView(
            cardText(
                "${quote?.name ?: symbol}  $symbol",
                20f,
                Color.rgb(15, 23, 42),
            ),
        )
        content.addView(
            cardText(
                quote?.let {
                    buildString {
                        append("現價 ${"%.2f".format(Locale.US, it.price)}")
                        val previous = it.previousClose
                        if (previous != null && previous > 0.0) {
                            val change = it.price - previous
                            val pct = change / previous * 100.0
                            append("｜${if (change > 0) "+" else ""}${"%.2f".format(Locale.US, change)}")
                            append(" (${if (pct > 0) "+" else ""}${"%.2f".format(Locale.US, pct)}%)")
                        }
                    }
                } ?: "行情待更新",
                18f,
                Color.rgb(30, 41, 59),
            ),
        )

        val metricRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                buildInstrumentMetricCard(
                    "均價",
                    averageCost?.let { "%.2f".format(Locale.US, it) } ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                buildInstrumentMetricCard(
                    "市值",
                    market?.marketValue?.let(::formatTwd) ?: "—",
                ),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(8)
                },
            )
        }
        content.addView(metricRow)

        val tabContent = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(51, 65, 85))
            setPadding(0, dp(10), 0, dp(10))
        }
        var selectedTab = InstrumentInfoTab.DETAIL
        var instrumentProfile: TaiwanInstrumentProfile? = null
        var profileLoadFinished = false
        var dailyBars: List<TaiwanDailyBar> = emptyList()
        var historyLoadFinished = false
        var institutionalFlow: TaiwanInstitutionalFlow? = null
        var institutionalLoadFinished = false
        var revenueSnapshot: TaiwanRevenueSnapshot? = null
        var revenueLoadFinished = false
        val chartHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(4))
        }

        fun profileSummary(profile: TaiwanInstrumentProfile): String = buildString {
            append("${profile.market}｜${profile.industry ?: "產業別待資料源"}")
            profile.listingDate?.let { append("｜掛牌 $it") }
            profile.paidInCapitalTwd?.let { append("\n實收資本額 ${formatTwd(it)}") }
            profile.issuedCommonShares?.let {
                append("｜已發行普通股 ${NumberFormat.getIntegerInstance(Locale.TAIWAN).format(it)} 股")
            }
        }

        fun renderTab() {
            chartHost.removeAllViews()
            chartHost.visibility = View.GONE
            tabContent.text = when (selectedTab) {
                InstrumentInfoTab.DETAIL -> buildString {
                    append("持有 ${holding.shares} 股｜投入成本 ${formatTwd(holding.investmentCost)}")
                    append("\n今日損益 ${market?.todayPnl?.let(::formatSignedTwd) ?: "—"}")
                    append("｜持有總損益 ${market?.totalPnl?.let(::formatSignedTwd) ?: "—"}")
                    if (quote != null) {
                        append("\n\n開 ${quote.open?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜高 ${quote.high?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜低 ${quote.low?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("\n來源 ${sourceName(quote.source)}｜品質 ${quote.quality.name}")
                        append("｜更新 ${Instant.ofEpochMilli(quote.asOfEpochMillis).atZone(taipeiZone).toLocalTime()}")
                    }
                    val profile = instrumentProfile
                    if (profile != null) {
                        append("\n\n台股基本資料｜")
                        append(profileSummary(profile))
                    } else if (!profileLoadFinished) {
                        append("\n\n台股基本資料載入中…")
                    }
                }
                InstrumentInfoTab.TREND -> if (dailyBars.isNotEmpty()) {
                    chartHost.visibility = View.VISIBLE
                    chartHost.addView(
                        TaiwanKLineView(this).apply { setBars(dailyBars) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(260),
                        ),
                    )
                    val latestBar = dailyBars.last()
                    "日 K｜近 1 年真實歷史資料｜${dailyBars.size} 根\n" +
                        "最新 O ${"%.2f".format(Locale.US, latestBar.open)} " +
                        "H ${"%.2f".format(Locale.US, latestBar.high)} " +
                        "L ${"%.2f".format(Locale.US, latestBar.low)} " +
                        "C ${"%.2f".format(Locale.US, latestBar.close)}｜量 ${latestBar.volume}"
                } else if (!historyLoadFinished) {
                    "走勢｜日 K 歷史行情載入中…"
                } else {
                    "走勢｜目前歷史行情來源未回傳此代號；不產生模擬 K 線。"
                }
                InstrumentInfoTab.TECHNICAL -> if (dailyBars.isNotEmpty()) {
                    chartHost.visibility = View.VISIBLE
                    chartHost.addView(
                        TaiwanKLineView(this).apply { setBars(dailyBars) },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(260),
                        ),
                    )
                    val indicators = TaiwanTechnicalIndicators.from(dailyBars)
                    buildString {
                        append("技術｜以同一組真實日 K 計算")
                        append("\nMA5 ${indicators.sma5?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜MA20 ${indicators.sma20?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜MA60 ${indicators.sma60?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("\nRSI14 ${indicators.rsi14?.let { "%.1f".format(Locale.US, it) } ?: "—"}")
                        append("｜20 日均量 ${indicators.averageVolume20 ?: "—"}")
                        append("\n指標只使用已取得歷史 K 線，不補造缺失交易日。")
                    }
                } else if (!historyLoadFinished) {
                    "技術｜歷史 K 線載入中…"
                } else {
                    "技術｜無可核實歷史 K 線，不產生推估指標。"
                }
                InstrumentInfoTab.COMPONENTS -> instrumentProfile?.let { profile ->
                    "個股結構｜${profile.market}｜產業 ${profile.industry ?: "—"}\n" +
                        "公司 ${profile.shortName}；指數成分與同族群資料將只在可核實來源存在時顯示。"
                } ?: if (profileLoadFinished) {
                    "成分｜目前未取得公司型標的資料；ETF 將使用基金成分 / 權重專屬資料層。"
                } else {
                    "成分｜台股標的資料載入中…"
                }
                InstrumentInfoTab.INSTITUTIONAL -> institutionalFlow?.let { flow ->
                    buildString {
                        append("三大法人｜${flow.taipeiDate}")
                        append("\n外資 ${flow.foreignNetShares?.let(::formatSignedShares) ?: "—"}")
                        append("｜投信 ${flow.investmentTrustNetShares?.let(::formatSignedShares) ?: "—"}")
                        append("｜自營商 ${flow.dealerNetShares?.let(::formatSignedShares) ?: "—"}")
                        append("\n來源 ${flow.source}｜單位：股")
                    }
                } ?: if (!institutionalLoadFinished) {
                    "法人｜TWSE / TPEx 三大法人資料載入中…"
                } else {
                    "法人｜最近交易日未取得可核實的官方三大法人明細；不產生推估值。"
                }
                InstrumentInfoTab.FINANCIAL -> instrumentProfile?.let { profile ->
                    buildString {
                        append("${profile.companyName}（${profile.shortName}）")
                        append("\n市場 ${profile.market}｜產業 ${profile.industry ?: "—"}")
                        append("\n實收資本額 ${profile.paidInCapitalTwd?.let(::formatTwd) ?: "—"}")
                        append("｜面額 ${profile.parValueText ?: "—"}")
                        append("\n已發行普通股 ${profile.issuedCommonShares?.let { NumberFormat.getIntegerInstance(Locale.TAIWAN).format(it) + " 股" } ?: "—"}")
                        append("\n掛牌日期 ${profile.listingDate ?: "—"}")
                        profile.englishShortName?.let { append("｜英文簡稱 $it") }
                        append("\n董事長 ${profile.chairman ?: "—"}｜總經理 ${profile.generalManager ?: "—"}")
                        profile.phone?.let { append("\n電話 $it") }
                        profile.website?.let { append("\n網站 $it") }
                        profile.address?.let { append("\n地址 $it") }
                        val revenue = revenueSnapshot
                        if (revenue != null) {
                            append("\n\n月營收｜${revenue.yearMonth ?: "最新"}")
                            append("\n當月 ${revenue.currentMonthRevenueTwd?.let(::formatTwd) ?: "—"}")
                            append("｜上月 ${revenue.previousMonthRevenueTwd?.let(::formatTwd) ?: "—"}")
                            append("\n年增 ${revenue.yearOverYearPct?.let { "%.2f%%".format(Locale.US, it) } ?: "—"}")
                            append("｜月增 ${revenue.monthOverMonthPct?.let { "%.2f%%".format(Locale.US, it) } ?: "—"}")
                            append("\n累計 ${revenue.accumulatedRevenueTwd?.let(::formatTwd) ?: "—"}")
                            append("｜來源 ${revenue.source}")
                        } else if (!revenueLoadFinished) {
                            append("\n\n月營收資料載入中…")
                        }
                        val capitalMetrics = TaiwanCapitalMetricCalculator.calculate(
                            profile = profile,
                            currentPrice = quote?.price,
                            dailyBars = dailyBars,
                        )
                        append("\n\n公司資本 / 市值")
                        append("\n公司市值 ${capitalMetrics.marketCapitalizationTwd?.let(::formatTwd) ?: "—"}")
                        append("｜公式：現價 × 已發行普通股")
                        append("\n每股實收資本 ${capitalMetrics.capitalPerIssuedShareTwd?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("\n一年高 ${capitalMetrics.oneYearHigh?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜一年低 ${capitalMetrics.oneYearLow?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜一年報酬 ${capitalMetrics.oneYearReturnPct?.let { "%.2f%%".format(Locale.US, it) } ?: "—"}")
                    }
                } ?: if (profileLoadFinished) {
                    "財務 / 資本｜目前公開公司基本資料來源未回傳此代號；不以估算值冒充官方資料。"
                } else {
                    "財務 / 資本｜台股公開資料載入中…"
                }
                InstrumentInfoTab.AFTER_HOURS -> "盤後｜收盤價、盤後資訊與當日行情摘要。"
                InstrumentInfoTab.DATA -> buildString {
                    append("行情來源 ${quote?.let { sourceName(it.source) } ?: "—"}")
                    append("｜品質 ${quote?.quality?.name ?: "—"}")
                    instrumentProfile?.let {
                        append("\n基本資料來源 ${it.source}｜市場 ${it.market}")
                        append("\n股本欄位：實收資本額 / 已發行普通股")
                        if (it.website != null || it.phone != null) append("\n公司聯絡資料：已取得")
                        revenueSnapshot?.let { revenue -> append("\n月營收來源 ${revenue.source}") }
                        append("\n公司市值口徑：現價 × 官方已發行普通股；屬衍生計算值")
                    } ?: if (profileLoadFinished) {
                        append("\n基本資料來源：未取得")
                    } else {
                        append("\n基本資料來源：載入中")
                    }
                }
            }
        }

        InstrumentInfoTab.entries.chunked(4).forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                group.forEach { tab ->
                    addView(
                        Button(this@MainActivity).apply {
                            text = tab.label
                            setOnClickListener {
                                selectedTab = tab
                                renderTab()
                            }
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                }
            }
            content.addView(row)
        }
        content.addView(chartHost)
        content.addView(tabContent)
        renderTab()

        val symbols = latestLedgerSnapshot
            ?.holdings
            ?.map { it.symbol }
            ?.sorted()
            .orEmpty()
        val currentIndex = symbols.indexOf(symbol)

        val dialog = AlertDialog.Builder(this)
            .setTitle("個股資訊｜$symbol")
            .setView(
                ScrollView(this).apply {
                    addView(content)
                },
            )
            .setNegativeButton("上一檔") { _, _ ->
                if (currentIndex > 0) {
                    showHoldingDetailDialog(symbols[currentIndex - 1])
                }
            }
            .setNeutralButton("下一檔") { _, _ ->
                if (currentIndex >= 0 && currentIndex + 1 < symbols.size) {
                    showHoldingDetailDialog(symbols[currentIndex + 1])
                }
            }
            .setPositiveButton("關閉", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = currentIndex > 0
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled =
                currentIndex >= 0 && currentIndex + 1 < symbols.size
        }
        dialog.setOnDismissListener {
            if (holdingDetailDialog === dialog) {
                holdingDetailDialog = null
            }
        }
        holdingDetailDialog = dialog
        dialog.show()

        ledgerExecutor.execute {
            val loadedProfile = runCatching {
                taiwanInstrumentInfoProvider.fetchProfile(symbol)
            }.getOrNull()
            val loadedBars = runCatching {
                taiwanDailyHistoryProvider.fetch(symbol)
            }.getOrDefault(emptyList())
            val loadedInstitutional = runCatching {
                taiwanInstitutionalProvider.fetchLatest(symbol)
            }.getOrNull()
            val loadedRevenue = runCatching {
                taiwanRevenueProvider.fetch(symbol)
            }.getOrNull()
            runOnUiThread {
                if (holdingDetailDialog !== dialog || !dialog.isShowing) return@runOnUiThread
                instrumentProfile = loadedProfile
                profileLoadFinished = true
                dailyBars = loadedBars
                historyLoadFinished = true
                institutionalFlow = loadedInstitutional
                institutionalLoadFinished = true
                revenueSnapshot = loadedRevenue
                revenueLoadFinished = true
                renderTab()
            }
        }
    }

    private fun buildInstrumentMetricCard(
        title: String,
        value: String,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = SaiTheme.softCard(resources.displayMetrics.density)
        setPadding(dp(12), dp(11), dp(12), dp(11))
        addView(cardText(title, 13f, SaiTheme.MUTED))
        addView(cardText(value, 18f, SaiTheme.TEXT).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
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

            val advancingCount = rows.count { quote ->
                quote.previousClose?.let { quote.price > it } == true
            }
            val decliningCount = rows.count { quote ->
                quote.previousClose?.let { quote.price < it } == true
            }
            val unchangedCount = rows.size - advancingCount - decliningCount

            val body = when (mode) {
                MarketWallMode.DETAIL_LIST -> rows.joinToString("\n\n") { quote ->
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
                        append("\n開 ${quote.open?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜高 ${quote.high?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜低 ${quote.low?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("\n來源 ${sourceName(quote.source)}｜品質 ${quote.quality.name}")
                    }
                }

                MarketWallMode.LARGE_LIST -> rows.joinToString("\n\n") { quote ->
                    val previous = quote.previousClose
                    val change = previous?.let { quote.price - it }
                    val pct = previous
                        ?.takeIf { it > 0.0 }
                        ?.let { (quote.price - it) / it * 100.0 }
                    buildString {
                        append("${quote.name}  ${quote.symbol}")
                        append("\n${"%.2f".format(Locale.US, quote.price)}")
                        if (change != null && pct != null) {
                            append("   ${if (change > 0) "+" else ""}${"%.2f".format(Locale.US, change)}")
                            append("   ${if (pct > 0) "+" else ""}${"%.2f".format(Locale.US, pct)}%")
                        }
                    }
                }

                MarketWallMode.GRID -> rows.chunked(2).joinToString("\n────────────\n") { pair ->
                    pair.joinToString("    │    ") { quote ->
                        val pct = quote.previousClose
                            ?.takeIf { it > 0.0 }
                            ?.let { (quote.price - it) / it * 100.0 }
                        buildString {
                            append("${quote.symbol} ${quote.name}")
                            append("\n${"%.2f".format(Locale.US, quote.price)}")
                            pct?.let {
                                append("  ${if (it > 0) "+" else ""}${"%.2f".format(Locale.US, it)}%")
                            }
                        }
                    }
                }

                MarketWallMode.MULTI_TREND -> rows.joinToString("\n\n") { quote ->
                    val open = quote.open
                    val arrow = when {
                        open == null -> "→"
                        quote.price > open -> "↗"
                        quote.price < open -> "↘"
                        else -> "→"
                    }
                    buildString {
                        append("${quote.symbol} ${quote.name}")
                        append("\n${open?.let { "%.2f".format(Locale.US, it) } ?: "—"} $arrow ${"%.2f".format(Locale.US, quote.price)}")
                        append("｜高 ${quote.high?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                        append("｜低 ${quote.low?.let { "%.2f".format(Locale.US, it) } ?: "—"}")
                    }
                }

                MarketWallMode.TREND_SIGNAL -> rows.joinToString("\n\n") { quote ->
                    val previous = quote.previousClose
                    val pct = previous
                        ?.takeIf { it > 0.0 }
                        ?.let { (quote.price - it) / it * 100.0 }
                    val intraday = quote.open?.let { open ->
                        when {
                            quote.price > open -> "盤中偏多"
                            quote.price < open -> "盤中偏空"
                            else -> "盤中中立"
                        }
                    } ?: "盤中待判"
                    val high = quote.high
                    val low = quote.low
                    val position = if (high != null && low != null && high > low) {
                        ((quote.price - low) / (high - low) * 100.0)
                            .coerceIn(0.0, 100.0)
                    } else {
                        null
                    }
                    buildString {
                        append("${quote.symbol} ${quote.name}｜$intraday")
                        pct?.let {
                            append("\n漲跌 ${if (it > 0) "+" else ""}${"%.2f".format(Locale.US, it)}%")
                        }
                        append("｜日內位置 ${position?.let { "%.0f%%".format(Locale.US, it) } ?: "—"}")
                        append("\n僅依即時 / OHLC 真實資料，不虛構多週期籌碼")
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
                        append("｜漲 $advancingCount 跌 $decliningCount 平 $unchangedCount")
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

            MarketWallMode.entries.chunked(3).forEach { modeGroup ->
                val modeRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    modeGroup.forEach { option ->
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
            }

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
                    textSize = when (mode) {
                        MarketWallMode.LARGE_LIST -> 20f
                        MarketWallMode.GRID -> 16f
                        else -> 14f
                    }
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
            MarketSource.FUGLE -> "Fugle"
            MarketSource.TWSE_MIS -> "TWSE MIS"
            MarketSource.YAHOO -> "Yahoo"
            MarketSource.CACHE -> "Cache"
        }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = "▌  $textValue"
        contentDescription = "區段：$textValue"
        textSize = 16f * displayScale
        setTextColor(SaiTheme.BRAND)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = SaiTheme.rounded(
            fill = SaiTheme.BRAND_SOFT,
            radiusDp = 14f,
            density = resources.displayMetrics.density,
            strokeColor = SaiTheme.BORDER,
            strokeDp = 1,
        )
        setPadding(displayDp(14), displayDp(10), displayDp(14), displayDp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            topMargin = displayDp(6)
            bottomMargin = displayDp(10)
        }
    }

    private fun statusText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f * displayScale
        setTextColor(SaiTheme.TEXT_SECONDARY)
        background = SaiTheme.softCard(resources.displayMetrics.density)
        setPadding(displayDp(14), displayDp(11), displayDp(14), displayDp(11))
        setLineSpacing(0f, 1.08f)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = displayDp(10)
        }
        contentDescription = "狀態：$value"
    }

    private fun buildMetricCard(
        title: String,
        value: String,
        note: String,
    ): Pair<LinearLayout, TextView> {
        val valueView = cardText(value, 26f, SaiTheme.TEXT).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, displayDp(5), 0, displayDp(5))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(resources.displayMetrics.density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(17), displayDp(15), displayDp(17), displayDp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        View(this@MainActivity).apply {
                            setBackgroundColor(SaiTheme.BRAND)
                        },
                        LinearLayout.LayoutParams(dp(4), dp(18)).apply {
                            marginEnd = displayDp(8)
                        },
                    )
                    addView(
                        cardText(title, 14f, SaiTheme.TEXT_SECONDARY).apply {
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        },
                    )
                },
            )
            addView(valueView)
            addView(
                cardText(note, 12f, SaiTheme.MUTED).apply {
                    setLineSpacing(0f, 1.08f)
                },
            )
        }
        return card to valueView
    }

    private fun buildLandingCard(card: FirstVersionContract.LandingCard): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = SaiTheme.card(resources.displayMetrics.density)
            elevation = dp(1).toFloat()
            setPadding(displayDp(16), displayDp(15), displayDp(16), displayDp(15))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = displayDp(10)
            }
            addView(cardText(card.title, 17f, SaiTheme.TEXT).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(cardText(card.body, 14f, SaiTheme.TEXT_SECONDARY))
            addView(cardText(card.status, 13f, SaiTheme.MUTED))
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
                    "Fugle 即時行情" -> showFugleSettingsDialog()
                    "立即更新行情" -> requestImmediateMarketRefresh()
                    "介面恢復標準" -> showResetDisplaySettingsConfirmation()
                }
            }
        }

    private fun showResetDisplaySettingsConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("恢復介面標準設定")
            .setMessage("將文字比例與卡片間距恢復為標準值。此動作需要再次確認。")
            .setNegativeButton("取消", null)
            .setPositiveButton("確認恢復") { _, _ ->
                getSharedPreferences("saietf-display", MODE_PRIVATE)
                    .edit()
                    .remove("scale")
                    .remove("spacing")
                    .apply()
                recreate()
            }
            .show()
    }

    private fun requestImmediateMarketRefresh() {
        if (marketPollingActive) {
            scheduleMarketRefresh(0L, pollGeneration)
            Toast.makeText(this, "已要求行情中心立即更新", Toast.LENGTH_SHORT).show()
        } else {
            refreshDashboard()
            Toast.makeText(this, "已重新整理帳務與行情狀態", Toast.LENGTH_SHORT).show()
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

    private fun showFugleSettingsDialog() {
        val configured = fugleApiKeyStore.hasKey()
        val input = EditText(this).apply {
            hint = if (configured) {
                "已設定 API Key；輸入新 Key 可更換"
            } else {
                "輸入 Fugle API Key"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val currentSymbols = latestLedgerSnapshot?.holdings?.map { it.symbol }?.toSet().orEmpty()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Fugle 即時行情")
            .setMessage(
                "API Key 使用 Android Keystore 加密後儲存在本機，" +
                    "不寫入 GitHub，也不納入 SaiETF JSON 備份。"
            )
            .setView(input)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除") { _, _ ->
                fugleApiKeyStore.clear()
                fugleStreamingController.onCredentialChanged(currentSymbols)
                Toast.makeText(this, "Fugle API Key 已清除", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("儲存", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val apiKey = input.text?.toString()?.trim().orEmpty()
                if (apiKey.isBlank()) {
                    Toast.makeText(this, "請輸入 Fugle API Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                runCatching {
                    fugleApiKeyStore.save(apiKey)
                }.onSuccess {
                    fugleStreamingController.onCredentialChanged(currentSymbols)
                    Toast.makeText(
                        this,
                        "Fugle WebSocket 憑證已安全儲存並重新連線",
                        Toast.LENGTH_SHORT,
                    ).show()
                    dialog.dismiss()
                }.onFailure { error ->
                    Toast.makeText(
                        this,
                        "API Key 儲存失敗：${error.message ?: "未知錯誤"}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        dialog.show()
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
            ?.values
            ?.map { it.symbol }
            ?.sorted()
            ?.joinToString(", ")
            ?.ifBlank { "無" }
            ?: "無"
        val fugleHealth = fugleStreamingController.health()
        val fugleConfigured = fugleApiKeyStore.hasKey()
        val providerHealthText = batch?.providerHealth
            ?.joinToString("｜") { health ->
                buildString {
                    append(sourceName(health.source))
                    append(" ")
                    append(health.circuitState.name)
                    append("/")
                    append(health.availability.name)
                    if (health.consecutiveFailures > 0) {
                        append(" fail=")
                        append(health.consecutiveFailures)
                    }
                }
            }
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
            append("\n來源保護 $providerHealthText")
            append("\nFugle WS ")
            append(if (fugleConfigured) "已設定" else "未設定")
            append("｜")
            append(fugleHealth.circuitState.name)
            append("/")
            append(fugleHealth.availability.name)
            if (fugleHealth.consecutiveFailures > 0) {
                append(" fail=")
                append(fugleHealth.consecutiveFailures)
            }
            append("\n\n同步策略：Fugle WebSocket → TWSE MIS → Yahoo；UI 1 秒刷新；行情中心統一仲裁、節流與備援")
            append("\n升級保護：固定 applicationId、固定開發簽章、versionCode 遞增、Room migration Gate")
        }

        AlertDialog.Builder(this)
            .setTitle("系統狀態 / 診斷")
            .setMessage(body)
            .setNeutralButton("行情明細") { _, _ ->
                showQuoteDiagnosticsDialog()
            }
            .setNegativeButton("重新整理") { _, _ ->
                refreshDashboard()
            }
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showQuoteDiagnosticsDialog() {
        val batch = latestMarketBatch
        if (batch == null || batch.quotes.isEmpty()) {
            Toast.makeText(this, "目前沒有行情診斷資料", Toast.LENGTH_SHORT).show()
            return
        }

        val now = System.currentTimeMillis()
        val body = batch.quotes.values
            .sortedBy { it.symbol }
            .joinToString("\n\n") { quote ->
                val ageSeconds = ((now - quote.asOfEpochMillis).coerceAtLeast(0L) / 1_000L)
                buildString {
                    append("${quote.symbol} ${quote.name}")
                    append("\n現價 ${"%.2f".format(Locale.US, quote.price)}")
                    append("｜來源 ${sourceName(quote.source)}")
                    append("\n品質 ${quote.quality.name}")
                    append("｜距今 ${ageSeconds}s")
                    append("\n交易日 ${quote.sessionDate ?: taipeiDateOf(quote.sourceTimestampEpochMillis)}")
                    append("｜Seq ${quote.sequence?.toString() ?: "—"}")
                    append("｜Fallback ${quote.fallbackLevel}")
                }
            }

        AlertDialog.Builder(this)
            .setTitle("行情來源 / 品質診斷")
            .setMessage(body)
            .setNeutralButton("複製診斷") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("SaiETF 行情診斷", body),
                )
                Toast.makeText(this, "行情診斷已複製", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("返回狀態") { _, _ ->
                showSystemStatusDialog()
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

    private fun styleDialog(
        dialog: AlertDialog,
        accent: Boolean = false,
    ) {
        val density = resources.displayMetrics.density
        dialog.window?.setBackgroundDrawable(
            SaiTheme.rounded(
                fill = SaiTheme.CARD,
                radiusDp = 24f,
                density = density,
                strokeColor = SaiTheme.BORDER,
                strokeDp = 1,
            ),
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(if (accent) SaiTheme.ACCENT else SaiTheme.BRAND)
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(SaiTheme.MUTED)
            isAllCaps = false
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
            setTextColor(SaiTheme.BRAND)
            isAllCaps = false
        }
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

    private fun optionSpinner(
        items: List<String>,
        selection: Int = 0,
    ): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(
            this@MainActivity,
            android.R.layout.simple_spinner_dropdown_item,
            items,
        )
        setSelection(selection.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        background = SaiTheme.rounded(
            fill = SaiTheme.INPUT,
            radiusDp = 14f,
            density = resources.displayMetrics.density,
            strokeColor = SaiTheme.BORDER,
            strokeDp = 1,
        )
        minimumHeight = dp(52)
        setPadding(dp(12), 0, dp(10), 0)
    }

    private fun input(hintValue: String): EditText = EditText(this).apply {
        hint = hintValue
        textSize = 15f * displayScale
        setTextColor(SaiTheme.TEXT)
        setHintTextColor(Color.rgb(148, 163, 184))
        background = SaiTheme.rounded(
            fill = SaiTheme.INPUT,
            radiusDp = 14f,
            density = resources.displayMetrics.density,
            strokeColor = SaiTheme.BORDER,
            strokeDp = 1,
        )
        minimumHeight = dp(52)
        setPadding(dp(14), dp(10), dp(14), dp(10))
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f * displayScale
        setTextColor(SaiTheme.MUTED)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(2), dp(10), 0, dp(5))
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

    private fun formatSignedShares(value: Long): String {
        val sign = if (value > 0L) "+" else ""
        return sign + NumberFormat.getIntegerInstance(Locale.TAIWAN).format(value) + " 股"
    }

    private fun displayDp(value: Int): Int = dp((value * displaySpacingScale).toInt().coerceAtLeast(1))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
