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
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import tw.saietf.core.database.LedgerRepository
import tw.saietf.core.finance.LedgerEntryKind
import tw.saietf.core.model.TradeMode

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val repository: LedgerRepository
        get() = (application as SaiEtfApplication).ledgerRepository

    private lateinit var totalAssetValue: TextView
    private lateinit var investmentCostValue: TextView
    private lateinit var pnlValue: TextView
    private lateinit var holdingsCountValue: TextView
    private lateinit var ledgerStatusValue: TextView

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
            "待行情中心",
            "尚未接行情前不以帳務成本冒充即時市值",
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
            "行情損益待行情中心；已實現損益由 Ledger 計算",
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

        ledgerStatusValue = TextView(this).apply {
            text = "帳務資料載入中…"
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            setPadding(0, 0, 0, dp(14))
        }
        root.addView(ledgerStatusValue)

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

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun refreshDashboard() {
        executor.execute {
            runCatching { repository.loadDashboard() }
                .onSuccess { snapshot ->
                    runOnUiThread { applyDashboard(snapshot) }
                }
                .onFailure { error ->
                    runOnUiThread {
                        ledgerStatusValue.text = "帳務載入失敗：${error.message ?: "未知錯誤"}"
                    }
                }
        }
    }

    private fun applyDashboard(snapshot: LedgerRepository.DashboardSnapshot) {
        totalAssetValue.text = "待行情中心"
        investmentCostValue.text = formatTwd(snapshot.totalInvestmentCost)
        pnlValue.text = "— / — / —"
        holdingsCountValue.text = "${snapshot.holdingCount} 檔"
        ledgerStatusValue.text =
            "Ledger ${snapshot.ledgerCount} 筆｜已實現損益 ${formatSignedTwd(snapshot.realizedNetPnL)}"
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
            setText(LocalDate.now(ZoneId.of("Asia/Taipei")).toString())
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

                executor.execute {
                    runCatching { repository.addTrade(command) }
                        .onSuccess { snapshot ->
                            runOnUiThread {
                                applyDashboard(snapshot)
                                Toast.makeText(this, "交易已寫入不可變 Ledger", Toast.LENGTH_SHORT).show()
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
        executor.execute {
            runCatching { repository.loadDashboard() }
                .onSuccess { snapshot ->
                    val body = if (snapshot.holdings.isEmpty()) {
                        "目前沒有持股。"
                    } else {
                        snapshot.holdings.joinToString("\n\n") { holding ->
                            "${holding.symbol}｜${holding.shares} 股\n" +
                                "投入成本 ${formatTwd(holding.investmentCost)}\n" +
                                "已實現損益 ${formatSignedTwd(holding.realizedNetPnL)}"
                        }
                    }
                    runOnUiThread {
                        AlertDialog.Builder(this)
                            .setTitle("持股清單")
                            .setMessage(body)
                            .setPositiveButton("關閉", null)
                            .show()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        Toast.makeText(this, "持股讀取失敗：${error.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 18f
        setTextColor(Color.rgb(15, 23, 42))
        setPadding(0, dp(6), 0, dp(8))
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
                    "行情牆" -> Toast.makeText(this@MainActivity, "行情中心將於下一階段串接", Toast.LENGTH_SHORT).show()
                    "股息" -> Toast.makeText(this@MainActivity, "股息資料源將於後續階段串接", Toast.LENGTH_SHORT).show()
                    "資料備份" -> Toast.makeText(this@MainActivity, "備份功能將在 Ledger 穩定後實裝", Toast.LENGTH_SHORT).show()
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

    private fun formatTwd(value: Double): String =
        "NT$ " + NumberFormat.getIntegerInstance(Locale.TAIWAN).format(value.toLong())

    private fun formatSignedTwd(value: Double): String {
        val rounded = value.toLong()
        val sign = if (rounded > 0) "+" else ""
        return sign + "NT$ " + NumberFormat.getIntegerInstance(Locale.TAIWAN).format(rounded)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
