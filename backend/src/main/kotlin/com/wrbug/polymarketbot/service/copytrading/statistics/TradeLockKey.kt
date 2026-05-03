package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.TradeResponse

internal fun buildTradeProcessingKey(leaderId: Long, trade: TradeResponse): String {
    return when (trade.side.uppercase()) {
        "SELL" -> {
            val outcomeKey = trade.outcomeIndex?.toString() ?: "unknown"
            "${leaderId}_SELL_${trade.market}_${outcomeKey}"
        }
        else -> "${leaderId}_${trade.id}"
    }
}
