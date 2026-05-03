package com.wrbug.polymarketbot.dto

data class CryptoTailManualOrderResponse(
    val success: Boolean = false,
    val orderId: String? = null,
    val message: String = "",
    val orderDetails: ManualOrderDetails? = null
)

data class ManualOrderDetails(
    val strategyId: Long = 0L,
    val direction: String = "",
    val price: String = "",
    val size: String = "",
    val totalAmount: String = ""
)
