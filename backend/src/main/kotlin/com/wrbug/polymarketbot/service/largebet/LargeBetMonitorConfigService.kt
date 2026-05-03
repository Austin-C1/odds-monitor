package com.wrbug.polymarketbot.service.largebet

import com.wrbug.polymarketbot.dto.LargeBetMonitorConfigDto
import com.wrbug.polymarketbot.dto.LargeBetMonitorConfigUpdateRequest
import com.wrbug.polymarketbot.entity.LargeBetMonitorConfig
import com.wrbug.polymarketbot.repository.LargeBetMonitorConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class LargeBetMonitorConfigService(
    private val repository: LargeBetMonitorConfigRepository
) {

    suspend fun getConfig(): LargeBetMonitorConfigDto = entityToDto(getOrCreateEntity())

    suspend fun getConfigEntity(): LargeBetMonitorConfig = getOrCreateEntity()

    @Transactional
    suspend fun updateConfig(request: LargeBetMonitorConfigUpdateRequest): Result<LargeBetMonitorConfigDto> {
        return try {
            val singleThreshold = parsePositiveDecimal(request.singleTradeThreshold, "singleTradeThreshold")
            val cumulativeThreshold = parsePositiveDecimal(request.cumulativeTradeThreshold, "cumulativeTradeThreshold")
            require(request.rollingWindowMinutes in 1..1440) { "rollingWindowMinutes must be between 1 and 1440" }
            require(request.checkIntervalSeconds in 5..3600) { "checkIntervalSeconds must be between 5 and 3600" }
            require(!request.enabled || request.footballEnabled || request.basketballEnabled) {
                "At least one sport must be enabled"
            }

            val existing = getOrCreateEntity()
            val now = System.currentTimeMillis()
            val saved = withContext(Dispatchers.IO) {
                repository.save(
                    existing.copy(
                        enabled = request.enabled,
                        footballEnabled = request.footballEnabled,
                        basketballEnabled = request.basketballEnabled,
                        singleTradeThreshold = singleThreshold,
                        cumulativeTradeThreshold = cumulativeThreshold,
                        rollingWindowMinutes = request.rollingWindowMinutes,
                        checkIntervalSeconds = request.checkIntervalSeconds,
                        telegramConfigId = request.telegramConfigId,
                        updatedAt = now
                    )
                )
            }
            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getOrCreateEntity(): LargeBetMonitorConfig {
        return withContext(Dispatchers.IO) {
            repository.findAll().firstOrNull() ?: repository.save(LargeBetMonitorConfig())
        }
    }

    private fun parsePositiveDecimal(raw: String, fieldName: String): BigDecimal {
        val value = raw.trim().toBigDecimalOrNull() ?: throw IllegalArgumentException("$fieldName must be a decimal")
        require(value > BigDecimal.ZERO) { "$fieldName must be positive" }
        return value.setScale(8, RoundingMode.DOWN)
    }

    private fun entityToDto(entity: LargeBetMonitorConfig): LargeBetMonitorConfigDto {
        return LargeBetMonitorConfigDto(
            id = entity.id,
            enabled = entity.enabled,
            footballEnabled = entity.footballEnabled,
            basketballEnabled = entity.basketballEnabled,
            singleTradeThreshold = entity.singleTradeThreshold.setScale(8, RoundingMode.DOWN).toPlainString(),
            cumulativeTradeThreshold = entity.cumulativeTradeThreshold.setScale(8, RoundingMode.DOWN).toPlainString(),
            rollingWindowMinutes = entity.rollingWindowMinutes,
            checkIntervalSeconds = entity.checkIntervalSeconds,
            telegramConfigId = entity.telegramConfigId,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }
}
