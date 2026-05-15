package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.SystemConfigDto
import com.wrbug.polymarketbot.entity.SystemConfig
import com.wrbug.polymarketbot.repository.SystemConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SystemConfigService(
    private val systemConfigRepository: SystemConfigRepository
) {
    private val logger = LoggerFactory.getLogger(SystemConfigService::class.java)

    companion object {
        const val CONFIG_KEY_LIVE_OBSERVATION_MINUTES = "odds_monitor.live_observation_minutes"
        private const val MAX_LIVE_OBSERVATION_MINUTES = 180

        fun configKeys(): Set<String> = setOf(CONFIG_KEY_LIVE_OBSERVATION_MINUTES)
    }

    fun getSystemConfig(): SystemConfigDto {
        return SystemConfigDto(liveObservationMinutes = getLiveObservationMinutes())
    }

    fun getLiveObservationMinutes(): Int? {
        return getConfigValue(CONFIG_KEY_LIVE_OBSERVATION_MINUTES)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
            ?.takeIf { it > 0 && it <= MAX_LIVE_OBSERVATION_MINUTES }
    }

    @Transactional
    fun updateLiveObservationMinutes(minutes: Int?): Result<SystemConfigDto> {
        return try {
            require(minutes == null || minutes > 0) { "liveObservationMinutes must be greater than 0" }
            require(minutes == null || minutes <= MAX_LIVE_OBSERVATION_MINUTES) {
                "liveObservationMinutes cannot exceed $MAX_LIVE_OBSERVATION_MINUTES"
            }
            updateConfigValue(CONFIG_KEY_LIVE_OBSERVATION_MINUTES, minutes?.toString())
            Result.success(getSystemConfig())
        } catch (e: Exception) {
            logger.error("Failed to update odds monitor live observation minutes", e)
            Result.failure(e)
        }
    }

    private fun getConfigValue(configKey: String): String? {
        return systemConfigRepository.findByConfigKey(configKey)?.configValue
    }

    private fun updateConfigValue(configKey: String, configValue: String?) {
        val existing = systemConfigRepository.findByConfigKey(configKey)
        if (existing != null) {
            systemConfigRepository.save(
                existing.copy(
                    configValue = configValue,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return
        }

        systemConfigRepository.save(
            SystemConfig(
                configKey = configKey,
                configValue = configValue,
                description = when (configKey) {
                    CONFIG_KEY_LIVE_OBSERVATION_MINUTES -> "赔率监控滚球观察分钟限制；空值表示不限制"
                    else -> null
                }
            )
        )
    }
}
