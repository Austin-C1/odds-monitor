package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class OddsNotificationMarketStateService(
    private val expectedSourceKeys: List<String> = listOf("crown")
) {
    private val marketStates = ConcurrentHashMap<OddsMarketStateKey, Set<String>>()
    private val oddsBaselineResets = ConcurrentHashMap<OddsMarketStateKey, OddsBaselineReset>()

    fun notifyMarketState(
        match: OddsPlatformMatch,
        standardMatch: OddsMatch,
        marketType: String,
        currentLines: Set<String>
    ) {
        if (match.sourceKey !in expectedSourceKeys) {
            return
        }
        if (OddsFootballMatchFilter.shouldIgnore(match.rawLeagueName, match.rawHomeTeam, match.rawAwayTeam)) {
            return
        }
        val standardMatchId = standardMatch.id ?: return
        val normalizedLines = currentLines
            .mapNotNull { OddsLineDisplayFormatter.format(marketType, it)?.takeIf { line -> line.isNotBlank() } }
            .toSortedSet()
        val stateKey = OddsMarketStateKey(
            matchId = standardMatchId,
            sourceKey = match.sourceKey,
            marketType = marketType
        )
        val previousLines = marketStates.put(stateKey, normalizedLines) ?: return
        if (previousLines == normalizedLines) {
            return
        }
        updateOddsBaselineReset(stateKey, normalizedLines)
    }

    fun shouldResetOddsBaselineAfterLineChange(market: OddsMarket): Boolean {
        val stateKey = OddsMarketStateKey(
            matchId = market.matchId,
            sourceKey = market.sourceKey,
            marketType = market.marketType
        )
        val reset = oddsBaselineResets[stateKey] ?: return false
        val normalizedLine = OddsLineDisplayFormatter.format(market.marketType, market.lineValue) ?: return false
        if (normalizedLine !in reset.currentLines) {
            return false
        }
        val marketKey = OddsNotificationStateMarketKey(
            marketType = market.marketType,
            lineValue = normalizedLine,
            selectionName = market.selectionName
        )
        return reset.suppressedMarkets.add(marketKey)
    }

    fun clearSourceState(sourceKey: String) {
        val normalizedSourceKey = sourceKey.trim().takeIf { it.isNotBlank() } ?: return
        marketStates.keys.removeIf { key -> key.sourceKey == normalizedSourceKey }
        oddsBaselineResets.keys.removeIf { key -> key.sourceKey == normalizedSourceKey }
    }

    private fun updateOddsBaselineReset(stateKey: OddsMarketStateKey, normalizedLines: Set<String>) {
        if (normalizedLines.isEmpty()) {
            oddsBaselineResets.remove(stateKey)
            return
        }
        oddsBaselineResets[stateKey] = OddsBaselineReset(
            currentLines = normalizedLines,
            suppressedMarkets = ConcurrentHashMap.newKeySet()
        )
    }
}

private data class OddsMarketStateKey(
    val matchId: Long,
    val sourceKey: String,
    val marketType: String
)

private data class OddsNotificationStateMarketKey(
    val marketType: String,
    val lineValue: String?,
    val selectionName: String
)

private data class OddsBaselineReset(
    val currentLines: Set<String>,
    val suppressedMarkets: MutableSet<OddsNotificationStateMarketKey>
)
