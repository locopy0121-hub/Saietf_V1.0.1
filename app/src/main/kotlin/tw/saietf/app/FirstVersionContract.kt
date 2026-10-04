package tw.saietf.app

object FirstVersionContract {
    const val appDisplayName = "SaiETF 資產管家"
    const val releaseLine = "第一版 1.0.1｜本機優先｜台股 ETF"
    const val accountingLine = "帳務核心：TF Asset V3.7.8 Finance Lock 已鎖定"
    const val storageLine = "資料層：Room 本機帳務資料庫 V1 已建立"
    const val marketLine = "行情牆：預留台股 / ETF 即時行情入口"
    const val dividendLine = "股息：預留自動更新與待確認登錄入口"
    const val buildLine = "建置：GitHub Actions 產出可安裝 APK"

    val landingSections: List<String> = listOf(
        accountingLine,
        storageLine,
        marketLine,
        dividendLine,
        buildLine,
    )
}
