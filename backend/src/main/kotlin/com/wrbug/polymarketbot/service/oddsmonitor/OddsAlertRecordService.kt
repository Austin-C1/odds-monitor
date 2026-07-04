package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsAlertRecordDto
import com.wrbug.polymarketbot.repository.OddsAlertRecordRepository
import org.springframework.stereotype.Service

@Service
class OddsAlertRecordService(
    private val alertRecordRepository: OddsAlertRecordRepository,
    private val displayMapper: OddsMonitorDisplayMapper
) {
    fun listRecords(): List<OddsAlertRecordDto> {
        return alertRecordRepository.findTop100ByOrderByCreatedAtDesc().map {
            OddsAlertRecordDto(
                id = it.id ?: 0,
                alertType = it.alertType,
                severity = it.severity,
                matchName = it.matchId?.toString(),
                sourceKey = it.sourceKey,
                title = displayMapper.alertTitle(it.title, it.message),
                message = displayMapper.alertMessage(it.message),
                createdAt = it.createdAt,
                acknowledged = it.acknowledged
            )
        }
    }
}
