package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsCollectionLogDto
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.util.TextEncodingUtils
import org.springframework.stereotype.Service

@Service
class OddsCollectionLogService(
    private val collectionLogRepository: OddsCollectionLogRepository
) {
    fun listLogs(): List<OddsCollectionLogDto> {
        return collectionLogRepository.findTop200ByOrderByStartedAtDesc().map {
            OddsCollectionLogDto(
                id = it.id ?: 0,
                sourceKey = it.sourceKey,
                status = it.status,
                message = it.message?.let(TextEncodingUtils::repairMojibake),
                startedAt = it.startedAt,
                finishedAt = it.finishedAt,
                recordsCount = it.recordsCount,
                matchCount = it.matchCount,
                marketCount = it.marketCount,
                emptyMarketCount = it.emptyMarketCount,
                failureReason = it.failureReason?.let(TextEncodingUtils::repairMojibake)
            )
        }
    }
}
