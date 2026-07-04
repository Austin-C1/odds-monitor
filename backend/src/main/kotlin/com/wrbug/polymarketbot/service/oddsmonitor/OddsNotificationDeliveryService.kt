package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.entity.OddsAlertRecord
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.service.system.NotificationTemplateService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.DateUtils
import com.wrbug.polymarketbot.util.TextEncodingUtils
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

class OddsNotificationDeliveryService(
    private val alertRecordRepository: OddsAlertRecordRepository,
    private val telegramNotificationService: TelegramNotificationService,
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository?,
    private val notificationTemplateService: NotificationTemplateService?,
    private val eligibilityService: OddsNotificationEligibilityService,
    private val defaultExpectedSources: List<String> = listOf("crown")
) {
    private val logger = LoggerFactory.getLogger(OddsNotificationDeliveryService::class.java)

    fun flushNotification(pending: PendingOddsChangeNotification) {
        if (pending.markets.isEmpty()) {
            return
        }
        val markets = pending.markets.mapNotNull { (marketKey, market) ->
            val finalQualifiedChanges = market.changes.values.filter { change ->
                eligibilityService.isChangeQualifiedByFinalCombinedWater(
                    matchId = pending.matchId,
                    marketKey = marketKey,
                    change = change,
                    configs = pending.configs
                )
            }
            if (finalQualifiedChanges.isEmpty()) {
                return@mapNotNull null
            }
            OddsChangeNotificationMarketItem(
                marketType = market.marketType,
                marketLabel = market.marketLabel,
                changes = finalQualifiedChanges
            )
        }
        if (markets.isEmpty()) {
            return
        }
        val expectedSources = expectedSourcesForNotification(pending)
        val timestampText = DateUtils.formatDateTime()
        val fallbackMessage = buildMergedOddsChangeAlertMessage(
            matchName = pending.matchName,
            leagueName = pending.leagueName,
            markets = markets,
            expectedSources = expectedSources,
            timestampText = timestampText,
            liveContext = pending.liveContext
        )
        val message = notificationTemplateService
            ?.renderTemplate(
                oddsMonitorTemplateType(pending.matchPhase),
                buildOddsChangeTemplateVariables(
                    matchName = pending.matchName,
                    leagueName = pending.leagueName,
                    markets = markets,
                    expectedSources = expectedSources,
                    timestampText = timestampText,
                    liveContext = pending.liveContext
                )
            )
            ?.takeIf { it.isNotBlank() }
            ?: fallbackMessage
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

    private fun expectedSourcesForNotification(pending: PendingOddsChangeNotification): List<String> {
        val changedSources = pending.markets.values
            .flatMap { market -> market.changes.keys }
            .filter { sourceKey -> sourceKey in defaultExpectedSources }
            .toSet()
        val enabledSources = enabledSourceKeys()
        return defaultExpectedSources
            .filter { sourceKey -> sourceKey in enabledSources || sourceKey in changedSources }
            .ifEmpty { changedSources.toList().ifEmpty { defaultExpectedSources } }
    }

    private fun enabledSourceKeys(): Set<String> {
        val repository = dataSourceConfigRepository ?: return defaultExpectedSources.toSet()
        return runCatching {
            repository.findAll()
                .filter { config -> config.enabled }
                .map { config -> config.sourceKey }
                .filter { sourceKey -> sourceKey in defaultExpectedSources }
                .toSet()
                .ifEmpty { defaultExpectedSources.toSet() }
        }.getOrElse { error ->
            logger.warn("Failed to load active odds data sources: {}", error.message)
            defaultExpectedSources.toSet()
        }
    }
}
