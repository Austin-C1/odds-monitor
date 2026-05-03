package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.service.common.BlockchainService
import java.math.BigDecimal

internal suspend fun resolveLeaderCurrentPositionValue(
    blockchainService: BlockchainService,
    leaderAddress: String?,
    marketId: String?,
    outcomeIndex: Int? = null,
    outcome: String? = null
): String? {
    if (leaderAddress.isNullOrBlank() || marketId.isNullOrBlank()) {
        return null
    }

    val positions = blockchainService.getPositions(leaderAddress).getOrNull() ?: return null
    return extractLeaderCurrentPositionValue(
        positions = positions,
        marketId = marketId,
        outcomeIndex = outcomeIndex,
        outcome = outcome
    )
}

internal fun extractLeaderCurrentPositionValue(
    positions: List<PositionResponse>,
    marketId: String?,
    outcomeIndex: Int? = null,
    outcome: String? = null
): String? {
    if (marketId.isNullOrBlank()) {
        return null
    }

    val totalByIndex = outcomeIndex?.let { index ->
        positions.sumCurrentPositionValue { position ->
            position.conditionId.equals(marketId, ignoreCase = true) && position.outcomeIndex == index
        }
    }
    if (totalByIndex != null && totalByIndex > BigDecimal.ZERO) {
        return totalByIndex.stripTrailingZeros().toPlainString()
    }

    val normalizedOutcome = outcome?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val totalByOutcome = positions.sumCurrentPositionValue { position ->
        position.conditionId.equals(marketId, ignoreCase = true) &&
            position.outcome?.trim()?.equals(normalizedOutcome, ignoreCase = true) == true
    }
    return totalByOutcome.takeIf { it > BigDecimal.ZERO }?.stripTrailingZeros()?.toPlainString()
}

private fun List<PositionResponse>.sumCurrentPositionValue(matches: (PositionResponse) -> Boolean): BigDecimal {
    return asSequence()
        .filter(matches)
        .mapNotNull { position -> position.currentValue?.takeIf { it > 0.0 }?.let(BigDecimal::valueOf) }
        .fold(BigDecimal.ZERO, BigDecimal::add)
}
