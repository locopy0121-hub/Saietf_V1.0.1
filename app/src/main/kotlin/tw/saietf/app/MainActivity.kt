package tw.saietf.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
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
        FirstVersionContract.dashboardMetrics.forEach { metric ->
            root.addView(buildMetricCard(metric))
        }

        root.addView(sectionTitle("功能入口"))
        FirstVersionContract.landingCards.forEach { card ->
            root.addView(buildLandingCard(card))
        }

        setContentView(
            ScrollView(this).apply {
                addView(root)
            },
        )
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 18f
        setTextColor(Color.rgb(15, 23, 42))
        setPadding(0, dp(6), 0, dp(8))
    }

    private fun buildMetricCard(metric: FirstVersionContract.DashboardMetric): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.WHITE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(10)
        }
        addView(cardText(metric.title, 15f, Color.rgb(71, 85, 105)))
        addView(cardText(metric.value, 24f, Color.rgb(15, 23, 42)))
        addView(cardText(metric.note, 13f, Color.rgb(100, 116, 139)))
    }

    private fun buildLandingCard(card: FirstVersionContract.LandingCard): LinearLayout = LinearLayout(this).apply {
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
    }

    private fun cardText(textValue: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
        text = textValue
        textSize = sizeSp
        setTextColor(color)
        setPadding(0, 0, 0, dp(4))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
