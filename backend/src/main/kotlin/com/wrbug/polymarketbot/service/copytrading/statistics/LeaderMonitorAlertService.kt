package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

internal data class LeaderMonitorPosition(
    val leaderId: Long,
    val leaderName: String,
    val marketId: String,
    val marketTitle: String,
    val marketLink: String,
    val outcomeIndex: Int,
    val outcome: String,
    val currentValue: BigDecimal,
    val avgPrice: BigDecimal,
    val leaderGroup: String?
)

internal data class SameSideMonitorAlert(
    val marketId: String,
    val marketTitle: String,
    val marketLink: String,
    val outcomeIndex: Int,
    val outcome: String,
    val sameSidePositionReport: String,
    val sameSideCount: Int,
    val leaderGroups: List<String?>,
    val fingerprint: String
)

internal data class OppositeMonitorAlert(
    val marketId: String,
    val marketTitle: String,
    val marketLink: String,
    val outcomeA: String,
    val sideAPositionReport: String,
    val outcomeB: String,
    val sideBPositionReport: String,
    val hedgePositionReport: String,
    val leaderGroups: List<String?>,
    val fingerprint: String
)

private data class OutcomeKey(
    val outcomeIndex: Int,
    val outcome: String
)

private fun escapeMonitorHtml(value: String): String {
    return value.replace("<", "&lt;").replace(">", "&gt;")
}

private fun formatMonitorDecimal(value: BigDecimal): String {
    val normalized = if (value.scale() > 4) {
        value.setScale(4, RoundingMode.DOWN).stripTrailingZeros()
    } else {
        value.stripTrailingZeros()
    }
    return normalized.toPlainString()
}

private fun buildHoldingReport(value: BigDecimal, avgPrice: BigDecimal): String {
    return "${formatMonitorDecimal(value)}u @ ${formatMonitorDecimal(avgPrice)}"
}

private fun buildMonitorPositionSummary(positions: List<LeaderMonitorPosition>): String {
    if (positions.isEmpty()) {
        return "无持仓"
    }

    return positions.groupBy { OutcomeKey(it.outcomeIndex, it.outcome) }
        .toSortedMap(compareBy<OutcomeKey> { it.outcomeIndex }.thenBy { it.outcome })
        .map { (key, items) ->
            val totalValue = items.fold(BigDecimal.ZERO) { acc, item -> acc + item.currentValue }
            "${key.outcome} ${formatMonitorDecimal(totalValue)}u"
        }
        .joinToString(" / ")
}

private fun buildSameSidePositionReport(positions: List<LeaderMonitorPosition>): String {
    return positions.sortedWith(compareByDescending<LeaderMonitorPosition> { it.currentValue }.thenBy { it.leaderName })
        .joinToString("\n") { position ->
            "• ${escapeMonitorHtml(position.leaderName)}｜持仓报告: <code>${buildHoldingReport(position.currentValue, position.avgPrice)}</code>"
        }
}

private fun buildHedgePositionReport(positions: List<LeaderMonitorPosition>): String {
    val grouped = positions.groupBy { it.leaderId }
        .mapValues { (_, items) ->
            items.sortedBy { it.outcomeIndex }
        }
        .filterValues { items -> items.map { it.outcomeIndex }.distinct().size >= 2 }

    if (grouped.isEmpty()) {
        return "无"
    }

    return grouped.values
        .sortedBy { items -> items.first().leaderName }
        .joinToString("\n") { items ->
            val leaderName = escapeMonitorHtml(items.first().leaderName)
            val outcomeReport = items.joinToString("｜") { item ->
                "${escapeMonitorHtml(item.outcome)}: <code>${buildHoldingReport(item.currentValue, item.avgPrice)}</code>"
            }
            "• $leaderName｜$outcomeReport"
        }
}

internal fun buildSameSideMonitorAlerts(positions: List<LeaderMonitorPosition>): List<SameSideMonitorAlert> {
    if (positions.isEmpty()) {
        return emptyList()
    }

    return positions.groupBy { OutcomeKey(it.outcomeIndex, it.outcome) }
        .filterValues { items -> items.map { it.leaderId }.distinct().size >= 2 }
        .toSortedMap(compareBy<OutcomeKey> { it.outcomeIndex }.thenBy { it.outcome })
        .map { (key, items) ->
            val normalizedItems = items
                .distinctBy { it.leaderId to it.outcomeIndex }
                .sortedWith(compareBy<LeaderMonitorPosition> { it.leaderId }.thenBy { it.outcomeIndex })
            val sample = normalizedItems.first()
            SameSideMonitorAlert(
                marketId = sample.marketId,
                marketTitle = sample.marketTitle,
                marketLink = sample.marketLink,
                outcomeIndex = key.outcomeIndex,
                outcome = key.outcome,
                sameSidePositionReport = buildSameSidePositionReport(normalizedItems),
                sameSideCount = normalizedItems.size,
                leaderGroups = normalizedItems.map { it.leaderGroup }.distinct(),
                fingerprint = normalizedItems.joinToString("|") {
                    "${it.leaderId}:${formatMonitorDecimal(it.currentValue)}:${formatMonitorDecimal(it.avgPrice)}"
                }
            )
        }
}

internal fun buildOppositeMonitorAlert(positions: List<LeaderMonitorPosition>): OppositeMonitorAlert? {
    if (positions.isEmpty()) {
        return null
    }

    val sideGroups = positions.groupBy { OutcomeKey(it.outcomeIndex, it.outcome) }
        .toSortedMap(compareBy<OutcomeKey> { it.outcomeIndex }.thenBy { it.outcome })

    val hedged = positions.groupBy { it.leaderId }
        .filterValues { items -> items.map { it.outcomeIndex }.distinct().size >= 2 }

    if (sideGroups.size < 2 && hedged.isEmpty()) {
        return null
    }

    val sideEntries = sideGroups.entries.toList()
    if (sideEntries.size < 2) {
        return null
    }

    val sideA = sideEntries[0]
    val sideB = sideEntries[1]
    val sideAItems = sideA.value.distinctBy { it.leaderId to it.outcomeIndex }
    val sideBItems = sideB.value.distinctBy { it.leaderId to it.outcomeIndex }
    val sample = sideAItems.firstOrNull() ?: sideBItems.first()
    val fingerprintParts = sideEntries.flatMap { (key, items) ->
        items.distinctBy { it.leaderId to it.outcomeIndex }.sortedWith(compareBy<LeaderMonitorPosition> { it.leaderId }.thenBy { it.outcomeIndex }).map {
            "${key.outcomeIndex}:${it.leaderId}:${formatMonitorDecimal(it.currentValue)}:${formatMonitorDecimal(it.avgPrice)}"
        }
    }

    return OppositeMonitorAlert(
        marketId = sample.marketId,
        marketTitle = sample.marketTitle,
        marketLink = sample.marketLink,
        outcomeA = sideA.key.outcome,
        sideAPositionReport = buildSameSidePositionReport(sideAItems),
        outcomeB = sideB.key.outcome,
        sideBPositionReport = buildSameSidePositionReport(sideBItems),
        hedgePositionReport = buildHedgePositionReport(positions),
        leaderGroups = positions.map { it.leaderGroup }.distinct(),
        fingerprint = fingerprintParts.joinToString("|")
    )
}

@Service
class LeaderMonitorAlertService(
    private val copyTradingRepository: CopyTradingRepository,
    private val leaderRepository: LeaderRepository,
    private val blockchainService: BlockchainService,
    private val telegramNotificationService: TelegramNotificationService
) {

    private val logger = LoggerFactory.getLogger(LeaderMonitorAlertService::class.java)
    private val baselineMutex = Mutex()
    private val leaderPositionCache = ConcurrentHashMap<Long, List<LeaderMonitorPosition>>()
    private val sameSideFingerprints = ConcurrentHashMap<String, String>()
    private val oppositeFingerprints = ConcurrentHashMap<String, String>()

    @Volatile
    private var baselineLeaderIds: Set<Long> = emptySet()

    suspend fun processTrade(leaderId: Long, trade: TradeResponse) {
        val activeLeaders = loadActiveLeaders()
        if (!activeLeaders.containsKey(leaderId)) {
            return
        }

        ensureBaseline(activeLeaders)
        val leader = activeLeaders.getValue(leaderId)
        val previousLeaderPositions = leaderPositionCache[leaderId].orEmpty()
        refreshLeaderPositions(leader)
        sendTradeDetectedNotification(leader, trade, previousLeaderPositions)
        publishMarketUpdates(trade.market, activeLeaders.keys)
    }

    private suspend fun sendTradeDetectedNotification(
        leader: Leader,
        trade: TradeResponse,
        previousLeaderPositions: List<LeaderMonitorPosition>
    ) {
        val leaderId = leader.id ?: return
        val currentMarketPositions = leaderPositionCache[leaderId].orEmpty()
            .filter { it.marketId == trade.market }
        val previousMarketPositions = previousLeaderPositions
            .filter { it.marketId == trade.market }
        val referencePosition = currentMarketPositions.firstOrNull { it.outcomeIndex == trade.outcomeIndex }
            ?: previousMarketPositions.firstOrNull { it.outcomeIndex == trade.outcomeIndex }
            ?: currentMarketPositions.firstOrNull()
            ?: previousMarketPositions.firstOrNull()
        val outcome = trade.outcome?.trim().takeUnless { it.isNullOrBlank() }
            ?: referencePosition?.outcome
            ?: trade.outcomeIndex?.toString()
            ?: "-"
        val leaderName = leader.leaderName?.trim().takeUnless { it.isNullOrBlank() } ?: "Leader-$leaderId"
        val copyTrading = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId).firstOrNull()

        telegramNotificationService.sendMonitorPushNotification(
            marketTitle = referencePosition?.marketTitle ?: trade.market,
            marketLink = referencePosition?.marketLink ?: "https://polymarket.com/condition/${trade.market}",
            leaderName = leaderName,
            side = trade.side,
            outcome = outcome,
            price = trade.price,
            size = trade.size,
            currentPositionSummary = buildMonitorPositionSummary(currentMarketPositions),
            copyTradingId = copyTrading?.id,
            leaderGroup = leader.customGroup
        )
    }

    private suspend fun ensureBaseline(activeLeaders: Map<Long, Leader>) {
        val activeLeaderIds = activeLeaders.keys
        if (leaderPositionCache.isNotEmpty() && baselineLeaderIds == activeLeaderIds) {
            return
        }

        baselineMutex.withLock {
            if (leaderPositionCache.isNotEmpty() && baselineLeaderIds == activeLeaderIds) {
                return
            }

            leaderPositionCache.clear()
            sameSideFingerprints.clear()
            oppositeFingerprints.clear()

            if (activeLeaders.isEmpty()) {
                baselineLeaderIds = emptySet()
                return
            }

            val loadedPositions = coroutineScope {
                activeLeaders.values.map { leader ->
                    async {
                        leader.id!! to loadLeaderPositions(leader)
                    }
                }.map { it.await() }.toMap()
            }

            leaderPositionCache.putAll(loadedPositions)
            baselineLeaderIds = activeLeaderIds

            loadedPositions.values.flatten().map { it.marketId }.toSet().forEach { marketId ->
                captureBaselineForMarket(marketId, activeLeaderIds)
            }
        }
    }

    private suspend fun refreshLeaderPositions(leader: Leader) {
        val leaderId = leader.id ?: return
        leaderPositionCache[leaderId] = loadLeaderPositions(leader)
    }

    private suspend fun loadLeaderPositions(leader: Leader): List<LeaderMonitorPosition> {
        val leaderId = leader.id ?: return emptyList()
        return blockchainService.getPositions(leader.leaderAddress).fold(
            onSuccess = { positions ->
                positions.mapNotNull { position ->
                    toLeaderMonitorPosition(
                        leaderId = leaderId,
                        leaderName = leader.leaderName,
                        leaderGroup = leader.customGroup,
                        position = position
                    )
                }
            },
            onFailure = { error ->
                logger.warn("Failed to load monitor positions for leader {}: {}", leaderId, error.message)
                leaderPositionCache[leaderId].orEmpty()
            }
        )
    }

    private fun toLeaderMonitorPosition(
        leaderId: Long,
        leaderName: String?,
        leaderGroup: String?,
        position: PositionResponse
    ): LeaderMonitorPosition? {
        val marketId = position.conditionId ?: return null
        val outcomeIndex = position.outcomeIndex ?: return null
        val currentValue = position.currentValue?.let { BigDecimal.valueOf(it) } ?: return null
        if (currentValue <= BigDecimal.ZERO) {
            return null
        }

        val avgPrice = position.avgPrice?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO
        val marketSlug = position.eventSlug?.takeIf { it.isNotBlank() }
            ?: position.slug?.takeIf { it.isNotBlank() }

        return LeaderMonitorPosition(
            leaderId = leaderId,
            leaderName = leaderName?.trim().takeUnless { it.isNullOrBlank() } ?: "Leader-$leaderId",
            marketId = marketId,
            marketTitle = position.title?.trim().takeUnless { it.isNullOrBlank() } ?: marketId,
            marketLink = marketSlug?.let { "https://polymarket.com/event/$it" }
                ?: "https://polymarket.com/condition/$marketId",
            outcomeIndex = outcomeIndex,
            outcome = position.outcome?.trim().takeUnless { it.isNullOrBlank() } ?: outcomeIndex.toString(),
            currentValue = currentValue,
            avgPrice = avgPrice,
            leaderGroup = leaderGroup
        )
    }

    private suspend fun publishMarketUpdates(marketId: String, activeLeaderIds: Set<Long>) {
        val marketPositions = positionsForMarket(marketId, activeLeaderIds)
        val activeSameSideAlerts = buildSameSideMonitorAlerts(marketPositions)
        val activeSameSideKeys = activeSameSideAlerts.map { buildSameSideKey(marketId, it.outcomeIndex) }.toSet()

        sameSideFingerprints.keys
            .filter { it.startsWith("$marketId:") && it !in activeSameSideKeys }
            .forEach { sameSideFingerprints.remove(it) }

        activeSameSideAlerts.forEach { alert ->
            val key = buildSameSideKey(alert.marketId, alert.outcomeIndex)
            if (sameSideFingerprints[key] != alert.fingerprint) {
                telegramNotificationService.sendMonitorSameSideNotification(
                    marketTitle = alert.marketTitle,
                    marketLink = alert.marketLink,
                    outcome = alert.outcome,
                    sameSidePositionReport = alert.sameSidePositionReport,
                    sameSideCount = alert.sameSideCount,
                    leaderGroups = alert.leaderGroups
                )
                sameSideFingerprints[key] = alert.fingerprint
            }
        }

        val oppositeAlert = buildOppositeMonitorAlert(marketPositions)
        if (oppositeAlert == null) {
            oppositeFingerprints.remove(marketId)
            return
        }

        if (oppositeFingerprints[marketId] != oppositeAlert.fingerprint) {
            telegramNotificationService.sendMonitorOppositeNotification(
                marketTitle = oppositeAlert.marketTitle,
                marketLink = oppositeAlert.marketLink,
                outcomeA = oppositeAlert.outcomeA,
                sideAPositionReport = oppositeAlert.sideAPositionReport,
                outcomeB = oppositeAlert.outcomeB,
                sideBPositionReport = oppositeAlert.sideBPositionReport,
                hedgePositionReport = oppositeAlert.hedgePositionReport,
                leaderGroups = oppositeAlert.leaderGroups
            )
            oppositeFingerprints[marketId] = oppositeAlert.fingerprint
        }
    }

    private fun captureBaselineForMarket(marketId: String, activeLeaderIds: Set<Long>) {
        val marketPositions = positionsForMarket(marketId, activeLeaderIds)
        buildSameSideMonitorAlerts(marketPositions).forEach { alert ->
            sameSideFingerprints[buildSameSideKey(alert.marketId, alert.outcomeIndex)] = alert.fingerprint
        }
        buildOppositeMonitorAlert(marketPositions)?.let { alert ->
            oppositeFingerprints[marketId] = alert.fingerprint
        }
    }

    private fun positionsForMarket(marketId: String, activeLeaderIds: Set<Long>): List<LeaderMonitorPosition> {
        return activeLeaderIds.flatMap { leaderId -> leaderPositionCache[leaderId].orEmpty() }
            .filter { it.marketId == marketId }
    }

    private fun buildSameSideKey(marketId: String, outcomeIndex: Int): String {
        return "$marketId:$outcomeIndex"
    }

    private fun loadActiveLeaders(): Map<Long, Leader> {
        val leaderIds = copyTradingRepository.findByEnabledTrue()
            .map { it.leaderId }
            .distinct()

        if (leaderIds.isEmpty()) {
            return emptyMap()
        }

        return leaderRepository.findAllById(leaderIds).associateBy { it.id!! }
    }
}
