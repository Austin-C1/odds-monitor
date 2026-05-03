package com.wrbug.polymarketbot.service.oddsmonitor.collector.polymarket

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
import com.wrbug.polymarketbot.service.market.MarketBettingQueryService
import com.wrbug.polymarketbot.service.oddsmonitor.OddsChangeNotificationService
import com.wrbug.polymarketbot.service.oddsmonitor.OddsFootballMatchFilter
import com.wrbug.polymarketbot.service.oddsmonitor.OddsStandardMatchService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class PolymarketOddsCollectionResult(
    val status: String,
    val message: String?,
    val recordsCount: Int
)

@Component
class PolymarketOddsCollector(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val platformMatchRepository: OddsPlatformMatchRepository,
    private val marketRepository: OddsMarketRepository,
    private val snapshotRepository: OddsSnapshotRepository,
    private val collectionLogRepository: OddsCollectionLogRepository,
    private val marketBettingQueryService: MarketBettingQueryService,
    private val mapper: PolymarketOddsMapper,
    private val objectMapper: ObjectMapper,
    private val oddsChangeNotificationService: OddsChangeNotificationService,
    private val standardMatchService: OddsStandardMatchService
) {
    private val logger = LoggerFactory.getLogger(PolymarketOddsCollector::class.java)

    @Transactional
    fun collectOnce(): PolymarketOddsCollectionResult {
        val config = dataSourceConfigRepository.findBySourceKey(SOURCE_KEY)
        if (config == null || !config.enabled) {
            return PolymarketOddsCollectionResult("disabled", null, 0)
        }

        val startedAt = System.currentTimeMillis()
        return try {
            val capturedAt = System.currentTimeMillis()
            val queryKeywords = listOf("soccer") + config.queryKeyword.orEmpty()
                .split(',', ';', '|')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val details = runBlocking {
                marketBettingQueryService.activeFootballDetails(limit = 100, queries = queryKeywords).getOrThrow()
            }
            val mappedEvents = details.mapNotNull { mapper.map(it, capturedAt) }
                .filterNot {
                    OddsFootballMatchFilter.shouldIgnore(
                        it.match.leagueName,
                        it.match.homeTeam,
                        it.match.awayTeam
                    )
                }

            val stats = writeEvents(mappedEvents)
            saveLog(
                startedAt = startedAt,
                status = "success",
                message = "collected ${stats.rowCount} polymarket odds rows",
                recordsCount = stats.rowCount,
                matchCount = stats.matchCount,
                marketCount = stats.rowCount,
                emptyMarketCount = stats.emptyMarketCount
            )
            PolymarketOddsCollectionResult("success", null, stats.rowCount)
        } catch (ex: RuntimeException) {
            val message = "polymarket odds collection error: ${ex.message ?: ex.javaClass.simpleName}"
            logger.warn(message, ex)
            saveLog(startedAt, "failed_runtime", message, 0, failureReason = message)
            PolymarketOddsCollectionResult("failed_runtime", message, 0)
        }
    }

    private fun writeEvents(events: List<PolymarketMappedEvent>): WriteStats {
        var rowCount = 0
        var emptyMarketCount = 0
        events.forEach { event ->
            val platformMatch = savePlatformMatch(event.match)
            val platformMatchId = platformMatch.id ?: return@forEach
            val standardMatch = standardMatchService.resolveStandardMatch(platformMatch)
            val standardMatchId = standardMatch.id ?: return@forEach
            if (event.rows.isEmpty()) {
                emptyMarketCount += 1
            }
            event.rows.forEach { row -> saveOddsRow(platformMatch, platformMatchId, standardMatchId, row) }
            rowCount += event.rows.size
        }
        return WriteStats(events.size, rowCount, emptyMarketCount)
    }

    private fun savePlatformMatch(match: PolymarketFootballMatch): OddsPlatformMatch {
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

    private fun saveOddsRow(
        platformMatch: OddsPlatformMatch,
        platformMatchId: Long,
        standardMatchId: Long,
        row: PolymarketMappedOddsRow
    ) {
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
                platformMatchId = platformMatchId,
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
        const val SOURCE_KEY = "polymarket"
    }
}
