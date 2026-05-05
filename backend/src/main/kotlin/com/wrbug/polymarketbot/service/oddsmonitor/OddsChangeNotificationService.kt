package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.DateUtils
import com.wrbug.polymarketbot.util.TextEncodingUtils
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
    private val snapshotRepository: OddsSnapshotRepository,
    private val matchRepository: OddsMatchRepository? = null,
    private val leagueFilterService: OddsLeagueFilterService? = null
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
        if (OddsFootballMatchFilter.shouldIgnore(match.rawLeagueName, match.rawHomeTeam, match.rawAwayTeam)) {
            return
        }
        if (!hasOddsChanged(previousOdds, currentOdds)) {
            return
        }
        val standardMatch = matchRepository?.findById(market.matchId)?.orElse(null)
        val configs = loadActiveMonitorTelegramConfigs() ?: return
        if (configs.isEmpty()) {
            return
        }
        val now = System.currentTimeMillis()
        val matchPhase = determineOddsMonitorMatchPhase(match, standardMatch, now)
        val startTime = standardMatch?.startTime ?: match.rawStartTime
        val phaseConfigs = configs.filter { telegramConfigMatchesOddsMonitorPhase(it, matchPhase, startTime, now) }
        if (phaseConfigs.isEmpty()) {
            return
        }

        val leagueMatched = shouldNotifyLeague(match, standardMatch)
        val eligibleConfigs = if (leagueMatched) {
            configsQualifiedBySelectedLeagueRules(market, previousOdds, currentOdds, phaseConfigs)
        } else {
            configsQualifiedByCombinedWater(market, currentOdds, phaseConfigs)
        }
        if (eligibleConfigs.isEmpty()) {
            return
        }

        enqueueMergedNotification(
            match = match,
            standardMatch = standardMatch,
            matchName = notificationMatchName(match, standardMatch),
            leagueName = notificationLeagueName(match, standardMatch),
            matchId = market.matchId,
            market = market,
            previousOdds = previousOdds ?: BigDecimal.ZERO,
            currentOdds = currentOdds,
            configs = eligibleConfigs,
            applyOddsMoveFilter = leagueMatched
        )
    }

    private fun enqueueMergedNotification(
        match: OddsPlatformMatch,
        standardMatch: OddsMatch?,
        matchName: String,
        leagueName: String,
        matchId: Long,
        market: OddsMarket,
        previousOdds: BigDecimal,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>,
        applyOddsMoveFilter: Boolean = true
    ) {
        val candidate = notificationMergeCandidate(match, standardMatch)
        val key = notificationMergeKey(matchId, candidate)
        val marketKey = OddsChangeNotificationMarketKey(
            marketType = market.marketType,
            lineValue = market.lineValue,
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
                configs = mergeNotificationConfigs(base.configs, configs)
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
                OddsChangeNotificationItem(market.sourceKey, previousOdds, currentOdds)
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
        scheduler.schedule({ flushNotification(key) }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun flushNotification(key: OddsChangeNotificationKey) {
        val pending = pendingAlerts.remove(key) ?: return
        if (pending.markets.isEmpty()) {
            return
        }
        val message = buildMergedOddsChangeAlertMessage(
            matchName = pending.matchName,
            leagueName = pending.leagueName,
            markets = pending.markets.values.map { market ->
                OddsChangeNotificationMarketItem(
                    marketType = market.marketType,
                    marketLabel = market.marketLabel,
                    changes = market.changes.values.toList()
                )
            },
            expectedSources = expectedSources,
            timestampText = DateUtils.formatDateTime()
        )
        alertRecordRepository.save(
            OddsAlertRecord(
                alertType = "odds_change",
                severity = "info",
                matchId = pending.matchId,
                sourceKey = null,
                title = "赔率变动：${TextEncodingUtils.repairMojibake(pending.matchName)}",
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )

        runCatching {
            runBlocking {
                telegramNotificationService.sendMonitorMessageToConfigs(message, pending.configs)
            }
        }.onFailure { error ->
            logger.warn("Failed to send odds change Telegram notification: {}", error.message)
        }
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
        val pairSelectionName = pairSelectionName(market.marketType, market.selectionName) ?: return configs
        if (activeCombinedWaterLimits(market.marketType, configs).isEmpty()) {
            return configs
        }

        val pairMarket = marketRepository.findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
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

    private fun configsQualifiedBySelectedLeagueRules(
        market: OddsMarket,
        previousOdds: BigDecimal?,
        currentOdds: BigDecimal,
        configs: List<NotificationConfigDto>
    ): List<NotificationConfigDto> {
        val unrestrictedConfigs = configs.filter { config ->
            activeOddsMoveLimits(market.marketType, listOf(config)).isEmpty() &&
                activeCombinedWaterLimits(market.marketType, listOf(config)).isEmpty()
        }
        val oddsMoveConfigs = configs
            .filter { config -> activeOddsMoveLimits(market.marketType, listOf(config)).isNotEmpty() }
            .let { filterConfigsByOddsMove(market, previousOdds, currentOdds, it) }
        val combinedWaterConfigs = configs
            .filter { config -> activeCombinedWaterLimits(market.marketType, listOf(config)).isNotEmpty() }
            .let { configsQualifiedByCombinedWater(market, currentOdds, it) }

        return (unrestrictedConfigs + oddsMoveConfigs + combinedWaterConfigs)
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

        val pairMarket = marketRepository.findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
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

    private fun loadActiveMonitorTelegramConfigs(): List<NotificationConfigDto>? {
        return runCatching {
            runBlocking { notificationConfigService.getEnabledConfigsByType("telegram") }
        }.map { configs ->
            activeMonitorTelegramConfigs(configs)
        }.getOrElse { error ->
            logger.warn("Failed to load Telegram monitor filters: {}", error.message)
            null
        }
    }

    private fun shouldNotifyLeague(match: OddsPlatformMatch, standardMatch: OddsMatch?): Boolean {
        val filter = leagueFilterService ?: return true
        val rawLeagueName = TextEncodingUtils.repairMojibake(match.rawLeagueName).trim()
        if (rawLeagueName.isNotBlank()) {
            return filter.shouldIncludeLeague(match.sourceKey, rawLeagueName)
        }
        return standardMatch?.leagueName?.let { filter.shouldIncludeLeague(it) } ?: false
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
    val currentOdds: BigDecimal
)

data class OddsChangeNotificationMarketItem(
    val marketType: String? = null,
    val marketLabel: String,
    val changes: List<OddsChangeNotificationItem>
)

private data class OddsChangeNotificationKey(
    val value: String
)

private data class OddsChangeNotificationMarketKey(
    val marketType: String,
    val lineValue: String?,
    val selectionName: String
)

private data class PendingOddsChangeNotification(
    val matchName: String,
    val leagueName: String,
    val matchId: Long,
    val candidate: OddsMatchCandidate,
    val configs: List<NotificationConfigDto>,
    val markets: LinkedHashMap<OddsChangeNotificationMarketKey, PendingOddsChangeMarketNotification>
)

private data class PendingOddsChangeMarketNotification(
    val marketType: String,
    val marketLabel: String,
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

fun activeMonitorTelegramConfigs(configs: List<NotificationConfigDto>): List<NotificationConfigDto> {
    return configs.filter { config ->
        config.enabled && (config.config as? NotificationConfigData.Telegram)?.data?.monitorModeEnabled == true
    }
}

private fun mergeNotificationConfigs(
    existing: List<NotificationConfigDto>,
    incoming: List<NotificationConfigDto>
): List<NotificationConfigDto> {
    return (existing + incoming).distinctBy { config -> config.id?.toString() ?: config.name }
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
    return "赔率变动：${TextEncodingUtils.repairMojibake(match.rawHomeTeam)} vs ${TextEncodingUtils.repairMojibake(match.rawAwayTeam)}"
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

联赛: ${escapeHtml(TextEncodingUtils.repairMojibake(match.rawLeagueName))}
比赛: ${escapeHtml(TextEncodingUtils.repairMojibake(match.rawHomeTeam))} vs ${escapeHtml(TextEncodingUtils.repairMojibake(match.rawAwayTeam))}
盘口: ${escapeHtml(marketLabel)}
平台: ${escapeHtml(market.sourceKey)}
变化: <code>${formatOdds(previousOdds, market.sourceKey, market.marketType)} -> ${formatOdds(currentOdds, market.sourceKey, market.marketType)}</code>
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
    return buildMergedOddsChangeAlertMessage(
        matchName = matchName,
        leagueName = leagueName,
        markets = listOf(OddsChangeNotificationMarketItem(marketLabel = marketLabel, changes = changes)),
        expectedSources = expectedSources,
        timestampText = timestampText
    )
}

fun buildMergedOddsChangeAlertMessage(
    matchName: String,
    leagueName: String,
    markets: List<OddsChangeNotificationMarketItem>,
    expectedSources: List<String>,
    timestampText: String
): String {
    val displayMatchName = TextEncodingUtils.repairMojibake(matchName)
    val displayLeagueName = TextEncodingUtils.repairMojibake(leagueName)
    return buildString {
        appendLine("<b>${escapeHtml("赔率变动：$displayMatchName")}</b>")
        appendLine()
        appendLine("联赛：${escapeHtml(displayLeagueName)}")
        appendLine()
        markets.forEachIndexed { index, market ->
            if (index > 0) {
                appendLine()
            }
            appendLine("盘口：${escapeHtml(TextEncodingUtils.repairMojibake(market.marketLabel))}")
            val changesBySource = market.changes.associateBy { it.sourceKey }
            expectedSources.forEach { sourceKey ->
                val change = changesBySource[sourceKey]
                if (change == null) {
                    appendLine("${platformLabel(sourceKey)}：无对应盘口")
                } else {
                    appendLine("${platformLabel(sourceKey)}：${formatMergedOdds(change.previousOdds, change.sourceKey, market.marketType)} -> ${formatMergedOdds(change.currentOdds, change.sourceKey, market.marketType)}")
                }
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

private fun notificationMatchName(platformMatch: OddsPlatformMatch, standardMatch: OddsMatch?): String {
    val standardName = standardMatch?.let { "${it.homeTeam} vs ${it.awayTeam}" }
    val platformName = "${platformMatch.rawHomeTeam} vs ${platformMatch.rawAwayTeam}"
    return bestNotificationText(platformName, standardName)
}

private fun notificationMergeCandidate(platformMatch: OddsPlatformMatch, standardMatch: OddsMatch?): OddsMatchCandidate {
    return OddsMatchCandidate(
        id = standardMatch?.id,
        leagueName = notificationLeagueName(platformMatch, standardMatch),
        homeTeam = TextEncodingUtils.repairMojibake(platformMatch.rawHomeTeam),
        awayTeam = TextEncodingUtils.repairMojibake(platformMatch.rawAwayTeam),
        startTime = standardMatch?.startTime ?: platformMatch.rawStartTime
    )
}

private fun notificationLeagueName(platformMatch: OddsPlatformMatch, standardMatch: OddsMatch?): String {
    return bestNotificationText(platformMatch.rawLeagueName, standardMatch?.leagueName)
}

private fun bestNotificationText(current: String, candidate: String?): String {
    val normalizedCurrent = TextEncodingUtils.repairMojibake(current).trim()
    val normalizedCandidate = TextEncodingUtils.repairMojibake(candidate.orEmpty()).trim()
    return when {
        normalizedCandidate.isBlank() -> normalizedCurrent
        normalizedCurrent.isBlank() -> normalizedCandidate
        isGenericMatchName(normalizedCurrent) -> normalizedCandidate
        normalizedCurrent.contains("特别投注") && !normalizedCandidate.contains("特别投注") -> normalizedCandidate
        else -> normalizedCurrent
    }
}

private fun isGenericMatchName(value: String): Boolean {
    val normalized = value.replace(" ", "")
    return normalized in setOf("主场vs客场", "主队vs客队", "homevsaway", "hometeamvsawayteam")
}

private fun formatMergedOdds(value: BigDecimal, sourceKey: String, marketType: String?): String {
    val displayValue = asianWaterOdds(sourceKey, marketType, value)
    val normalized = displayValue.stripTrailingZeros()
    return if (normalized.scale() < 2) {
        displayValue.setScale(2).toPlainString()
    } else {
        normalized.toPlainString()
    }
}

private fun formatOdds(value: BigDecimal, sourceKey: String, marketType: String): String {
    return asianWaterOdds(sourceKey, marketType, value).stripTrailingZeros().toPlainString()
}

private fun asianWaterOdds(sourceKey: String, marketType: String?, value: BigDecimal): BigDecimal {
    if (
        sourceKey != "pinnacle" ||
        marketType?.lowercase() !in setOf("handicap", "total") ||
        value < BigDecimal.ONE
    ) {
        return value
    }
    return value - BigDecimal.ONE
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
