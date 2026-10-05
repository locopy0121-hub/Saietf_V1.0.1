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
    const val releaseLine = "第五十八階段 1.0.67｜首頁與行情｜持股快照・即時行情列表"
    const val phaseLine = "首頁持股快照｜行情排序｜Source/Quality/Age｜StateFlow 即時刷新｜Finance Lock 不變"

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
            note = "昨日取上一交易日快照；可點入日 / 週 / 月 / 年走勢與逐日損益紀錄",
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
            status = "Ledger Repository + Room 已實裝；賣出前先比對目前持股，核心 projector 仍保留第二道檢查",
        ),
        LandingCard(
            title = "交易紀錄",
            body = "不可變 Ledger 交易明細，支援 10 / 20 / 50 筆分頁",
            status = "最新到最舊，支援 10 / 20 / 50 筆分頁；每筆可修改或刪除，底層採 append-only 修正紀錄保留原始 Ledger 稽核軌跡",
        ),
        LandingCard(
            title = "持股分析",
            body = "依即時市值計算配置權重、Top 1 / Top 3 集中度與損益",
            status = "完整行情覆蓋後才發布分析結果",
        ),
        LandingCard(
            title = "持股清單",
            body = "台股與 ETF 庫存由 Ledger 經 Finance Lock 投影",
            status = "個股低頻資料新增 Room v5：法人、月營收、季財務、ETF 成分、股利參考、盤後；統一 dataDate / period / source / fetchedAt / quality / freshness / rawRevision",
        ),
        LandingCard(
            title = "行情牆",
            body = "Fugle WebSocket 優先，TWSE MIS / Yahoo 自動備援；盤中 UI 1 秒同步",
            status = "Fugle 串流優先；失聯或不新鮮時由 TWSE MIS / Yahoo 接手，全部走 Session / Timestamp / Sequence 仲裁與 Memory SSOT",
        ),
        LandingCard(
            title = "股息",
            body = "預告可先登錄；相同代號＋除息日可更新為已確認",
            status = "月曆可前後切換月份；新增 / 更新會驗證股利金額、日期格式與日期先後順序",
        ),
        LandingCard(
            title = "資料備份",
            body = "本機 Room 優先；可匯出 JSON 備份並從空白帳務安全還原",
            status = "交易、每日損益、走勢、股息均納入；還原前先驗 SHA-256、版本與資料筆數，Ledger 不允許覆寫",
        ),
        LandingCard(
            title = "顯示設定",
            body = "精簡 / 標準 / 放大三段文字比例",
            status = "設定保存在本機；套用或恢復標準後立即重建主畫面",
        ),
        LandingCard(
            title = "卡片間距",
            body = "緊湊 / 標準 / 寬鬆三段卡片密度",
            status = "調整資產卡片與功能卡片的內距與卡片間距，設定保存在本機",
        ),
        LandingCard(
            title = "系統狀態",
            body = "版本、Ledger、持股、行情覆蓋、資料來源與走勢點數",
            status = "提供版本、新鮮度、Provider Circuit State、Fugle WebSocket 與逐檔 Session / Sequence / Fallback；Room schema v4 已納入行情快照與 1m K",
        ),
        LandingCard(
            title = "Fugle 即時行情",
            body = "設定個人 Fugle API Key，啟用台股 WebSocket trades 即時推播",
            status = "API Key 以 Android Keystore 加密儲存在本機；支援 30 秒 Heartbeat、Ping/Pong、斷線重連與訂閱差異更新",
        ),
        LandingCard(
            title = "立即更新行情",
            body = "手動要求行情中心立即刷新持股報價",
            status = "不改變盤中 1 秒排程；只追加一次立即更新請求",
        ),
        LandingCard(
            title = "介面恢復標準",
            body = "將文字比例與卡片間距恢復為標準值",
            status = "執行前需二次確認；只清除介面偏好，不碰 Ledger 與行情資料",
        ),
    )

    val landingSections: List<String> = landingCards.map { card ->
        "${card.title}：${card.body}｜${card.status}"
    }
}
