package com.wrbug.polymarketbot.service.oddsmonitor

import com.wrbug.polymarketbot.dto.OddsDataSourceConfigDto
import com.wrbug.polymarketbot.dto.OddsDataSourceStatusDto
import com.wrbug.polymarketbot.entity.OddsDataSourceConfig
import com.wrbug.polymarketbot.repository.OddsCollectionLogRepository
import com.wrbug.polymarketbot.repository.OddsDataSourceConfigRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OddsDataSourceService(
    private val dataSourceConfigRepository: OddsDataSourceConfigRepository,
    private val collectionLogRepository: OddsCollectionLogRepository,
    private val oddsChangeNotificationService: OddsChangeNotificationService? = null,
    private val displayMapper: OddsMonitorDisplayMapper = OddsMonitorDisplayMapper()
) {
    private val defaultSources = listOf(
        OddsDataSourceConfigDto("crown", "皇冠", false, intervalSeconds = 60, updatedAt = 0)
    )

    fun listConfigs(): List<OddsDataSourceConfigDto> {
        val existing = dataSourceConfigRepository.findAll().associateBy { it.sourceKey }
        return defaultSources.map { default ->
            existing[default.sourceKey]?.toDto() ?: default.copy(updatedAt = System.currentTimeMillis())
        }
    }

    @Transactional
    fun saveConfigs(configs: List<OddsDataSourceConfigDto>): List<OddsDataSourceConfigDto> {
        val supportedSourceKeys = defaultSources.map { it.sourceKey }.toSet()
        configs.filter { it.sourceKey in supportedSourceKeys }.forEach { incoming ->
            val normalized = normalizeConfig(incoming)
            val existing = dataSourceConfigRepository.findBySourceKey(normalized.sourceKey)
            val savedConfig = OddsDataSourceConfig(
                id = existing?.id,
                sourceKey = normalized.sourceKey,
                displayName = normalized.displayName,
                enabled = normalized.enabled,
                username = normalized.username?.takeIf { it.isNotBlank() } ?: existing?.username,
                password = passwordValue(normalized.password) ?: existing?.password,
                queryKeyword = normalized.queryKeyword?.takeIf { it.isNotBlank() } ?: existing?.queryKeyword,
                intervalSeconds = normalized.intervalSeconds.coerceAtLeast(10),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            dataSourceConfigRepository.save(savedConfig)
            if (existing?.enabled == true && !savedConfig.enabled) {
                oddsChangeNotificationService?.clearSourceState(savedConfig.sourceKey)
            }
        }
        return listConfigs()
    }

    fun listStatuses(): List<OddsDataSourceStatusDto> {
        return listConfigs().map { config ->
            val lastLog = collectionLogRepository.findTop1BySourceKeyOrderByStartedAtDesc(config.sourceKey)
            val lastSuccess = collectionLogRepository.findTop1BySourceKeyAndStatusOrderByStartedAtDesc(
                config.sourceKey,
                "success"
            )
            val lastFailure = collectionLogRepository.findTop1FailureBySourceKey(config.sourceKey)
                ?.takeIf { lastLog?.status != "success" }
            OddsDataSourceStatusDto(
                sourceKey = config.sourceKey,
                displayName = displayMapper.sourceDisplayName(config.sourceKey, config.displayName),
                enabled = config.enabled,
                currentStatus = if (!config.enabled) "disabled" else lastLog?.status ?: "waiting",
                lastCollectTime = lastLog?.startedAt,
                lastSuccessTime = lastSuccess?.startedAt,
                lastFailureTime = lastFailure?.startedAt,
                failureReason = lastFailure?.message
            )
        }
    }

    private fun normalizeConfig(config: OddsDataSourceConfigDto): OddsDataSourceConfigDto {
        val default = defaultSources.firstOrNull { it.sourceKey == config.sourceKey }
        return config.copy(
            displayName = displayMapper.sourceDisplayName(
                config.sourceKey,
                config.displayName.ifBlank { default?.displayName ?: config.sourceKey }
            ),
            intervalSeconds = config.intervalSeconds.coerceAtLeast(10)
        )
    }

    private fun passwordValue(value: String?): String? {
        return value?.takeIf { it.isNotBlank() }
    }

    private fun OddsDataSourceConfig.toDto(): OddsDataSourceConfigDto {
        return OddsDataSourceConfigDto(
            sourceKey = sourceKey,
            displayName = displayMapper.sourceDisplayName(sourceKey, displayName),
            enabled = enabled,
            username = username,
            password = password,
            queryKeyword = queryKeyword,
            intervalSeconds = intervalSeconds,
            updatedAt = updatedAt
        )
    }
}
