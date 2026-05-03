package com.wrbug.polymarketbot.dto

data class CryptoTailMonitorInitRequest(
    val strategyId: Long = 0L,
    val periodStartUnix: Long? = null
)

data class CryptoTailMonitorInitResponse(
    val strategyId: Long = 0L,
    val name: String = "",
    val accountId: Long = 0L,
    val accountName: String = "",
    val marketSlugPrefix: String = "",
    val marketTitle: String = "",
    val intervalSeconds: Int = 300,
    val periodStartUnix: Long = 0L,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String = "1",
    val minSpreadMode: String = "NONE",
    val spreadDirection: String = "MIN",
    val minSpreadValue: String? = null,
    val autoMinSpreadUp: String? = null,
    val autoMinSpreadDown: String? = null,
    val openPriceBtc: String? = null,
    /** Up tokenId */
    val tokenIdUp: String? = null,
    /** Down tokenId */
    val tokenIdDown: String? = null,
    val currentTimestamp: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val amountMode: String? = null,
    val amountValue: String? = null
)

data class CryptoTailMonitorPushData(
    val strategyId: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val periodStartUnix: Long = 0L,
    val marketTitle: String = "",
    val currentPriceUp: String? = null,
    val currentPriceDown: String? = null,
    val spreadUp: String? = null,
    val spreadDown: String? = null,
    val minSpreadLineUp: String? = null,
    val minSpreadLineDown: String? = null,
    val openPriceBtc: String? = null,
    val currentPriceBtc: String? = null,
    val spreadBtc: String? = null,
    val remainingSeconds: Int = 0,
    val inTimeWindow: Boolean = false,
    val inPriceRangeUp: Boolean = false,
    val inPriceRangeDown: Boolean = false,
    val triggered: Boolean = false,
    val triggerDirection: String? = null,
    val periodEnded: Boolean = false
)
