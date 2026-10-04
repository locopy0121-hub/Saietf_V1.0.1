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
    const val releaseLine = "第二階段 1.0.2｜主頁雛型｜Room 帳務串接"
    const val phaseLine = "本機優先｜台股 ETF｜Finance Lock 不變"

    val dashboardMetrics: List<DashboardMetric> = listOf(
        DashboardMetric(
            title = "總資產",
            value = "NT$ 0",
            note = "等待第一筆交易建立後，由 Room 帳務資料庫投影",
        ),
        DashboardMetric(
            title = "昨日 / 今日 / 總損益",
            value = "0 / 0 / 0",
            note = "三者分開顯示，不互相加總混用",
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
            body = "預留買進 / 賣出 / 手續費 / 證交稅實際值入口",
            status = "下一步接 Ledger Repository",
        ),
        LandingCard(
            title = "持股清單",
            body = "預留台股與 ETF 庫存列表，資料由帳務核心計算",
            status = "公式禁止在 UI 重算",
        ),
        LandingCard(
            title = "行情牆",
            body = "預留台股 / ETF 即時行情與走勢圖模組入口",
            status = "等待行情中心串接",
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
