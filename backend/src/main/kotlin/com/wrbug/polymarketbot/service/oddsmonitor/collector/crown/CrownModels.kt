package com.wrbug.polymarketbot.service.oddsmonitor.collector.crown

import java.math.BigDecimal

data class CrownLoginResponse(
    val status: String,
    val uid: String?,
    val messageCode: String?,
    val message: String?,
    val balance: BigDecimal? = null
)

data class CrownAccountCheckResult(
    val status: String,
    val balance: BigDecimal?,
    val message: String?,
    val checkedAt: Long = System.currentTimeMillis()
)

data class CrownSession(
    val uid: String,
    val cookies: Map<String, String>,
    val username: String? = null,
    val baseUrl: String? = null,
    val savedAt: Long = System.currentTimeMillis()
)

data class CrownFetchResult(
    val matches: List<CrownFootballMatch>,
    val session: CrownSession
)

interface CrownMatchGateway {
    fun login(config: com.wrbug.polymarketbot.entity.OddsDataSourceConfig): CrownSession
    fun fetchMatchesWithSession(
        config: com.wrbug.polymarketbot.entity.OddsDataSourceConfig,
        session: CrownSession
    ): CrownFetchResult
}

data class CrownGameListItem(
    val lid: String,
    val detailId: String,
    val ecid: String?,
    val isLive: Boolean,
    val isRb: String?,
    val elapsedMinutes: Int?
)

data class CrownFootballMatch(
    val sourceMatchId: String,
    val leagueName: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Long?,
    val isLive: Boolean,
    val handicaps: List<CrownHandicapMarket>,
    val totals: List<CrownTotalMarket>,
    val moneyline: CrownMoneylineMarket?,
    val rawPayload: Map<String, Any?>
)

data class CrownHandicapMarket(
    val line: String,
    val homeOdds: BigDecimal,
    val awayOdds: BigDecimal
)

data class CrownTotalMarket(
    val line: String,
    val overOdds: BigDecimal,
    val underOdds: BigDecimal
)

data class CrownMoneylineMarket(
    val homeOdds: BigDecimal,
    val drawOdds: BigDecimal,
    val awayOdds: BigDecimal
)

data class CrownMappedOddsRow(
    val platformMatchId: Long,
    val marketType: String,
    val lineValue: String?,
    val selectionName: String,
    val oddsValue: BigDecimal,
    val capturedAt: Long,
    val rawPayload: Map<String, Any?>
)

data class CrownCollectionResult(
    val status: String,
    val message: String?,
    val recordsCount: Int
)

class CrownCollectionException(
    val status: String,
    override val message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
