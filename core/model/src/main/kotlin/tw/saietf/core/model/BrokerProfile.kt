package tw.saietf.core.model

enum class RoundingMode {
    FLOOR,
    ROUND,
    CEIL,
}

enum class UnrealizedPlMode {
    NET,
    GROSS,
}

data class BrokerProfile(
    val id: String = DEFAULT_ID,
    val name: String = "App 預設",
    val commissionRate: Double = 0.001425,
    val commissionDiscount: Double = 0.65,
    val minimumCommissionRoundLot: Long = 20,
    val minimumCommissionOddLot: Long = 1,
    val etfSellTaxRate: Double = 0.001,
    val stockSellTaxRate: Double = 0.003,
    val tradeAmountRounding: RoundingMode = RoundingMode.FLOOR,
    val commissionRounding: RoundingMode = RoundingMode.FLOOR,
    val taxRounding: RoundingMode = RoundingMode.FLOOR,
    val unrealizedPlMode: UnrealizedPlMode = UnrealizedPlMode.NET,
    val includeEstimatedSellFee: Boolean = true,
    val includeEstimatedSellTax: Boolean = true,
) {
    init {
        require(id.isNotBlank())
        require(commissionRate >= 0.0)
        require(commissionDiscount >= 0.0)
        require(minimumCommissionRoundLot >= 0)
        require(minimumCommissionOddLot >= 0)
        require(etfSellTaxRate >= 0.0)
        require(stockSellTaxRate >= 0.0)
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val HUANAN_YONGCHANG_ID = "huanan-yongchang"

        val DEFAULT = BrokerProfile()

        val HUANAN_YONGCHANG = BrokerProfile(
            id = HUANAN_YONGCHANG_ID,
            name = "華南永昌證券",
        )
    }
}
