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
    const val releaseLine = "第四階段 1.0.5｜行情中心｜即時資產估值"
    const val phaseLine = "本機優先｜TWSE MIS → Yahoo｜Finance Lock 不變"

    val dashboardMetrics: List<DashboardMetric> = listOf(
        DashboardMetric(
            title = "總資產",
            value = "行情載入中",
            note = "完整行情覆蓋才發布總市值，禁止用部分報價冒充總資產",
        ),
        DashboardMetric(
            title = "帳務投入成本",
            value = "NT$ 0",
            note = "由 Room Ledger 經 Finance Lock 投影",
        ),
        DashboardMetric(
            title = "昨日 / 今日 / 總損益",
            value = "— / — / —",
            note = "今日與總損益分開計算；昨日待每日快照串接",
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
            status = "Ledger Repository + Room 已實裝",
        ),
        LandingCard(
            title = "持股清單",
            body = "台股與 ETF 庫存由 Ledger 經 Finance Lock 投影",
            status = "加入行情價格、市值、今日與總損益",
        ),
        LandingCard(
            title = "行情牆",
            body = "TWSE MIS 優先，Yahoo 無金鑰備援；盤中 1 秒更新",
            status = "App 啟動 / 回前景立即抓取",
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
