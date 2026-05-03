package com.wrbug.polymarketbot.dto

data class CryptoTailManualOrderRequest(
    val strategyId: Long = 0L,
    val periodStartUnix: Long = 0L,
    val direction: String = "UP",
    val price: String = "0",
    val size: String = "1",
    val marketTitle: String = "",
    /** Token IDs */
    val tokenIds: List<String> = emptyList()
)
