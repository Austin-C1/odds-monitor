package com.wrbug.polymarketbot.dto

import java.math.BigDecimal

data class TradeData(
    val tradeId: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val side: String,
    val outcome: String,
    val outcomeIndex: Int?,
    val price: BigDecimal,
    val size: BigDecimal,
    val amount: BigDecimal,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TradeData) return false
        return tradeId == other.tradeId
    }

    override fun hashCode(): Int {
        return tradeId.hashCode()
    }
}

