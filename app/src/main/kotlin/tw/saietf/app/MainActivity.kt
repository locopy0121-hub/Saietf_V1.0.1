package tw.saietf.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tw.saietf.core.database.LedgerRepository
import tw.saietf.core.finance.LedgerEntryKind
import tw.saietf.core.market.HoldingCost
import tw.saietf.core.market.MarketBatch
import tw.saietf.core.market.MarketDataCenter
import tw.saietf.core.market.MarketSource
import tw.saietf.core.market.PortfolioMarketValuation
import tw.saietf.core.market.PortfolioMarketValuator
import tw.saietf.core.model.TradeMode

class MainActivity : Activity() {
    private val ledgerExecutor = Executors.newSingleThreadExecutor()
    private val marketScheduler = Executors.newSingleThreadScheduledExecutor()
    private val valuator = PortfolioMarketValuator()
    private val taipeiZone = ZoneId.of("Asia/Taipei")

    private val repository: LedgerRepository
        get() = (application as SaiEtfApplication).ledgerRepository

    private val marketDataCenter: MarketDataCenter
        get() = (application as SaiEtfApplication).marketDataCenter

    @Volatile
    private var marketPollingActive = false

    @Volatile
    private var pollGeneration = 0L

    @Volatile
    private var latestLedgerSnapshot: LedgerRepository.DashboardSnapshot? = null

    @Volatile
    private var latestMarketBatch: MarketBatch? = null

    @Volatile
    private var latestValuation: PortfolioMarketValuation? = null

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
            "三者為不同數據；昨日等待每日快照",
        )
        pnlValue = pnl.second
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
            pnlValue.text = "— / NT$ 0 / NT$ 0"
            marketStatusValue.text = "目前沒有持股，不需抓取行情"
            latestMarketBatch = null
            latestValuation = valuator.value(emptyList(), emptyMap())
        } else if (latestMarketBatch == null) {
            totalAssetValue.text = "行情載入中"
            pnlValue.text = "— / — / —"
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
        val tradingSession = isTaipeiTradingSession(taipeiNow.dayOfWeek, taipeiNow.toLocalTime())

        val batch = marketDataCenter.refresh(
            symbols = symbols,
            nowEpochMillis = now,
            currentTaipeiDate = taipeiNow.toLocalDate().toString(),
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

        runOnUiThread {
            applyLedgerSnapshot(snapshot)
            applyMarketValuation(batch, valuation, tradingSession)
        }

        val nextDelay = if (tradingSession) 1_000L else 30_000L
        scheduleMarketRefresh(nextDelay, generation)
    }

    private fun applyMarketValuation(
        batch: MarketBatch,
        valuation: PortfolioMarketValuation,
        tradingSession: Boolean,
    ) {
        if (valuation.expectedHoldingCount == 0) return

        if (valuation.isComplete) {
            totalAssetValue.text = formatTwd(valuation.totalMarketValue ?: 0L)
            pnlValue.text =
                "— / ${valuation.todayPnl?.let(::formatSignedTwd) ?: "—"} / " +
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
        marketStatusValue.text =
            "行情中心：$sources｜$mode｜覆蓋 ${valuation.quotedHoldingCount}/" +
                "${valuation.expectedHoldingCount}$stale"
    }

    private fun isTaipeiTradingSession(day: DayOfWeek, time: LocalTime): Boolean {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false
        return !time.isBefore(LocalTime.of(9, 0)) && !time.isAfter(LocalTime.of(13, 30))
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
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showMarketWall() {
        val batch = latestMarketBatch
        if (batch == null || batch.quotes.isEmpty()) {
            Toast.makeText(this, "目前沒有可顯示的行情", Toast.LENGTH_SHORT).show()
            return
        }

        val body = batch.quotes.toSortedMap().values.joinToString("\n\n") { quote ->
            val change = quote.previousClose?.let { quote.price - it }
            val pct = quote.previousClose
                ?.takeIf { it > 0.0 }
                ?.let { (quote.price - it) / it * 100.0 }
            buildString {
                append("${quote.symbol} ${quote.name}\n")
                append("現價 ${quote.price}")
                if (change != null && pct != null) {
                    append("｜${if (change > 0) "+" else ""}${"%.2f".format(Locale.US, change)}")
                    append(" (${if (pct > 0) "+" else ""}${"%.2f".format(Locale.US, pct)}%)")
                }
                append("\n來源 ${sourceName(quote.source)}｜品質 ${quote.quality.name}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("行情牆")
            .setMessage(body)
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun sourceName(source: MarketSource): String =
        when (source) {
            MarketSource.TWSE_MIS -> "TWSE MIS"
            MarketSource.YAHOO -> "Yahoo"
        }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 18f
        setTextColor(Color.rgb(15, 23, 42))
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun statusText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
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
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
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
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(10)
            }
            addView(cardText(card.title, 17f, Color.rgb(15, 23, 42)))
            addView(cardText(card.body, 14f, Color.rgb(51, 65, 85)))
            addView(cardText(card.status, 13f, Color.rgb(100, 116, 139)))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                when (card.title) {
                    "交易新增" -> showTradeDialog()
                    "持股清單" -> showHoldingsDialog()
                    "行情牆" -> showMarketWall()
                    "股息" -> Toast.makeText(this@MainActivity, "股息資料源將於後續階段串接", Toast.LENGTH_SHORT).show()
                    "資料備份" -> Toast.makeText(this@MainActivity, "備份功能將在行情中心穩定後實裝", Toast.LENGTH_SHORT).show()
                }
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
        textSize = sizeSp
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
