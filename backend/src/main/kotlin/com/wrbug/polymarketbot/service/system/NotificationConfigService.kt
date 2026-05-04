package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.NotificationConfigRequest
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.entity.NotificationConfig
import com.wrbug.polymarketbot.repository.NotificationConfigRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class NotificationConfigService(
    private val notificationConfigRepository: NotificationConfigRepository,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(NotificationConfigService::class.java)
    private val MAX_COMBINED_WATER = java.math.BigDecimal("2")

    suspend fun getAllConfigs(): List<NotificationConfigDto> {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findAll().map { entityToDto(it) }
        }
    }

    suspend fun getConfigsByType(type: String): List<NotificationConfigDto> {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findByType(type).map { entityToDto(it) }
        }
    }

    suspend fun getEnabledConfigsByType(type: String): List<NotificationConfigDto> {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findByTypeAndEnabled(type, true).map { entityToDto(it) }
        }
    }

    suspend fun getConfigById(id: Long): NotificationConfigDto? {
        return withContext(Dispatchers.IO) {
            notificationConfigRepository.findById(id).orElse(null)?.let { entityToDto(it) }
        }
    }

    @Transactional
    suspend fun createConfig(request: NotificationConfigRequest): Result<NotificationConfigDto> {
        return try {
            validateConfig(request.type, request.config)

            val configJson = objectMapper.writeValueAsString(request.config)
            val config = NotificationConfig(
                type = request.type,
                name = request.name,
                enabled = request.enabled ?: true,
                configJson = configJson
            )

            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(config)
            }

            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("Failed to create notification config: {}", e.message, e)
            Result.failure(e)
        }
    }

    @Transactional
    suspend fun updateConfig(id: Long, request: NotificationConfigRequest): Result<NotificationConfigDto> {
        return try {
            val existing = withContext(Dispatchers.IO) {
                notificationConfigRepository.findById(id).orElse(null)
            } ?: return Result.failure(IllegalArgumentException("Config not found"))

            validateConfig(request.type, request.config)

            val configJson = objectMapper.writeValueAsString(request.config)
            val updated = existing.copy(
                type = request.type,
                name = request.name,
                enabled = request.enabled ?: existing.enabled,
                configJson = configJson,
                updatedAt = System.currentTimeMillis()
            )

            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(updated)
            }

            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("Failed to update notification config: {}", e.message, e)
            Result.failure(e)
        }
    }

    @Transactional
    suspend fun updateEnabled(id: Long, enabled: Boolean): Result<NotificationConfigDto> {
        return try {
            val existing = withContext(Dispatchers.IO) {
                notificationConfigRepository.findById(id).orElse(null)
            } ?: return Result.failure(IllegalArgumentException("Config not found"))

            val updated = existing.copy(
                enabled = enabled,
                updatedAt = System.currentTimeMillis()
            )

            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(updated)
            }

            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("Failed to update notification enabled status: {}", e.message, e)
            Result.failure(e)
        }
    }

    @Transactional
    suspend fun deleteConfig(id: Long): Result<Unit> {
        return try {
            withContext(Dispatchers.IO) {
                notificationConfigRepository.deleteById(id)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to delete notification config: {}", e.message, e)
            Result.failure(e)
        }
    }

    @Transactional
    suspend fun updateTelegramLiveOnlyMode(id: Long, liveOnlyModeEnabled: Boolean): Result<NotificationConfigDto> {
        return try {
            val existing = withContext(Dispatchers.IO) {
                notificationConfigRepository.findById(id).orElse(null)
            } ?: return Result.failure(IllegalArgumentException("Config not found"))

            require(existing.type.equals("telegram", ignoreCase = true)) {
                "Config is not a Telegram config"
            }

            @Suppress("UNCHECKED_CAST")
            val configMap = try {
                objectMapper.readValue(existing.configJson, MutableMap::class.java) as MutableMap<String, Any>
            } catch (e: Exception) {
                mutableMapOf()
            }
            configMap["liveOnlyModeEnabled"] = liveOnlyModeEnabled

            val updated = existing.copy(
                configJson = objectMapper.writeValueAsString(configMap),
                updatedAt = System.currentTimeMillis()
            )

            val saved = withContext(Dispatchers.IO) {
                notificationConfigRepository.save(updated)
            }

            Result.success(entityToDto(saved))
        } catch (e: Exception) {
            logger.error("Failed to update Telegram live-only mode: {}", e.message, e)
            Result.failure(e)
        }
    }

    private fun validateConfig(type: String, config: Map<String, Any>) {
        when (type.lowercase()) {
            "telegram" -> validateTelegramConfig(config)
        }
    }

    private fun validateTelegramConfig(config: Map<String, Any>) {
        val botToken = config["botToken"] as? String
        val chatIds = config["chatIds"]

        require(!botToken.isNullOrBlank()) { "Telegram Bot Token cannot be blank" }
        require(chatIds != null) { "Telegram Chat IDs cannot be blank" }

        val chatIdList = when (chatIds) {
            is List<*> -> chatIds.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
            is String -> chatIds.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> throw IllegalArgumentException("Chat IDs must be a list or a comma-separated string")
        }

        require(chatIdList.isNotEmpty()) { "At least one Chat ID is required" }

        val monitorModeEnabled = config["monitorModeEnabled"]
        require(monitorModeEnabled == null || monitorModeEnabled is Boolean) {
            "monitorModeEnabled must be a boolean"
        }
        val liveOnlyModeEnabled = config["liveOnlyModeEnabled"]
        require(liveOnlyModeEnabled == null || liveOnlyModeEnabled is Boolean) {
            "liveOnlyModeEnabled must be a boolean"
        }
        requireOptionalPrematchWindow(config["prematchWindowMinutes"], "prematchWindowMinutes")
        val marketBettingQueryEnabled = config["marketBettingQueryEnabled"]
        require(marketBettingQueryEnabled == null || marketBettingQueryEnabled is Boolean) {
            "marketBettingQueryEnabled must be a boolean"
        }
        val marketBettingDailyReportEnabled = config["marketBettingDailyReportEnabled"]
        require(marketBettingDailyReportEnabled == null || marketBettingDailyReportEnabled is Boolean) {
            "marketBettingDailyReportEnabled must be a boolean"
        }
        val marketBettingDailyReportTime = config["marketBettingDailyReportTime"]
        require(marketBettingDailyReportTime == null || marketBettingDailyReportTime is String) {
            "marketBettingDailyReportTime must be a string"
        }
        require(marketBettingDailyReportTime == null || isValidDailyReportTime(marketBettingDailyReportTime)) {
            "marketBettingDailyReportTime must use HH:mm format"
        }
        requireOptionalWaterLimit(config["handicapCombinedWaterMin"], "handicapCombinedWaterMin")
        requireOptionalWaterLimit(config["totalCombinedWaterMin"], "totalCombinedWaterMin")
        requireOptionalOddsMoveLimit(config["handicapOddsMoveMin"], "handicapOddsMoveMin")
        requireOptionalOddsMoveLimit(config["totalOddsMoveMin"], "totalOddsMoveMin")
        requireOptionalOddsMoveLimit(config["moneylineOddsMoveMin"], "moneylineOddsMoveMin")
        requireConfigStringList(config["copyTradingCategories"], "copyTradingCategories")
        requireConfigStringList(config["copyTradingNotificationTypes"], "copyTradingNotificationTypes")
        requireConfigStringList(config["copyTradingLeaderGroups"], "copyTradingLeaderGroups")
    }

    private fun requireOptionalWaterLimit(value: Any?, fieldName: String) {
        if (value == null || value == "") return
        val numericValue = value.toString().toBigDecimalOrNull()
        require(numericValue != null) { "$fieldName must be a number" }
        require(numericValue >= java.math.BigDecimal.ZERO) { "$fieldName cannot be negative" }
        require(numericValue <= MAX_COMBINED_WATER) { "$fieldName cannot exceed 2" }
    }

    private fun requireOptionalOddsMoveLimit(value: Any?, fieldName: String) {
        if (value == null || value == "") return
        val numericValue = value.toString().toBigDecimalOrNull()
        require(numericValue != null) { "$fieldName must be a number" }
        require(numericValue >= java.math.BigDecimal.ZERO) { "$fieldName cannot be negative" }
        require(numericValue <= MAX_COMBINED_WATER) { "$fieldName cannot exceed 2" }
    }

    private fun requireOptionalPrematchWindow(value: Any?, fieldName: String) {
        if (value == null || value == "") return
        val numericValue = value.toString().toIntOrNull()
        require(numericValue != null) { "$fieldName must be an integer" }
        require(numericValue > 0) { "$fieldName must be greater than 0" }
        require(numericValue <= 7 * 24 * 60) { "$fieldName cannot exceed 10080" }
    }

    private fun requireConfigStringList(value: Any?, fieldName: String) {
        require(value == null || value is List<*> || value is String) {
            "$fieldName must be a list or a comma-separated string"
        }
    }

    private fun entityToDto(entity: NotificationConfig): NotificationConfigDto {
        val configMap = try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(entity.configJson, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            logger.error("Failed to parse notification config JSON: {}", e.message, e)
            emptyMap()
        }

        val configData = when (entity.type.lowercase()) {
            "telegram" -> {
                val botToken = configMap["botToken"]?.toString() ?: ""
                val chatIds = when (val ids = configMap["chatIds"]) {
                    is List<*> -> ids.mapNotNull { it?.toString() }
                    is String -> ids.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    else -> emptyList()
                }
                val monitorModeEnabled = when (val raw = configMap["monitorModeEnabled"]) {
                    is Boolean -> raw
                    is String -> raw.equals("true", ignoreCase = true)
                    else -> false
                }
                val liveOnlyModeEnabled = when (val raw = configMap["liveOnlyModeEnabled"]) {
                    is Boolean -> raw
                    is String -> raw.equals("true", ignoreCase = true)
                    else -> false
                }
                val prematchWindowMinutes = normalizePrematchWindow(configMap["prematchWindowMinutes"])
                val marketBettingQueryEnabled = when (val raw = configMap["marketBettingQueryEnabled"]) {
                    is Boolean -> raw
                    is String -> raw.equals("true", ignoreCase = true)
                    else -> false
                }
                val marketBettingDailyReportEnabled = when (val raw = configMap["marketBettingDailyReportEnabled"]) {
                    is Boolean -> raw
                    is String -> raw.equals("true", ignoreCase = true)
                    else -> false
                }
                val marketBettingDailyReportTime = normalizeDailyReportTime(configMap["marketBettingDailyReportTime"]?.toString())
                val handicapCombinedWaterMin = normalizeWaterLimit(configMap["handicapCombinedWaterMin"])
                val totalCombinedWaterMin = normalizeWaterLimit(configMap["totalCombinedWaterMin"])
                val handicapOddsMoveMin = normalizeWaterLimit(configMap["handicapOddsMoveMin"])
                val totalOddsMoveMin = normalizeWaterLimit(configMap["totalOddsMoveMin"])
                val moneylineOddsMoveMin = normalizeWaterLimit(configMap["moneylineOddsMoveMin"])
                val copyTradingCategories = parseStringList(configMap["copyTradingCategories"])
                val copyTradingNotificationTypes = parseStringList(configMap["copyTradingNotificationTypes"])
                val copyTradingLeaderGroups = parseStringList(configMap["copyTradingLeaderGroups"])
                NotificationConfigData.Telegram(
                    TelegramConfigData(
                        botToken = botToken,
                        chatIds = chatIds,
                        monitorModeEnabled = monitorModeEnabled,
                        liveOnlyModeEnabled = liveOnlyModeEnabled,
                        prematchWindowMinutes = prematchWindowMinutes,
                        marketBettingQueryEnabled = marketBettingQueryEnabled,
                        marketBettingDailyReportEnabled = marketBettingDailyReportEnabled,
                        marketBettingDailyReportTime = marketBettingDailyReportTime,
                        handicapCombinedWaterMin = handicapCombinedWaterMin,
                        totalCombinedWaterMin = totalCombinedWaterMin,
                        handicapOddsMoveMin = handicapOddsMoveMin,
                        totalOddsMoveMin = totalOddsMoveMin,
                        moneylineOddsMoveMin = moneylineOddsMoveMin,
                        copyTradingLeaderGroups = copyTradingLeaderGroups,
                        copyTradingCategories = copyTradingCategories,
                        copyTradingNotificationTypes = copyTradingNotificationTypes
                    )
                )
            }

            else -> NotificationConfigData.Telegram(TelegramConfigData("", emptyList(), false))
        }

        return NotificationConfigDto(
            id = entity.id,
            type = entity.type,
            name = entity.name,
            enabled = entity.enabled,
            config = configData,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )
    }

    private fun parseStringList(value: Any?): List<String> {
        val rawItems = when (value) {
            is List<*> -> value.mapNotNull { it?.toString() }
            is String -> value.split(",")
            else -> emptyList()
        }

        return rawItems
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it != "all" }
            .distinct()
    }

    private fun normalizeWaterLimit(value: Any?): String? {
        if (value == null || value == "") return null
        val normalized = value.toString().trim().toBigDecimalOrNull() ?: return null
        return normalized.stripTrailingZeros().toPlainString()
    }

    private fun normalizePrematchWindow(value: Any?): Int? {
        if (value == null || value == "") return null
        return value.toString().trim().toIntOrNull()?.takeIf { it > 0 }
    }

    private fun isValidDailyReportTime(value: Any?): Boolean {
        return normalizeDailyReportTime(value?.toString()) == value?.toString()?.trim()
    }

    private fun normalizeDailyReportTime(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (Regex("""^([01]\d|2[0-3]):[0-5]\d$""").matches(trimmed)) trimmed else "02:00"
    }
}
