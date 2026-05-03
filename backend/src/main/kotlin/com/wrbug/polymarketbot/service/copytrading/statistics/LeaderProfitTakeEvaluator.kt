package com.wrbug.polymarketbot.service.copytrading.statistics

import org.springframework.stereotype.Component
import java.math.BigDecimal

data class LeaderProfitTakeDecision(
    val shouldSell: Boolean,
    val sellQuantity: BigDecimal
)

@Component
class LeaderProfitTakeEvaluator {

    fun evaluate(
        enabled: Boolean,
        triggerPrice: BigDecimal?,
        currentPrice: BigDecimal,
        remainingQuantity: BigDecimal
    ): LeaderProfitTakeDecision {
        if (!enabled || triggerPrice == null) {
            return LeaderProfitTakeDecision(false, BigDecimal.ZERO)
        }
        if (currentPrice < triggerPrice || remainingQuantity <= BigDecimal.ZERO) {
            return LeaderProfitTakeDecision(false, BigDecimal.ZERO)
        }
        return LeaderProfitTakeDecision(true, remainingQuantity)
    }
}
