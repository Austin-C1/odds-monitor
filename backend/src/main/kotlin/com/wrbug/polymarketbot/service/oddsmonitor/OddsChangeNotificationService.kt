package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.DateUtils
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Service
class OddsChangeNotificationService(
    private val alertRecordRepository: OddsAlertRecordRepository,
    private val telegramNotificationService: TelegramNotificationService,
    private val notificationConfigService: NotificationConfigService,
    private val marketRepository: OddsMarketRepository,
    private val snapshotRepository: OddsSnapshotRepository
) {
    private val logger = LoggerFactory.getLogger(OddsChangeNotificationService::class.java)
    private val expectedSources = listOf("pinnacle", "crown", "polymarket")
    private val pendingAlerts = ConcurrentHashMap<OddsChangeNotificationKey, PendingOddsChangeNotification>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "odds-change-notification-flush").apply { isDaemon = true }
    }

    fun notifyIfChanged(
        match: OddsPlatformMatch,
        market: OddsMarket,
        previousOdds: BigDecimal?,
        currentOdds: BigDecimal
    ) {
        if (!hasOddsChanged(previousOdds, currentOdds)) {
            return
        }
        if (shouldSuppressByOddsMove(market, previousOdds, currentOdds)) {
            return
        }
        if (shouldSuppressByCombinedWater(market, currentOdds)) {
            return
        }

        enqueueMergedNotification(match, market, previousOdds ?: BigDecimal.ZERO, currentOdds)
    }

    private fun enqueueMergedNotification(
        match: OddsPlatformMatch,
        market: OddsMarket,
        previousOdds: BigDecimal,
        currentOdds: BigDecimal
    ) {
        val key = OddsChangeNotificationKey(
            standardMatchId = market.matchId,
            marketType = market.marketType,
            lineValue = market.lineValue,
            selectionName = market.selectionName
        )
        pendingAlerts.compute(key) { _, existing ->
            val base = existing ?: PendingOddsChangeNotification(
                matchName = "${match.rawHomeTeam} vs ${match.rawAwayTeam}",
                leagueName = match.rawLeagueName,
                marketLabel = market.displayLabel(),
                matchId = market.matchId,
                changes = linkedMapOf()
            )
            base.changes[market.sourceKey] = OddsChangeNotificationItem(market.sourceKey, previousOdds, currentOdds)
            base
        }
        scheduler.schedule({ flushNotification(key) }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun flushNotification(key: OddsChangeNotificationKey) {
        val pending = pendingAlerts.remove(key) ?: return
        val message = buildMergedOddsChangeAlertMessage(
            matchName = pending.matchName,
            leagueName = pending.leagueName,
            marketLabel = pending.marketLabel,
            changes = pending.changes.values.toList(),
            expectedSources = expectedSources,
            timestampText = DateUtils.formatDateTime()
        )
        alertRecordRepository.save(
            OddsAlertRecord(
                alertType = "odds_change",
                severity = "info",
                matchId = pending.matchId,
                sourceKey = null,
                title = "赔率变动：${pending.matchName}",
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )

        runCatching {
            runBlocking {
                telegramNotificationService.sendMonitorMessage(message)
            }
        }.onFailure { error ->
            logger.warn("Failed to send odds change Telegram notification: {}", error.message)
        }
    }

    private fun shouldSuppressByOddsMove(
        market: OddsMarket,
        previousOdds: BigDecimal?,
        currentOdds: BigDecimal
    ): Boolean {
        val configs = runCatching {
            runBlocking { notificationConfigService.getEnabledConfigsByType("telegram") }
        }.getOrElse { error ->
            logger.warn("Failed to load Telegram odds move filters: {}", error.message)
            return false
        }
        return shouldSuppressOddsChangeByMove(market.marketType, previousOdds, currentOdds, configs)
    }

    private fun shouldSuppressByCombinedWater(market: OddsMarket, currentOdds: BigDecimal): Boolean {
        val pairSelectionName = pairSelectionName(market.marketType, market.selectionName) ?: return false
        val configs = runCatching {
            runBlocking { notificationConfigService.getEnabledConfigsByType("telegram") }
        }.getOrElse { error ->
            logger.warn("Failed to load Telegram water limits: {}", error.message)
            return false
        }
        val activeLimits = activeCombinedWaterLimits(market.marketType, configs)
        if (activeLimits.isEmpty()) {
            return false
        }

        val pairMarket = marketRepository.findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
            matchId = market.matchId,
            sourceKey = market.sourceKey,
            marketType = market.marketType,
            lineValue = market.lineValue,
            selectionName = pairSelectionName
        ) ?: return true
        val pairMarketId = pairMarket.id ?: return true
        val pairOdds = snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(pairMarketId)?.oddsValue ?: return true

        return shouldSuppressOddsChangeByCombinedWater(
            marketType = market.marketType,
            currentOdds = currentOdds,
            pairedOdds = pairOdds,
            configs = configs
        )
    }
}

data class OddsChangeNotificationItem(
    val sourceKey: String,
    val previousOdds: BigDecimal,
    val currentOdds: BigDecimal
)

private data class OddsChangeNotificationKey(
    val standardMatchId: Long,
    val marketType: String,
    val lineValue: String?,
    val selectionName: String
)

private data class PendingOddsChangeNotification(
    val matchName: String,
    val leagueName: String,
    val marketLabel: String,
    val matchId: Long,
    val changes: LinkedHashMap<String, OddsChangeNotificationItem>
)

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

fun buildOddsChangeAlertTitle(match: OddsPlatformMatch): String {
    return "赔率变动：${match.rawHomeTeam} vs ${match.rawAwayTeam}"
}

fun buildOddsChangeAlertMessage(
    match: OddsPlatformMatch,
    market: OddsMarket,
    previousOdds: BigDecimal,
    currentOdds: BigDecimal
): String {
    val marketLabel = listOf(
        market.marketType,
        market.selectionName,
        OddsLineDisplayFormatter.format(market.marketType, market.lineValue)
    ).mapNotNull { it?.takeIf { value -> value.isNotBlank() } }.joinToString(" ")

    return """<b>${escapeHtml(buildOddsChangeAlertTitle(match))}</b>

联赛: ${escapeHtml(match.rawLeagueName)}
比赛: ${escapeHtml(match.rawHomeTeam)} vs ${escapeHtml(match.rawAwayTeam)}
盘口: ${escapeHtml(marketLabel)}
平台: ${escapeHtml(market.sourceKey)}
变化: <code>${formatOdds(previousOdds)} -> ${formatOdds(currentOdds)}</code>
时间: <code>${DateUtils.formatDateTime()}</code>"""
}

fun buildMergedOddsChangeAlertMessage(
    matchName: String,
    leagueName: String,
    marketLabel: String,
    changes: List<OddsChangeNotificationItem>,
    expectedSources: List<String>,
    timestampText: String
): String {
    val changesBySource = changes.associateBy { it.sourceKey }
    return buildString {
        appendLine("<b>${escapeHtml("赔率变动：$matchName")}</b>")
        appendLine()
        appendLine("联赛：${escapeHtml(leagueName)}")
        appendLine("盘口：${escapeHtml(marketLabel)}")
        appendLine()
        expectedSources.forEach { sourceKey ->
            val change = changesBySource[sourceKey]
            if (change == null) {
                appendLine("${platformLabel(sourceKey)}：无对应盘口")
            } else {
                appendLine("${platformLabel(sourceKey)}：${formatMergedOdds(change.previousOdds)} -> ${formatMergedOdds(change.currentOdds)}")
            }
        }
        appendLine()
        appendLine("筛选：动水通过 / 合水通过")
        append("时间：$timestampText")
    }
}

private fun OddsMarket.displayLabel(): String {
    val marketLabel = when (marketType.lowercase()) {
        "handicap" -> "让球"
        "total" -> "大小球"
        "moneyline" -> "胜平负"
        else -> marketType
    }
    val selectionLabel = when (selectionName.lowercase()) {
        "home" -> "主队"
        "away" -> "客队"
        "over" -> "大球"
        "under" -> "小球"
        "draw" -> "平"
        else -> selectionName
    }
    return listOf(marketLabel, selectionLabel, OddsLineDisplayFormatter.format(marketType, lineValue))
        .mapNotNull { it?.takeIf { value -> value.isNotBlank() } }
        .joinToString(" ")
}

private fun platformLabel(sourceKey: String): String {
    return when (sourceKey) {
        "pinnacle" -> "平博"
        "crown" -> "皇冠"
        "polymarket" -> "Polymarket"
        else -> sourceKey
    }
}

private fun formatOdds(value: BigDecimal): String {
    return value.stripTrailingZeros().toPlainString()
}

private fun formatMergedOdds(value: BigDecimal): String {
    val normalized = value.stripTrailingZeros()
    return if (normalized.scale() < 2) {
        value.setScale(2).toPlainString()
    } else {
        normalized.toPlainString()
    }
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
