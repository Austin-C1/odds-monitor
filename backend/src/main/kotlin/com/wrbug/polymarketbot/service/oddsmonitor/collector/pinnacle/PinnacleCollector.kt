package com.wrbug.polymarketbot.service.oddsmonitor.collector.pinnacle

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.entity.OddsCollectionLog
import com.wrbug.polymarketbot.entity.OddsMarket
import com.wrbug.polymarketbot.entity.OddsPlatformMatch
import com.wrbug.polymarketbot.entity.OddsSnapshot
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import com.wrbug.polymarketbot.repository.OddsMarketRepository
import com.wrbug.polymarketbot.repository.OddsPlatformMatchRepository
import com.wrbug.polymarketbot.repository.OddsSnapshotRepository
import com.wrbug.polymarketbot.service.oddsmonitor.OddsChangeNotificationService
import com.wrbug.polymarketbot.service.oddsmonitor.OddsStandardMatchService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class PinnacleCollector(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository,
    private val marketRepository: OddsMarketRepository,
    private val snapshotRepository: OddsSnapshotRepository,
    private val collectionLogRepository: OddsCollectionLogRepository,
    private val loginService: PinnacleLoginService,
    private val browserSession: PinnacleBrowserSession,
    private val pageGuard: PinnaclePageGuard,
    private val parser: PinnacleFootballParser,
    private val mapper: PinnacleOddsMapper,
    private val debugArtifactService: PinnacleDebugArtifactService,
    private val objectMapper: ObjectMapper,
    private val oddsChangeNotificationService: OddsChangeNotificationService,
    private val standardMatchService: OddsStandardMatchService
) {
    private val logger = LoggerFactory.getLogger(PinnacleCollector::class.java)

    fun sourceKey(): String = SOURCE_KEY

    @Transactional
    fun collectOnce(): PinnacleCollectionResult {
        val config = dataSourceConfigRepository.findBySourceKey(SOURCE_KEY)
        if (config == null || !config.enabled) {
            return PinnacleCollectionResult("disabled", null, 0)
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val page = loginService.ensureLoggedIn(config)
            loginService.openFootballPage(page)
            browserSession.dismissBlockingPrompts()
            browserSession.scrollToBottom()
            val html = browserSession.waitForOddsContent()
            guardHtml(html, page)

            val matches = parser.parse(html)
            if (matches.isEmpty()) {
                val artifacts = debugArtifactService.saveHtmlAndScreenshot(html, page, "failed_empty")
                throw PinnacleCollectionException("failed_empty", "pinnacle football page has no prematch odds; debug: $artifacts")
            }

            val stats = writeMatches(matches)
            saveLog(
                startedAt = startedAt,
                status = "success",
                message = "collected ${stats.rowCount} pinnacle odds rows",
                recordsCount = stats.rowCount,
                matchCount = stats.matchCount,
                marketCount = stats.rowCount,
                emptyMarketCount = stats.emptyMarketCount
            )
            PinnacleCollectionResult("success", null, stats.rowCount)
        } catch (ex: PinnacleCollectionException) {
            saveFailure(startedAt, ex.status, ex.message)
            PinnacleCollectionResult(ex.status, ex.message, 0)
        } catch (ex: RuntimeException) {
            val artifacts = debugArtifactService.saveHtmlAndScreenshot(
                runCatching { browserSession.activePage()?.content() }.getOrNull(),
                browserSession.activePage(),
                "failed_browser"
            )
            val message = "pinnacle browser error; debug: $artifacts"
            logger.warn(message, ex)
            saveFailure(startedAt, "failed_browser", message)
            PinnacleCollectionResult("failed_browser", message, 0)
        }
    }

    private fun writeMatches(matches: List<PinnacleFootballMatch>): WriteStats {
        val capturedAt = System.currentTimeMillis()
        var rowCount = 0
        var emptyMarketCount = 0
        matches.forEach { match ->
            val platformMatch = savePlatformMatch(match)
            val platformMatchId = platformMatch.id ?: return@forEach
            val standardMatch = standardMatchService.resolveStandardMatch(platformMatch)
            val standardMatchId = standardMatch.id ?: return@forEach
            val rows = mapper.map(match, platformMatchId, capturedAt)
            if (rows.isEmpty()) {
                emptyMarketCount += 1
            }
            rows.forEach { saveOddsRow(platformMatch, standardMatchId, it) }
            rowCount += rows.size
        }
        return WriteStats(matchCount = matches.size, rowCount = rowCount, emptyMarketCount = emptyMarketCount)
    }

    private fun guardHtml(html: String, page: com.microsoft.playwright.Page) {
        try {
            pageGuard.ensureUsableFootballHtml(html)
        } catch (ex: PinnacleCollectionException) {
            val artifacts = debugArtifactService.saveHtmlAndScreenshot(html, page, ex.status)
            throw PinnacleCollectionException(ex.status, "${ex.message}; debug: $artifacts", ex)
        }
    }

    private fun savePlatformMatch(match: PinnacleFootballMatch): OddsPlatformMatch {
        val now = System.currentTimeMillis()
        val rawJson = objectMapper.writeValueAsString(match.rawPayload)
        val existing = platformMatchRepository.findBySourceKeyAndSourceMatchId(SOURCE_KEY, match.sourceMatchId)
        val entity = existing?.copy(
            rawLeagueName = match.leagueName,
            rawHomeTeam = match.homeTeam,
            rawAwayTeam = match.awayTeam,
            rawStartTime = match.startTime,
            rawPayloadJson = rawJson,
            updatedAt = now
        ) ?: OddsPlatformMatch(
            sourceKey = SOURCE_KEY,
            sourceMatchId = match.sourceMatchId,
            rawLeagueName = match.leagueName,
            rawHomeTeam = match.homeTeam,
            rawAwayTeam = match.awayTeam,
            rawStartTime = match.startTime,
            rawPayloadJson = rawJson,
            createdAt = now,
            updatedAt = now
        )
        return platformMatchRepository.save(entity)
    }

    private fun saveOddsRow(platformMatch: OddsPlatformMatch, standardMatchId: Long, row: PinnacleMappedOddsRow) {
        val now = System.currentTimeMillis()
        val market = marketRepository.findByMatchIdAndSourceKeyAndMarketTypeAndLineValueAndSelectionName(
            matchId = standardMatchId,
            sourceKey = SOURCE_KEY,
            marketType = row.marketType,
            lineValue = row.lineValue,
            selectionName = row.selectionName
        ) ?: marketRepository.save(
            OddsMarket(
                matchId = standardMatchId,
                platformMatchId = row.platformMatchId,
                sourceKey = SOURCE_KEY,
                marketType = row.marketType,
                lineValue = row.lineValue,
                selectionName = row.selectionName,
                createdAt = now,
                updatedAt = now
            )
        )
        val marketId = market.id ?: return
        val previousOdds = snapshotRepository.findTop1ByMarketIdOrderByCapturedAtDesc(marketId)?.oddsValue

        snapshotRepository.save(
            OddsSnapshot(
                marketId = marketId,
                sourceKey = SOURCE_KEY,
                oddsValue = row.oddsValue,
                capturedAt = row.capturedAt,
                rawPayloadJson = objectMapper.writeValueAsString(row.rawPayload)
            )
        )
        oddsChangeNotificationService.notifyIfChanged(platformMatch, market, previousOdds, row.oddsValue)
    }

    private fun saveFailure(startedAt: Long, status: String, message: String?) {
        saveLog(startedAt, status, message, 0, failureReason = message)
    }

    private fun saveLog(
        startedAt: Long,
        status: String,
        message: String?,
        recordsCount: Int,
        matchCount: Int = 0,
        marketCount: Int = recordsCount,
        emptyMarketCount: Int = 0,
        failureReason: String? = null
    ) {
        collectionLogRepository.save(
            OddsCollectionLog(
                sourceKey = SOURCE_KEY,
                status = status,
                message = message,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                recordsCount = recordsCount,
                matchCount = matchCount,
                marketCount = marketCount,
                emptyMarketCount = emptyMarketCount,
                failureReason = failureReason
            )
        )
    }

    private data class WriteStats(
        val matchCount: Int,
        val rowCount: Int,
        val emptyMarketCount: Int
    )

    companion object {
        const val SOURCE_KEY = "pinnacle"
    }
}
