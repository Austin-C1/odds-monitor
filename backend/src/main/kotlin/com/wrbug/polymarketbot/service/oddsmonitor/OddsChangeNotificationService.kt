package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsMatch
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.system.NotificationConfigService
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.TextEncodingUtils
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
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
    private val leagueFilterService: OddsLeagueFilterService? = null,
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository? = null,
    private val systemConfigService: SystemConfigService? = null,
    private val notificationTemplateService: NotificationTemplateService? = null,
    private val marketStateService: OddsNotificationMarketStateService = OddsNotificationMarketStateService()
) {
    private val logger = LoggerFactory.getLogger(OddsChangeNotificationService::class.java)
    private val defaultExpectedSources = listOf("crown")
    private val eligibilityService = OddsNotificationEligibilityService(marketRepository, snapshotRepository)
    private val pendingQueue = OddsNotificationPendingQueue(defaultExpectedSources)
    private val deliveryService = OddsNotificationDeliveryService(
        alertRecordRepository = alertRecordRepository,
        telegramNotificationService = telegramNotificationService,
        dataSourceConfigRepository = dataSourceConfigRepository,
        notificationTemplateService = notificationTemplateService,
        eligibilityService = eligibilityService,
        defaultExpectedSources = defaultExpectedSources
    )
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "odds-change-notification-flush").apply { isDaemon = true }
    }

    fun notifyIfChanged(
        match: OddsPlatformMatch,
        market: OddsMarket,
        previousOdds: BigDecimal?,
        currentOdds: BigDecimal,
        previousCapturedAt: Long? = null,
        currentCapturedAt: Long = System.currentTimeMillis()
    ) {
        if (!eligibilityService.shouldProcessSource(match, defaultExpectedSources)) {
            return
        }
        if (!eligibilityService.shouldProcessFootballMatch(match)) {
            return
        }
        if (!hasOddsChanged(previousOdds, currentOdds)) {
            return
        }
        if (isStaleOddsChangeComparison(market.sourceKey, previousCapturedAt, currentCapturedAt)) {
            return
        }
        if (marketStateService.shouldResetOddsBaselineAfterLineChange(market)) {
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
        val liveObservationMinutes = if (matchPhase == OddsMonitorMatchPhase.LIVE) {
            loadLiveObservationMinutes()
        } else {
            null
        }
        val phaseConfigs = eligibilityService.configsQualifiedByPhase(
            configs,
            matchPhase,
            startTime,
            now,
            liveObservationMinutes
        )
        if (phaseConfigs.isEmpty()) {
            return
        }

        val leagueMatched = shouldNotifyLeague(match, standardMatch)
        val leagueEligibleConfigs = eligibilityService.configsQualifiedByLeague(phaseConfigs, leagueMatched)
        if (leagueEligibleConfigs.isEmpty()) {
            return
        }
        val eligibleConfigs = eligibilityService.configsQualifiedBySelectedLeagueRules(
            market,
            previousOdds,
            currentOdds,
            leagueEligibleConfigs
        )
        if (eligibleConfigs.isEmpty()) {
            return
        }
        val hasTestModeEligibleConfig = eligibleConfigs.any { isTelegramTestModeEnabled(it) }

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
            applyOddsMoveFilter = leagueMatched && !hasTestModeEligibleConfig,
            matchPhase = matchPhase,
            liveContext = liveContextForNotification(matchPhase, match, standardMatch, now)
        )
    }

    fun notifyMarketState(
        match: OddsPlatformMatch,
        standardMatch: OddsMatch,
        marketType: String,
        currentLines: Set<String>
    ) {
        marketStateService.notifyMarketState(match, standardMatch, marketType, currentLines)
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
        applyOddsMoveFilter: Boolean = true,
        matchPhase: OddsMonitorMatchPhase = OddsMonitorMatchPhase.PREMATCH,
        liveContext: OddsLiveMatchContext? = null
    ) {
        val key = pendingQueue.enqueueMergedNotification(
            match = match,
            standardMatch = standardMatch,
            matchName = matchName,
            leagueName = leagueName,
            matchId = matchId,
            market = market,
            previousOdds = previousOdds,
            currentOdds = currentOdds,
            configs = configs,
            applyOddsMoveFilter = applyOddsMoveFilter,
            matchPhase = matchPhase,
            liveContext = liveContext
        )
        scheduler.schedule({ flushNotification(key) }, 1500, TimeUnit.MILLISECONDS)
    }

    private fun flushNotification(key: OddsChangeNotificationKey) {
        val pending = pendingQueue.remove(key) ?: return
        deliveryService.flushNotification(pending)
    }

    fun clearSourceState(sourceKey: String) {
        val normalizedSourceKey = sourceKey.trim().takeIf { it.isNotBlank() } ?: return
        marketStateService.clearSourceState(normalizedSourceKey)
        pendingQueue.clearSourceState(normalizedSourceKey)
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

    private fun loadLiveObservationMinutes(): Int? {
        return runCatching {
            systemConfigService?.getLiveObservationMinutes()
        }.getOrElse { error ->
            logger.warn("Failed to load live observation minutes: {}", error.message)
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

    private fun liveContextForNotification(
        matchPhase: OddsMonitorMatchPhase,
        match: OddsPlatformMatch,
        standardMatch: OddsMatch?,
        now: Long
    ): OddsLiveMatchContext? {
        if (matchPhase != OddsMonitorMatchPhase.LIVE) {
            return null
        }
        val startTime = standardMatch?.startTime ?: match.rawStartTime
        val elapsedMinutes = oddsMonitorLiveElapsedMinutes(match.rawPayloadJson)
            ?: startTime
                ?.let { ((now - it) / 60_000L).toInt() }
                ?.takeIf { it >= 0 }
        return OddsLiveMatchContext(
            elapsedMinutes = elapsedMinutes,
            scoreText = oddsMonitorLiveScoreText(match.rawPayloadJson)
        )
    }

    private fun isStaleOddsChangeComparison(
        sourceKey: String,
        previousCapturedAt: Long?,
        currentCapturedAt: Long
    ): Boolean {
        val previous = previousCapturedAt ?: return false
        if (currentCapturedAt < previous) {
            return true
        }
        val intervalMillis = dataSourceConfigRepository
            ?.findBySourceKey(sourceKey)
            ?.intervalSeconds
            ?.coerceAtLeast(10)
            ?.times(1000L)
            ?: return false
        return currentCapturedAt - previous > intervalMillis + STALE_ODDS_CHANGE_GRACE_MILLIS
    }

    companion object {
        private const val STALE_ODDS_CHANGE_GRACE_MILLIS = 1_000L
    }
}
