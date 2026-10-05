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
    const val releaseLine = "第十四階段 1.0.23｜行情牆｜模式排序記憶"
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
            note = "昨日取上一交易日快照；今日與持有總損益分開計算",
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
            title = "交易紀錄",
            body = "不可變 Ledger 交易明細，支援 10 / 20 / 50 筆分頁",
            status = "最新到最舊，可往前追溯第一筆交易",
        ),
        LandingCard(
            title = "持股分析",
            body = "依即時市值計算配置權重、Top 1 / Top 3 集中度與損益",
            status = "完整行情覆蓋後才發布分析結果",
        ),
        LandingCard(
            title = "持股清單",
            body = "台股與 ETF 庫存由 Ledger 經 Finance Lock 投影",
            status = "加入行情價格、市值、今日與總損益；儀表板可查看每日紀錄",
        ),
        LandingCard(
            title = "行情牆",
            body = "TWSE MIS 優先，Yahoo 無金鑰備援；盤中 1 秒更新",
            status = "App 啟動 / 回前景立即抓取；精簡/詳細、排序欄位與升降冪會保留上次選擇",
        ),
        LandingCard(
            title = "股息",
            body = "預告可先登錄；相同代號＋除息日可更新為已確認",
            status = "月曆日期、日期順序驗證、持股股數與預估入金已實裝",
        ),
        LandingCard(
            title = "資料備份",
            body = "本機 Room 優先；可匯出 JSON 備份並從空白帳務安全還原",
            status = "交易、每日損益、走勢、股息均納入；Ledger 不允許覆寫",
        ),
    )

    val landingSections: List<String> = landingCards.map { card ->
        "${card.title}：${card.body}｜${card.status}"
    }
}
