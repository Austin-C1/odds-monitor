package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import java.math.BigDecimal

data class PinnacleFootballMatch(
    val sourceMatchId: String,
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Long?,
    val isLive: Boolean,
    val handicaps: List<PinnacleHandicapMarket>,
    val totals: List<PinnacleTotalMarket>,
    val moneyline: PinnacleMoneylineMarket?,
    val rawPayload: Map<String, Any?>
)

data class PinnacleHandicapMarket(
    val line: String,
    val homeOdds: BigDecimal,
    val awayOdds: BigDecimal
)

data class PinnacleTotalMarket(
    val line: String,
    val overOdds: BigDecimal,
    val underOdds: BigDecimal
)

data class PinnacleMoneylineMarket(
    val homeOdds: BigDecimal,
    val drawOdds: BigDecimal,
    val awayOdds: BigDecimal
)

data class PinnacleMappedOddsRow(
    val platformMatchId: Long,
    val marketType: String,
    val lineValue: String?,
    val selectionName: String,
    val oddsValue: BigDecimal,
    val capturedAt: Long,
    val rawPayload: Map<String, Any?>
)

data class PinnacleCollectionResult(
    val status: String,
    val message: String?,
    val recordsCount: Int
)

class PinnacleCollectionException(
    val status: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
