package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import java.math.BigDecimal

class OddsNotificationEligibilityService(
    private val marketRepository: OddsMarketRepository,
    private val snapshotRepository: OddsSnapshotRepository
) {
    fun shouldProcessSource(match: OddsPlatformMatch, expectedSources: List<String>): Boolean {
        return match.sourceKey in expectedSources
    }

    fun shouldProcessFootballMatch(match: OddsPlatformMatch): Boolean {
        return !OddsFootballMatchFilter.shouldIgnore(match.rawLeagueName, match.rawHomeTeam, match.rawAwayTeam)
    }

    fun configsQualifiedByPhase(
        configs: List<NotificationConfigDto>,
        matchPhase: OddsMonitorMatchPhase,
        startTime: Long?,
        now: Long,
        liveObservationMinutes: Int?
    ): List<NotificationConfigDto> {
        return configs.filter {
            telegramConfigMatchesOddsMonitorPhase(it, matchPhase, startTime, now, liveObservationMinutes)
        }
    }

    fun configsQualifiedByLeague(
        configs: List<NotificationConfigDto>,
        leagueMatched: Boolean
    ): List<NotificationConfigDto> {
        return if (leagueMatched) {
            configs
        } else {
            configs.filter { isTelegramTestModeEnabled(it) }
        }
    }

    fun isChangeQualifiedByFinalCombinedWater(
        matchId: Long,
        marketKey: OddsChangeNotificationMarketKey,
        change: OddsChangeNotificationItem,
        configs: List<NotificationConfigDto>
    ): Boolean {
        val (testModeConfigs, normalConfigs) = configs.partition { isTelegramTestModeEnabled(it) }
        if (normalConfigs.isEmpty()) {
            return testModeConfigs.isNotEmpty()
        }
        if (activeCombinedWaterLimits(marketKey.marketType, normalConfigs).isEmpty()) {
            return true
        }
        if (configsWithoutCombinedWaterLimit(marketKey.marketType, normalConfigs).isNotEmpty()) {
            return true
        }
        val pairSelectionName = pairSelectionName(marketKey.marketType, marketKey.selectionName)
            ?: return testModeConfigs.isNotEmpty()
        val pairMarket = marketRepository.findTopByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionNameOrderByUpdatedAtDesc(
            matchId = matchId,
            sourceKey = change.sourceKey,
            marketType = marketKey.marketType,
            lineValue = change.lineValue ?: marketKey.lineValue,
            selectionName = pairSelectionName
        ) ?: return testModeConfigs.isNotEmpty()
        val pairMarketId = pairMarket.id ?: return testModeConfigs.isNotEmpty()
        val pairOdds = snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(pairMarketId)?.oddsValue
            ?: return testModeConfigs.isNotEmpty()

        val normalQualified = normalConfigs.any { config ->
            !shouldSuppressOddsChangeByCombinedWater(
                marketType = marketKey.marketType,
                currentOdds = asianWaterOdds(change.sourceKey, marketKey.marketType, change.currentOdds),
                pairedOdds = asianWaterOdds(pairMarket.sourceKey, pairMarket.marketType, pairOdds),
                configs = listOf(config)
            )
        }
        return normalQualified || testModeConfigs.isNotEmpty()
    }

    private fun filterConfigsByOddsMove(
        market: OddsMarket,
        previousOdds: BigDecimal?,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>
    ): List<NotificationConfigDto> {
        return configs.filter { config ->
            !shouldSuppressOddsChangeByMove(market.marketType, previousOdds, currentOdds, listOf(config))
        }
    }

    private fun filterConfigsByCombinedWater(
        market: OddsMarket,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>
    ): List<NotificationConfigDto> {
        if (activeCombinedWaterLimits(market.marketType, configs).isEmpty()) {
            return configs
        }
        val pairSelectionName = pairSelectionName(market.marketType, market.selectionName)
            ?: return configsWithoutCombinedWaterLimit(market.marketType, configs)

        val pairMarket = marketRepository.findTopByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionNameOrderByUpdatedAtDesc(
            matchId = market.matchId,
            sourceKey = market.sourceKey,
            marketType = market.marketType,
            lineValue = market.lineValue,
            selectionName = pairSelectionName
        ) ?: return configsWithoutCombinedWaterLimit(market.marketType, configs)
        val pairMarketId = pairMarket.id ?: return configsWithoutCombinedWaterLimit(market.marketType, configs)
        val pairOdds = snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(pairMarketId)?.oddsValue
            ?: return configsWithoutCombinedWaterLimit(market.marketType, configs)

        return configs.filter { config ->
            !shouldSuppressOddsChangeByCombinedWater(
                marketType = market.marketType,
                currentOdds = asianWaterOdds(market.sourceKey, market.marketType, currentOdds),
                pairedOdds = asianWaterOdds(pairMarket.sourceKey, pairMarket.marketType, pairOdds),
                configs = listOf(config)
            )
        }
    }

    private fun configsWithoutCombinedWaterLimit(
        marketType: String,
        configs: List<NotificationConfigDto>
    ): List<NotificationConfigDto> {
        return configs.filter { config -> activeCombinedWaterLimits(marketType, listOf(config)).isEmpty() }
    }

    fun configsQualifiedBySelectedLeagueRules(
        market: OddsMarket,
        previousOdds: BigDecimal?,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>
    ): List<NotificationConfigDto> {
        val (testModeConfigs, normalConfigs) = configs.partition { isTelegramTestModeEnabled(it) }
        return (testModeConfigs + normalConfigs
            .let { filterConfigsByOddsMove(market, previousOdds, currentOdds, it) }
            .let { filterConfigsByCombinedWater(market, currentOdds, it) })
            .distinctBy { config -> config.id?.toString() ?: config.name }
    }

    private fun configsQualifiedByCombinedWater(
        market: OddsMarket,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>
    ): List<NotificationConfigDto> {
        val pairSelectionName = pairSelectionName(market.marketType, market.selectionName) ?: return emptyList()
        if (activeCombinedWaterLimits(market.marketType, configs).isEmpty()) {
            return emptyList()
        }

        val pairMarket = marketRepository.findTopByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionNameOrderByUpdatedAtDesc(
            matchId = market.matchId,
            sourceKey = market.sourceKey,
            marketType = market.marketType,
            lineValue = market.lineValue,
            selectionName = pairSelectionName
        ) ?: return emptyList()
        val pairMarketId = pairMarket.id ?: return emptyList()
        val pairOdds = snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(pairMarketId)?.oddsValue
            ?: return emptyList()

        return configs.filter { config ->
            !shouldSuppressOddsChangeByCombinedWater(
                marketType = market.marketType,
                currentOdds = asianWaterOdds(market.sourceKey, market.marketType, currentOdds),
                pairedOdds = asianWaterOdds(pairMarket.sourceKey, pairMarket.marketType, pairOdds),
                configs = listOf(config)
            )
        }
    }
}

fun hasOddsChanged(previousOdds: BigDecimal?, currentOdds: BigDecimal): Boolean {
    if (previousOdds == null) return false
    return previousOdds.compareTo(currentOdds) != 0
}

fun shouldSuppressOddsChangeByCombinedWater(
    marketType: String,
    currentOdds: BigDecimal,
    pairedOdds: BigDecimal?,
    configs: List<NotificationConfigDto>
): Boolean {
    val limits = activeCombinedWaterLimits(marketType, configs)
    if (limits.isEmpty()) {
        return false
    }
    val combinedWater = pairedOdds?.let { currentOdds + it } ?: return true
    return limits.none { combinedWater >= it }
}

fun shouldSuppressOddsChangeByMove(
    marketType: String,
    previousOdds: BigDecimal?,
    currentOdds: BigDecimal,
    configs: List<NotificationConfigDto>
): Boolean {
    val limits = activeOddsMoveLimits(marketType, configs)
    if (limits.isEmpty()) {
        return false
    }
    val previous = previousOdds ?: return true
    val move = currentOdds.subtract(previous).abs()
    return limits.none { move >= it }
}

fun activeMonitorTelegramConfigs(configs: List<NotificationConfigDto>): List<NotificationConfigDto> {
    return configs.filter { config ->
        config.enabled && (config.config as? NotificationConfigData.Telegram)?.data?.monitorModeEnabled == true
    }
}

internal fun isTelegramTestModeEnabled(config: NotificationConfigDto): Boolean {
    return config.enabled && (config.config as? NotificationConfigData.Telegram)?.data?.testModeEnabled == true
}

private fun mergeNotificationConfigs(
    existing: List<NotificationConfigDto>,
    incoming: List<NotificationConfigDto>
): List<NotificationConfigDto> {
    return (existing + incoming).distinctBy { config -> config.id?.toString() ?: config.name }
}

private fun mergeNotificationPhase(
    existing: OddsMonitorMatchPhase,
    incoming: OddsMonitorMatchPhase
): OddsMonitorMatchPhase {
    return if (existing == OddsMonitorMatchPhase.LIVE || incoming == OddsMonitorMatchPhase.LIVE) {
        OddsMonitorMatchPhase.LIVE
    } else {
        OddsMonitorMatchPhase.PREMATCH
    }
}

private fun activeCombinedWaterLimits(marketType: String, configs: List<NotificationConfigDto>): List<BigDecimal> {
    return configs.mapNotNull { config ->
        if (!config.enabled) {
            return@mapNotNull null
        }
        val telegram = config.config as? NotificationConfigData.Telegram ?: return@mapNotNull null
        if (!telegram.data.monitorModeEnabled) {
            return@mapNotNull null
        }
        val rawLimit = when (marketType.lowercase()) {
            "handicap" -> telegram.data.handicapCombinedWaterMin
            "total" -> telegram.data.totalCombinedWaterMin
            else -> return@mapNotNull null
        }
        rawLimit?.trim()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    }
}

private fun activeOddsMoveLimits(marketType: String, configs: List<NotificationConfigDto>): List<BigDecimal> {
    return configs.mapNotNull { config ->
        if (!config.enabled) {
            return@mapNotNull null
        }
        val telegram = config.config as? NotificationConfigData.Telegram ?: return@mapNotNull null
        if (!telegram.data.monitorModeEnabled) {
            return@mapNotNull null
        }
        val rawLimit = when (marketType.lowercase()) {
            "handicap" -> telegram.data.handicapOddsMoveMin
            "total" -> telegram.data.totalOddsMoveMin
            "moneyline" -> telegram.data.moneylineOddsMoveMin
            else -> return@mapNotNull null
        }
        rawLimit?.trim()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    }
}

private fun pairSelectionName(marketType: String, selectionName: String): String? {
    return when (marketType.lowercase()) {
        "handicap" -> when (selectionName.lowercase()) {
            "home" -> "away"
            "away" -> "home"
            else -> null
        }
        "total" -> when (selectionName.lowercase()) {
            "over" -> "under"
            "under" -> "over"
            else -> null
        }
        else -> null
    }
}
