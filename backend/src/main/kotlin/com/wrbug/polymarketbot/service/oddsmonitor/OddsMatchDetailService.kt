package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsMetricDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDetailDto
import com.wrbug.polymarketbot.dto.OddsMonitorMatchDto
import com.wrbug.polymarketbot.dto.OddsPlatformMatchDto
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class OddsMatchDetailService(
    private val marketRepository: OddsMarketRepository? = null,
    private val snapshotRepository: OddsSnapshotRepository? = null,
    private val displayMapper: OddsMonitorDisplayMapper = OddsMonitorDisplayMapper()
) {
    fun buildDetail(
        match: OddsMonitorMatchDto,
        sourceMatches: Map<String, OddsPlatformMatch>
    ): OddsMonitorMatchDetailDto {
        val marketRepo = marketRepository ?: return OddsMonitorMatchDetailDto(match, emptyList(), emptyList())
        val snapshotRepo = snapshotRepository ?: return OddsMonitorMatchDetailDto(match, emptyList(), emptyList())
        val metrics = match.matchedPlatforms.flatMap { sourceKey ->
            val platformMatchId = sourceMatches[sourceKey]?.id ?: return@flatMap emptyList()
            val markets = marketRepo.findByMatchIdInAndSourceKey(listOf(match.id), sourceKey)
                .ifEmpty { marketRepo.findByMatchIdInAndSourceKey(listOf(platformMatchId), sourceKey) }
                .ifEmpty { marketRepo.findByPlatformMatchIdInAndSourceKey(listOf(platformMatchId), sourceKey) }
                .filter { market -> shouldDisplayMarket(market.marketType) }
            markets.mapNotNull { market ->
                val snapshot = market.id?.let { snapshotRepo.findTop1ByMarketIdOrderByCapturedAtDesc(it) }
                snapshot?.let {
                    val line = OddsLineDisplayFormatter.format(market.marketType, market.lineValue)
                        ?.let { value -> " $value" }
                        .orEmpty()
                    OddsMetricDto(
                        label = "${market.marketType} ${market.selectionName}$line",
                        value = it.oddsValue.stripTrailingZeros().toPlainString(),
                        trend = "",
                        sourceKey = sourceKey
                    )
                }
            }
        }
        return OddsMonitorMatchDetailDto(
            match = match,
            metrics = metrics,
            oddsHistory = emptyList(),
            platformMatches = sourceMatches.values.map { it.toDto() }
        )
    }

    private fun shouldDisplayMarket(marketType: String): Boolean {
        return marketType.lowercase(Locale.ROOT) != "moneyline"
    }

    private fun OddsPlatformMatch.toDto(): OddsPlatformMatchDto {
        return OddsPlatformMatchDto(
            sourceKey = sourceKey,
            sourceMatchId = sourceMatchId,
            rawLeagueName = displayMapper.leagueName(rawLeagueName),
            rawHomeTeam = displayMapper.teamName(rawHomeTeam),
            rawAwayTeam = displayMapper.teamName(rawAwayTeam),
            rawStartTime = rawStartTime
        )
    }
}
