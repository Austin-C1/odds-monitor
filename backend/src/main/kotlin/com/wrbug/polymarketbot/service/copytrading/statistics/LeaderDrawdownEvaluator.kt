package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.gte
import com.wrbug.polymarketbot.util.multi
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

data class LeaderCurvePoint(
    val timestamp: Long,
    val cumulativePnl: BigDecimal
)

data class LeaderDrawdownEvaluation(
    val shouldPause: Boolean,
    val peakPnl: BigDecimal,
    val currentPnl: BigDecimal,
    val drawdownPercent: BigDecimal
)

@Service
class LeaderDrawdownEvaluator {

    fun evaluate(
        points: List<LeaderCurvePoint>,
        thresholdPercent: BigDecimal
    ): LeaderDrawdownEvaluation {
        if (points.isEmpty()) {
            return LeaderDrawdownEvaluation(
                shouldPause = false,
                peakPnl = BigDecimal.ZERO,
                currentPnl = BigDecimal.ZERO,
                drawdownPercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            )
        }

        val orderedPoints = points.sortedBy { it.timestamp }
        val currentPnl = orderedPoints.last().cumulativePnl
        var peakPnl = BigDecimal.ZERO

        orderedPoints.forEach { point ->
            if (point.cumulativePnl.gt(peakPnl)) {
                peakPnl = point.cumulativePnl
            }
        }

        if (!peakPnl.gt(BigDecimal.ZERO)) {
            return LeaderDrawdownEvaluation(
                shouldPause = false,
                peakPnl = BigDecimal.ZERO,
                currentPnl = currentPnl,
                drawdownPercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            )
        }

        val drawdownPercent = peakPnl
            .subtract(currentPnl)
            .div(peakPnl, 8, RoundingMode.HALF_UP)
            .multi(100)
            .setScale(2, RoundingMode.HALF_UP)

        return LeaderDrawdownEvaluation(
            shouldPause = drawdownPercent.gte(thresholdPercent),
            peakPnl = peakPnl,
            currentPnl = currentPnl,
            drawdownPercent = if (drawdownPercent < BigDecimal.ZERO) {
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
            } else {
                drawdownPercent
            }
        )
    }
}
