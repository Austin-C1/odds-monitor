package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import java.math.BigDecimal
import java.math.RoundingMode

internal data class CopyTradingCurrentMetrics(
    val currentPositionValue: BigDecimal = BigDecimal.ZERO,
    val unrealizedPnl: BigDecimal = BigDecimal.ZERO,
)

internal fun resolveCopyTradingCurrentMetrics(
    copyTradings: List<CopyTrading>,
    activeOrdersByCopyTradingId: Map<Long, List<CopyOrderTracking>>,
    currentPositionsByAccount: Map<Long, List<AccountPositionDto>>,
): Map<Long, CopyTradingCurrentMetrics> {
    if (copyTradings.isEmpty() || activeOrdersByCopyTradingId.isEmpty() || currentPositionsByAccount.isEmpty()) {
        return emptyMap()
    }

    val metricsByCopyTradingId = mutableMapOf<Long, CopyTradingCurrentMetrics>()
    val copyTradingsByAccount = copyTradings
        .filter { it.id != null }
        .groupBy { it.accountId }

    copyTradingsByAccount.forEach { (accountId, accountCopyTradings) ->
        val currentPositions = currentPositionsByAccount[accountId].orEmpty().filter { it.isCurrent }
        if (currentPositions.isEmpty()) {
            return@forEach
        }

        currentPositions.forEach { position ->
            val currentValue = position.currentValue.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val unrealizedPnl = position.pnl.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (currentValue.compareTo(BigDecimal.ZERO) == 0 && unrealizedPnl.compareTo(BigDecimal.ZERO) == 0) {
                return@forEach
            }

            val matchingCostBasis = accountCopyTradings.mapNotNull { copyTrading ->
                val copyTradingId = copyTrading.id ?: return@mapNotNull null
                val costBasis = activeOrdersByCopyTradingId[copyTradingId].orEmpty()
                    .asSequence()
                    .filter { order ->
                        order.remainingQuantity.compareTo(BigDecimal.ZERO) > 0 &&
                            order.status != "pending_fill" &&
                            orderMatchesPosition(order, position)
                    }
                    .map { order -> order.remainingQuantity.multiply(order.price) }
                    .fold(BigDecimal.ZERO, BigDecimal::add)

                if (costBasis.compareTo(BigDecimal.ZERO) > 0) {
                    copyTradingId to costBasis
                } else {
                    null
                }
            }

            val totalCostBasis = matchingCostBasis.fold(BigDecimal.ZERO) { sum, (_, costBasis) ->
                sum.add(costBasis)
            }
            if (totalCostBasis.compareTo(BigDecimal.ZERO) <= 0) {
                return@forEach
            }

            matchingCostBasis.forEach { (copyTradingId, costBasis) ->
                val ratio = costBasis.divide(totalCostBasis, 12, RoundingMode.HALF_UP)
                val existing = metricsByCopyTradingId[copyTradingId] ?: CopyTradingCurrentMetrics()
                metricsByCopyTradingId[copyTradingId] = existing.copy(
                    currentPositionValue = existing.currentPositionValue.add(currentValue.multiply(ratio)),
                    unrealizedPnl = existing.unrealizedPnl.add(unrealizedPnl.multiply(ratio)),
                )
            }
        }
    }

    return metricsByCopyTradingId
}

private fun orderMatchesPosition(
    order: CopyOrderTracking,
    position: AccountPositionDto,
): Boolean {
    if (!order.marketId.equals(position.marketId, ignoreCase = true)) {
        return false
    }

    val positionOutcomeIndex = position.outcomeIndex
    if (order.outcomeIndex != null && positionOutcomeIndex != null) {
        return order.outcomeIndex == positionOutcomeIndex
    }

    if (positionOutcomeIndex != null && order.side == positionOutcomeIndex.toString()) {
        return true
    }

    return order.side.equals(position.side, ignoreCase = true)
}
