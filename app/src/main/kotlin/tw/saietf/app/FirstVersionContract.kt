package tw.saietf.app

object FirstVersionContract {
    data class DashboardMetric(
        val title: String,
        val value: String,
        val note: String,
    )

    data class LandingCard(
        val title: String,
        val body: String,
        val status: String,
    )

    const val appDisplayName = "SaiETF 資產管家"
    const val releaseLine = "第三階段 1.0.3｜Ledger 實裝｜固定開發簽章"
    const val phaseLine = "本機優先｜台股 ETF｜Finance Lock 不變"

    val dashboardMetrics: List<DashboardMetric> = listOf(
        DashboardMetric(
            title = "總資產",
            value = "待行情中心",
            note = "尚未接行情前不以成本冒充市值",
        ),
        DashboardMetric(
            title = "帳務投入成本",
            value = "NT$ 0",
            note = "由 Room Ledger 經 Finance Lock 投影",
        ),
        DashboardMetric(
            title = "昨日 / 今日 / 總損益",
            value = "— / — / —",
            note = "行情損益待行情中心；已實現損益另由 Ledger 計算",
        ),
        DashboardMetric(
            title = "持股檔數",
            value = "0 檔",
            note = "由不可變交易 Ledger 推導",
        ),
    )

    val landingCards: List<LandingCard> = listOf(
        LandingCard(
            title = "交易新增",
            body = "買進 / 賣出 / 股數 / 成交價 / 手續費 / 證交稅實際值",
            status = "已接 Ledger Repository + Room",
        ),
        LandingCard(
            title = "持股清單",
            body = "台股與 ETF 庫存由 Ledger 經 Finance Lock 投影",
            status = "公式禁止在 UI 重算",
        ),
        LandingCard(
            title = "行情牆",
            body = "預留台股 / ETF 即時行情與走勢圖模組入口",
            status = "下一階段接行情中心",
        ),
        LandingCard(
            title = "股息",
            body = "預留自動更新、預告登錄、待確認更新流程",
            status = "等待股息資料源串接",
        ),
        LandingCard(
            title = "資料備份",
            body = "本機 Room 優先，後續加入匯出備份與還原",
            status = "不可破壞本機帳務 SSOT",
        ),
    )

    val landingSections: List<String> = landingCards.map { card ->
        "${card.title}：${card.body}｜${card.status}"
    }
}
