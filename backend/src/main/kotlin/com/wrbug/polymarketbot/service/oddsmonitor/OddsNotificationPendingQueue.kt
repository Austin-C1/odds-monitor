package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class OddsNotificationPendingQueue(
    private val expectedSourceKeys: List<String> = listOf("crown")
) {
    private val pendingAlerts = ConcurrentHashMap<OddsChangeNotificationKey, PendingOddsChangeNotification>()

    fun enqueueMergedNotification(
        match: OddsPlatformMatch,
        standardMatch: OddsMatch?,
        matchName: String,
        leagueName: String,
        matchId: Long,
        market: OddsMarket,
        previousOdds: BigDecimal,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>,
        applyOddsMoveFilter: Boolean = true,
        matchPhase: OddsMonitorMatchPhase = OddsMonitorMatchPhase.PREMATCH,
        liveContext: OddsLiveMatchContext? = null
    ): OddsChangeNotificationKey {
        val candidate = notificationMergeCandidate(match, standardMatch)
        val key = notificationMergeKey(matchId, candidate)
        val marketKey = OddsChangeNotificationMarketKey(
            marketType = market.marketType,
            lineValue = market.normalizedLineValue(),
            selectionName = market.selectionName
        )
        pendingAlerts.compute(key) { _, existing ->
            val base = existing ?: PendingOddsChangeNotification(
                matchName = matchName,
                leagueName = leagueName,
                matchId = matchId,
                candidate = candidate,
                configs = configs,
                markets = linkedMapOf()
            )
            val updatedBase = base.copy(
                matchName = bestNotificationText(base.matchName, matchName),
                leagueName = bestNotificationText(base.leagueName, leagueName),
                configs = mergeNotificationConfigs(base.configs, configs),
                matchPhase = mergeNotificationPhase(base.matchPhase, matchPhase),
                liveContext = mergeLiveContext(base.liveContext, liveContext)
            )
            val pendingMarket = updatedBase.markets.getOrPut(marketKey) {
                PendingOddsChangeMarketNotification(
                    marketType = market.marketType,
                    marketLabel = market.displayLabel(),
                    changes = linkedMapOf()
                )
            }
            val existingChange = pendingMarket.changes[market.sourceKey]
            val updatedChange = if (existingChange == null) {
                OddsChangeNotificationItem(market.sourceKey, previousOdds, currentOdds, market.lineValue)
            } else {
                existingChange.copy(currentOdds = currentOdds)
            }
            if (
                hasOddsChanged(updatedChange.previousOdds, updatedChange.currentOdds) &&
                (!applyOddsMoveFilter || !shouldSuppressOddsChangeByMove(
                    marketType = market.marketType,
                    previousOdds = updatedChange.previousOdds,
                    currentOdds = updatedChange.currentOdds,
                    configs = configs
                ))
            ) {
                pendingMarket.changes[market.sourceKey] = updatedChange
            } else {
                pendingMarket.changes.remove(market.sourceKey)
            }
            if (pendingMarket.changes.isEmpty()) {
                updatedBase.markets.remove(marketKey)
            }
            updatedBase.takeIf { it.markets.isNotEmpty() }
        }
        return key
    }

    fun remove(key: OddsChangeNotificationKey): PendingOddsChangeNotification? {
        return pendingAlerts.remove(key)
    }

    fun clearSourceState(sourceKey: String) {
        val normalizedSourceKey = sourceKey.trim().takeIf { it.isNotBlank() } ?: return
        pendingAlerts.keys.forEach { key ->
            pendingAlerts.computeIfPresent(key) { _, pending ->
                pending.markets.values.forEach { market ->
                    market.changes.remove(normalizedSourceKey)
                }
                pending.markets.entries.removeIf { (_, market) -> market.changes.isEmpty() }
                pending.takeIf { it.markets.isNotEmpty() }
            }
        }
    }

    private fun mergeLiveContext(
        existing: OddsLiveMatchContext?,
        incoming: OddsLiveMatchContext?
    ): OddsLiveMatchContext? {
        if (existing == null) {
            return incoming
        }
        if (incoming == null) {
            return existing
        }
        return OddsLiveMatchContext(
            elapsedMinutes = incoming.elapsedMinutes ?: existing.elapsedMinutes,
            scoreText = incoming.scoreText ?: existing.scoreText
        )
    }

    private fun notificationMergeKey(
        matchId: Long,
        incomingCandidate: OddsMatchCandidate
    ): OddsChangeNotificationKey {
        val standardKey = OddsChangeNotificationKey("standard:$matchId")
        if (pendingAlerts.containsKey(standardKey)) {
            return standardKey
        }
        pendingAlerts.forEach { (existingKey, pending) ->
            val score = OddsMatchMatcher.score(pending.candidate, incomingCandidate)
            if (OddsMatchMatcher.shouldMerge(score)) {
                return existingKey
            }
        }
        return standardKey
    }
}

data class OddsChangeNotificationItem(
    val sourceKey: String,
    val previousOdds: BigDecimal,
    val currentOdds: BigDecimal,
    val lineValue: String? = null
)

data class OddsChangeNotificationMarketItem(
    val marketType: String? = null,
    val marketLabel: String,
    val changes: List<OddsChangeNotificationItem>
)

data class OddsLiveMatchContext(
    val elapsedMinutes: Int? = null,
    val scoreText: String? = null
)

data class OddsChangeNotificationKey(
    val value: String
)

data class OddsChangeNotificationMarketKey(
    val marketType: String,
    val lineValue: String?,
    val selectionName: String
)

data class PendingOddsChangeNotification(
    val matchName: String,
    val leagueName: String,
    val matchId: Long,
    val candidate: OddsMatchCandidate,
    val configs: List<NotificationConfigDto>,
    val markets: LinkedHashMap<OddsChangeNotificationMarketKey, PendingOddsChangeMarketNotification>,
    val matchPhase: OddsMonitorMatchPhase = OddsMonitorMatchPhase.PREMATCH,
    val liveContext: OddsLiveMatchContext? = null
)

data class PendingOddsChangeMarketNotification(
    val marketType: String,
    val marketLabel: String,
    val changes: LinkedHashMap<String, OddsChangeNotificationItem>
)

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
