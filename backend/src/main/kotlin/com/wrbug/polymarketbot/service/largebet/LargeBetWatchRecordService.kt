package com.wrbug.polymarketbot.service.largebet

import com.wrbug.polymarketbot.dto.LargeBetWatchRecordDto
import com.wrbug.polymarketbot.entity.LargeBetWatchRecord
import com.wrbug.polymarketbot.repository.LargeBetWatchRecordRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class LargeBetWatchRecordService(
    private val repository: LargeBetWatchRecordRepository
) {

    suspend fun listRecords(): List<LargeBetWatchRecordDto> {
        return withContext(Dispatchers.IO) {
            repository.findAllByOrderByLastTriggeredAtDesc().map { entityToDto(it) }
        }
    }

    @Transactional
    suspend fun clearRecords() {
        withContext(Dispatchers.IO) {
            repository.deleteAllInBatch()
        }
    }

    @Transactional
    suspend fun upsert(
        event: LargeBetTradeEvent,
        triggerReason: String,
        singleAmount: BigDecimal,
        cumulativeAmount: BigDecimal
    ): LargeBetWatchRecordDto {
        val address = event.traderAddress.lowercase()
        val now = System.currentTimeMillis()
        val saved = withContext(Dispatchers.IO) {
            val existing = repository.findByTraderAddressAndMarketIdAndOutcome(
                address,
                event.marketId,
                event.outcome
            )
            val record = if (existing == null) {
                LargeBetWatchRecord(
                    traderAddress = address,
                    traderName = event.traderName,
                    profileUrl = profileUrl(address),
                    marketId = event.marketId,
                    marketSlug = event.marketSlug,
                    marketTitle = event.marketTitle,
                    sportType = event.sportType,
                    outcome = event.outcome,
                    triggerReason = triggerReason,
                    lastSingleAmount = singleAmount.setScale(8, RoundingMode.DOWN),
                    lastCumulativeAmount = cumulativeAmount.setScale(8, RoundingMode.DOWN),
                    firstTriggeredAt = event.timestampMillis,
                    lastTriggeredAt = event.timestampMillis,
                    triggerCount = 1,
                    createdAt = now,
                    updatedAt = now
                )
            } else {
                existing.copy(
                    traderName = event.traderName ?: existing.traderName,
                    profileUrl = profileUrl(address),
                    marketSlug = event.marketSlug,
                    marketTitle = event.marketTitle,
                    sportType = event.sportType,
                    triggerReason = triggerReason,
                    lastSingleAmount = singleAmount.setScale(8, RoundingMode.DOWN),
                    lastCumulativeAmount = cumulativeAmount.setScale(8, RoundingMode.DOWN),
                    lastTriggeredAt = event.timestampMillis,
                    triggerCount = existing.triggerCount + 1,
                    updatedAt = now
                )
            }
            repository.save(record)
        }
        return entityToDto(saved)
    }

    private fun profileUrl(address: String): String = "https://polymarket.com/profile/$address"

    private fun entityToDto(entity: LargeBetWatchRecord): LargeBetWatchRecordDto {
        return LargeBetWatchRecordDto(
            id = entity.id,
            traderName = entity.traderName,
            traderAddress = entity.traderAddress,
            profileUrl = entity.profileUrl,
            marketTitle = entity.marketTitle,
            marketSlug = entity.marketSlug,
            marketId = entity.marketId,
            sportType = entity.sportType,
            outcome = entity.outcome,
            triggerReason = entity.triggerReason,
            lastSingleAmount = entity.lastSingleAmount.setScale(8, RoundingMode.DOWN).toPlainString(),
            lastCumulativeAmount = entity.lastCumulativeAmount.setScale(8, RoundingMode.DOWN).toPlainString(),
            firstTriggeredAt = entity.firstTriggeredAt,
            lastTriggeredAt = entity.lastTriggeredAt,
            triggerCount = entity.triggerCount
        )
    }
}
