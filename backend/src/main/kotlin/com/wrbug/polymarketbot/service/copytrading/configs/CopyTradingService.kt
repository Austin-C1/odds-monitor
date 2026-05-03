package com.wrbug.polymarketbot.service.copytrading.configs

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wrbug.polymarketbot.dto.AccountTemplateDto
import com.wrbug.polymarketbot.dto.AccountTemplatesResponse
import com.wrbug.polymarketbot.dto.CopyTradingCreateRequest
import com.wrbug.polymarketbot.dto.CopyTradingDto
import com.wrbug.polymarketbot.dto.CopyTradingListRequest
import com.wrbug.polymarketbot.dto.CopyTradingListResponse
import com.wrbug.polymarketbot.dto.CopyTradingNotificationRouteDto
import com.wrbug.polymarketbot.dto.CopyTradingUpdateRequest
import com.wrbug.polymarketbot.dto.CopyTradingUpdateStatusRequest
import com.wrbug.polymarketbot.dto.FollowAmountRuleDto
import com.wrbug.polymarketbot.dto.FollowAmountRuleSaveItem
import com.wrbug.polymarketbot.dto.FollowSettingsResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.CopyTradingFollowRule
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyTradingFollowRuleRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.monitor.CopyTradingMonitoringRefreshEvent
import com.wrbug.polymarketbot.util.JsonUtils
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class CopyTradingService(
    private val copyTradingRepository: CopyTradingRepository,
    private val copyTradingFollowRuleRepository: CopyTradingFollowRuleRepository,
    private val accountRepository: AccountRepository,
    private val templateRepository: CopyTradingTemplateRepository,
    private val leaderRepository: LeaderRepository,
    private val leaderCopyTradingControlRepository: LeaderCopyTradingControlRepository,
    private val applicationEventPublisher: ApplicationEventPublisher,
    private val jsonUtils: JsonUtils,
    private val gson: Gson
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(CopyTradingService::class.java)
    private var applicationContext: ApplicationContext? = null

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    private fun getSelf(): CopyTradingService {
        return applicationContext?.getBean(CopyTradingService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }

    @Transactional
    fun createCopyTrading(request: CopyTradingCreateRequest): Result<CopyTradingDto> {
        return try {
            val account = accountRepository.findById(request.accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Account not found"))
            val leader = leaderRepository.findById(request.leaderId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Leader not found"))

            val configName = request.configName?.trim()
            if (configName.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Config name is required"))
            }

            val config = buildConfig(request)
            val enabled = request.enabled && !isLeaderGroupPaused(request.leaderId)
            val copyTrading = CopyTrading(
                accountId = request.accountId,
                leaderId = request.leaderId,
                enabled = enabled,
                followSettingsEnabled = request.followSettingsEnabled ?: false,
                maxOrderSize = config.maxOrderSize,
                minOrderSize = config.minOrderSize,
                maxDailyLoss = config.maxDailyLoss,
                maxDailyOrders = config.maxDailyOrders,
                priceTolerance = config.priceTolerance,
                delaySeconds = config.delaySeconds,
                pollIntervalSeconds = config.pollIntervalSeconds,
                useWebSocket = config.useWebSocket,
                websocketReconnectInterval = config.websocketReconnectInterval,
                websocketMaxRetries = config.websocketMaxRetries,
                supportSell = config.supportSell,
                minOrderDepth = config.minOrderDepth,
                maxSpread = config.maxSpread,
                minPrice = config.minPrice,
                maxPrice = config.maxPrice,
                maxPositionValue = config.maxPositionValue,
                keywordFilterMode = config.keywordFilterMode,
                keywords = config.keywords,
                configName = configName,
                pushFailedOrders = request.pushFailedOrders ?: false,
                pushFilteredOrders = config.pushFilteredOrders,
                notificationRoutes = convertNotificationRoutesToJson(request.notificationRoutes),
                maxMarketEndDate = config.maxMarketEndDate
            )

            val saved = copyTradingRepository.save(copyTrading)
            if (saved.enabled) {
                publishMonitoringRefresh(saved.leaderId, listOf(saved.accountId))
            }

            Result.success(toDto(saved, account, leader))
        } catch (e: Exception) {
            logger.error("Failed to create copy trading config", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun updateCopyTrading(request: CopyTradingUpdateRequest): Result<CopyTradingDto> {
        return try {
            val existing = copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Copy trading config not found"))

            val configName = when (request.configName) {
                null -> existing.configName
                else -> request.configName.trim().ifBlank {
                    return Result.failure(IllegalArgumentException("Config name is required"))
                }
            }

            val nextEnabled = request.enabled ?: existing.enabled
            if (nextEnabled && isLeaderGroupPaused(existing.leaderId)) {
                return Result.failure(IllegalStateException("Leader group is paused, restart the group first"))
            }

            val updated = existing.copy(
                enabled = nextEnabled,
                followSettingsEnabled = mergeOptionalBoolean(request.followSettingsEnabled, existing.followSettingsEnabled),
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: existing.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: existing.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: existing.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: existing.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: existing.priceTolerance,
                delaySeconds = request.delaySeconds ?: existing.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: existing.pollIntervalSeconds,
                useWebSocket = mergeOptionalBoolean(request.useWebSocket, existing.useWebSocket),
                websocketReconnectInterval = request.websocketReconnectInterval ?: existing.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: existing.websocketMaxRetries,
                supportSell = mergeOptionalBoolean(request.supportSell, existing.supportSell),
                minOrderDepth = mergeOptionalBigDecimal(request.minOrderDepth, existing.minOrderDepth),
                maxSpread = mergeOptionalBigDecimal(request.maxSpread, existing.maxSpread),
                minPrice = mergeOptionalBigDecimal(request.minPrice, existing.minPrice),
                maxPrice = mergeOptionalBigDecimal(request.maxPrice, existing.maxPrice),
                maxPositionValue = mergeOptionalBigDecimal(request.maxPositionValue, existing.maxPositionValue),
                keywordFilterMode = request.keywordFilterMode ?: existing.keywordFilterMode,
                keywords = when {
                    request.keywords != null -> convertKeywordsToJson(request.keywords)
                    request.keywordFilterMode == "DISABLED" -> null
                    else -> existing.keywords
                },
                configName = configName,
                pushFailedOrders = mergeOptionalBoolean(request.pushFailedOrders, existing.pushFailedOrders),
                pushFilteredOrders = mergeOptionalBoolean(request.pushFilteredOrders, existing.pushFilteredOrders),
                notificationRoutes = if (request.notificationRoutes != null) {
                    convertNotificationRoutesToJson(request.notificationRoutes)
                } else {
                    existing.notificationRoutes
                },
                maxMarketEndDate = when (request.maxMarketEndDate) {
                    null -> existing.maxMarketEndDate
                    -1L -> null
                    else -> request.maxMarketEndDate
                },
                updatedAt = System.currentTimeMillis()
            )

            val saved = copyTradingRepository.save(updated)
            publishMonitoringRefresh(saved.leaderId, listOf(saved.accountId))

            val account = accountRepository.findById(saved.accountId).orElse(null)
                ?: return Result.failure(IllegalStateException("Account not found"))
            val leader = leaderRepository.findById(saved.leaderId).orElse(null)
                ?: return Result.failure(IllegalStateException("Leader not found"))

            Result.success(toDto(saved, account, leader))
        } catch (e: Exception) {
            logger.error("Failed to update copy trading config", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun updateCopyTradingStatus(request: CopyTradingUpdateStatusRequest): Result<CopyTradingDto> {
        return getSelf().updateCopyTrading(
            CopyTradingUpdateRequest(
                copyTradingId = request.copyTradingId,
                enabled = request.enabled
            )
        )
    }

    fun getCopyTradingList(request: CopyTradingListRequest): Result<CopyTradingListResponse> {
        return try {
            val copyTradings = when {
                request.accountId != null && request.leaderId != null ->
                    copyTradingRepository.findByAccountIdAndLeaderId(request.accountId, request.leaderId)
                request.accountId != null ->
                    copyTradingRepository.findByAccountId(request.accountId)
                request.leaderId != null ->
                    copyTradingRepository.findByLeaderId(request.leaderId)
                request.enabled == true ->
                    copyTradingRepository.findByEnabledTrue()
                else ->
                    copyTradingRepository.findAll()
            }.let { list ->
                if (request.enabled == null) list else list.filter { it.enabled == request.enabled }
            }

            val followRulesByCopyTradingId = copyTradingFollowRuleRepository
                .findByCopyTradingIdIn(copyTradings.mapNotNull { it.id })
                .groupBy { it.copyTradingId }
            val accountsById = accountRepository.findAllById(copyTradings.map { it.accountId }.distinct())
                .associateBy { it.id!! }
            val leadersById = leaderRepository.findAllById(copyTradings.map { it.leaderId }.distinct())
                .associateBy { it.id!! }

            val dtos = copyTradings.mapNotNull { copyTrading ->
                val account = accountsById[copyTrading.accountId]
                val leader = leadersById[copyTrading.leaderId]
                if (account == null || leader == null) {
                    logger.warn("Skip broken copy trading record: {}", copyTrading.id)
                    null
                } else {
                    toDto(copyTrading, account, leader, followRulesByCopyTradingId[copyTrading.id] ?: emptyList())
                }
            }

            Result.success(CopyTradingListResponse(dtos, dtos.size.toLong()))
        } catch (e: Exception) {
            logger.error("Failed to fetch copy trading list", e)
            Result.failure(e)
        }
    }

    fun getCopyTradingDetail(copyTradingId: Long): Result<CopyTradingDto> {
        return try {
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Copy trading config not found"))
            val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                ?: return Result.failure(IllegalStateException("Account not found"))
            val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
                ?: return Result.failure(IllegalStateException("Leader not found"))
            val followRules = copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(copyTradingId)
            Result.success(toDto(copyTrading, account, leader, followRules))
        } catch (e: Exception) {
            logger.error("Failed to fetch copy trading detail", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun deleteCopyTrading(copyTradingId: Long): Result<Unit> {
        return try {
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Copy trading config not found"))

            copyTradingRepository.delete(copyTrading)
            publishMonitoringRefresh(copyTrading.leaderId, listOf(copyTrading.accountId))
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("Failed to delete copy trading config", e)
            Result.failure(e)
        }
    }

    fun getAccountTemplates(accountId: Long): Result<AccountTemplatesResponse> {
        return try {
            accountRepository.findById(accountId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Account not found"))

            val copyTradings = copyTradingRepository.findByAccountId(accountId)
            val leadersById = leaderRepository.findAllById(
                copyTradings.map { it.leaderId }.distinct()
            ).associateBy { it.id!! }
            val list = copyTradings.mapNotNull { copyTrading ->
                val leader = leadersById[copyTrading.leaderId] ?: return@mapNotNull null
                AccountTemplateDto(
                    copyTradingId = copyTrading.id!!,
                    leaderId = leader.id!!,
                    leaderName = leader.leaderName,
                    leaderAddress = leader.leaderAddress,
                    enabled = copyTrading.enabled
                )
            }
            Result.success(AccountTemplatesResponse(list, list.size.toLong()))
        } catch (e: Exception) {
            logger.error("Failed to fetch account copy trading list", e)
            Result.failure(e)
        }
    }

    fun getFollowSettings(copyTradingId: Long): Result<FollowSettingsResponse> {
        return try {
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Copy trading config not found"))
            val rules = copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(copyTradingId)
                .map(::toFollowRuleDto)
            Result.success(FollowSettingsResponse(copyTradingId, copyTrading.followSettingsEnabled, rules))
        } catch (e: Exception) {
            logger.error("Failed to fetch follow settings", e)
            Result.failure(e)
        }
    }

    @Transactional
    fun saveFollowSettings(
        copyTradingId: Long,
        enabled: Boolean,
        rules: List<FollowAmountRuleSaveItem>
    ): Result<FollowSettingsResponse> {
        return try {
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("Copy trading config not found"))

            val normalizedRules = validateAndNormalizeRules(copyTradingId, enabled, rules)
            copyTradingFollowRuleRepository.deleteByCopyTradingId(copyTradingId)
            if (normalizedRules.isNotEmpty()) {
                copyTradingFollowRuleRepository.saveAll(normalizedRules)
            }

            copyTradingRepository.save(
                copyTrading.copy(
                    followSettingsEnabled = enabled,
                    updatedAt = System.currentTimeMillis()
                )
            )

            Result.success(
                FollowSettingsResponse(
                    copyTradingId = copyTradingId,
                    enabled = enabled,
                    rules = normalizedRules.map(::toFollowRuleDto)
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to save follow settings", e)
            Result.failure(e)
        }
    }

    private fun buildConfig(request: CopyTradingCreateRequest): CopyTradingConfig {
        return if (request.templateId != null) {
            val template = templateRepository.findById(request.templateId).orElse(null)
                ?: throw IllegalArgumentException("Template not found")
            CopyTradingConfig(
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: template.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: template.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: template.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: template.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: template.priceTolerance,
                delaySeconds = request.delaySeconds ?: template.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: template.pollIntervalSeconds,
                useWebSocket = mergeOptionalBoolean(request.useWebSocket, template.useWebSocket),
                websocketReconnectInterval = request.websocketReconnectInterval ?: template.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: template.websocketMaxRetries,
                supportSell = mergeOptionalBoolean(request.supportSell, template.supportSell),
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal() ?: template.minOrderDepth,
                maxSpread = request.maxSpread?.toSafeBigDecimal() ?: template.maxSpread,
                minPrice = request.minPrice?.toSafeBigDecimal() ?: template.minPrice,
                maxPrice = request.maxPrice?.toSafeBigDecimal() ?: template.maxPrice,
                maxPositionValue = request.maxPositionValue?.toSafeBigDecimal(),
                keywordFilterMode = request.keywordFilterMode ?: "DISABLED",
                keywords = convertKeywordsToJson(request.keywords),
                maxMarketEndDate = request.maxMarketEndDate,
                pushFilteredOrders = mergeOptionalBoolean(request.pushFilteredOrders, template.pushFilteredOrders)
            )
        } else {
            CopyTradingConfig(
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: BigDecimal("1000"),
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: BigDecimal.ONE,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: BigDecimal("10000"),
                maxDailyOrders = request.maxDailyOrders ?: 100,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: BigDecimal("5"),
                delaySeconds = request.delaySeconds ?: 0,
                pollIntervalSeconds = request.pollIntervalSeconds ?: 5,
                useWebSocket = mergeOptionalBoolean(request.useWebSocket, true),
                websocketReconnectInterval = request.websocketReconnectInterval ?: 5000,
                websocketMaxRetries = request.websocketMaxRetries ?: 10,
                supportSell = mergeOptionalBoolean(request.supportSell, true),
                minOrderDepth = request.minOrderDepth?.toSafeBigDecimal(),
                maxSpread = request.maxSpread?.toSafeBigDecimal(),
                minPrice = request.minPrice?.toSafeBigDecimal(),
                maxPrice = request.maxPrice?.toSafeBigDecimal(),
                maxPositionValue = request.maxPositionValue?.toSafeBigDecimal(),
                keywordFilterMode = request.keywordFilterMode ?: "DISABLED",
                keywords = convertKeywordsToJson(request.keywords),
                maxMarketEndDate = request.maxMarketEndDate,
                pushFilteredOrders = mergeOptionalBoolean(request.pushFilteredOrders, false)
            )
        }
    }

    private fun isLeaderGroupPaused(leaderId: Long): Boolean {
        val control = leaderCopyTradingControlRepository.findByLeaderId(leaderId) ?: return false
        return control.status != GROUP_STATUS_ACTIVE
    }

    private fun mergeOptionalBigDecimal(rawValue: String?, currentValue: BigDecimal?): BigDecimal? {
        return when {
            rawValue == null -> currentValue
            rawValue.isBlank() -> null
            else -> rawValue.toSafeBigDecimal()
        }
    }

    private fun mergeOptionalBoolean(rawValue: Boolean?, currentValue: Boolean): Boolean {
        return rawValue ?: currentValue
    }

    private fun convertKeywordsToJson(keywords: List<String>?): String? {
        if (keywords.isNullOrEmpty()) {
            return null
        }
        return try {
            gson.toJson(keywords)
        } catch (e: Exception) {
            logger.error("Failed to serialize keywords", e)
            null
        }
    }

    private fun convertJsonToKeywords(jsonString: String?): List<String>? {
        if (jsonString.isNullOrBlank()) {
            return null
        }
        return try {
            jsonUtils.parseStringArray(jsonString)
        } catch (e: Exception) {
            logger.error("Failed to parse keywords", e)
            null
        }
    }

    private fun convertNotificationRoutesToJson(routes: List<CopyTradingNotificationRouteDto>?): String? {
        val normalizedRoutes = routes
            ?.filter { it.telegramConfigId > 0 }
            ?.map {
                it.copy(
                    categories = it.categories.mapNotNull(::normalizeRouteValue).distinct(),
                    notificationTypes = it.notificationTypes.mapNotNull(::normalizeRouteValue).distinct()
                )
            }
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        return gson.toJson(normalizedRoutes)
    }

    private fun convertJsonToNotificationRoutes(jsonString: String?): List<CopyTradingNotificationRouteDto> {
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val type = object : TypeToken<List<CopyTradingNotificationRouteDto>>() {}.type
            gson.fromJson<List<CopyTradingNotificationRouteDto>>(jsonString, type).orEmpty()
                .filter { it.telegramConfigId > 0 }
        } catch (e: Exception) {
            logger.error("Failed to parse notification routes", e)
            emptyList()
        }
    }

    private fun normalizeRouteValue(value: String?): String? {
        return value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "all" }
    }

    private fun validateAndNormalizeRules(
        copyTradingId: Long,
        enabled: Boolean,
        rules: List<FollowAmountRuleSaveItem>
    ): List<CopyTradingFollowRule> {
        if (enabled && rules.isEmpty()) {
            throw IllegalArgumentException("At least one rule is required when follow settings are enabled")
        }

        return rules.mapIndexed { index, rule ->
            val minLeaderAmount = parseDecimal(rule.minLeaderAmount, "min leader amount")
            val maxLeaderAmount = rule.maxLeaderAmount?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { parseDecimal(it, "max leader amount") }
            val followAmount = parseDecimal(rule.followAmount, "follow amount")
            val followMaxAmount = parseDecimal(rule.followMaxAmount, "follow max amount")

            if (minLeaderAmount < BigDecimal.ZERO) {
                throw IllegalArgumentException("Rule ${index + 1}: min leader amount must be >= 0")
            }
            if (maxLeaderAmount != null && maxLeaderAmount <= minLeaderAmount) {
                throw IllegalArgumentException("Rule ${index + 1}: max leader amount must be greater than min leader amount")
            }
            if (followAmount <= BigDecimal.ZERO) {
                throw IllegalArgumentException("Rule ${index + 1}: follow amount must be greater than 0")
            }
            if (followMaxAmount <= BigDecimal.ZERO) {
                throw IllegalArgumentException("Rule ${index + 1}: max amount must be greater than 0")
            }
            if (followMaxAmount < followAmount) {
                throw IllegalArgumentException("Rule ${index + 1}: max amount must be greater than or equal to follow amount")
            }

            CopyTradingFollowRule(
                copyTradingId = copyTradingId,
                minLeaderAmount = minLeaderAmount,
                maxLeaderAmount = maxLeaderAmount,
                followAmount = followAmount,
                followMaxAmount = followMaxAmount,
                sortOrder = index + 1
            )
        }
    }

    private fun parseDecimal(rawValue: String, fieldName: String): BigDecimal {
        return try {
            BigDecimal(rawValue.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("$fieldName is invalid")
        }
    }

    private fun toDto(
        copyTrading: CopyTrading,
        account: Account,
        leader: Leader,
        followRules: List<CopyTradingFollowRule> = copyTradingFollowRuleRepository
            .findByCopyTradingIdOrderBySortOrderAsc(copyTrading.id!!)
    ): CopyTradingDto {
        return CopyTradingDto(
            id = copyTrading.id!!,
            accountId = account.id!!,
            accountName = account.accountName,
            walletAddress = account.walletAddress,
            leaderId = leader.id!!,
            leaderName = leader.leaderName,
            leaderAddress = leader.leaderAddress,
            enabled = copyTrading.enabled,
            followSettingsEnabled = copyTrading.followSettingsEnabled,
            maxOrderSize = copyTrading.maxOrderSize.toPlainString(),
            minOrderSize = copyTrading.minOrderSize.toPlainString(),
            maxDailyLoss = copyTrading.maxDailyLoss.toPlainString(),
            maxDailyOrders = copyTrading.maxDailyOrders,
            priceTolerance = copyTrading.priceTolerance.toPlainString(),
            delaySeconds = copyTrading.delaySeconds,
            pollIntervalSeconds = copyTrading.pollIntervalSeconds,
            useWebSocket = copyTrading.useWebSocket,
            websocketReconnectInterval = copyTrading.websocketReconnectInterval,
            websocketMaxRetries = copyTrading.websocketMaxRetries,
            supportSell = copyTrading.supportSell,
            minOrderDepth = copyTrading.minOrderDepth?.toPlainString(),
            maxSpread = copyTrading.maxSpread?.toPlainString(),
            minPrice = copyTrading.minPrice?.toPlainString(),
            maxPrice = copyTrading.maxPrice?.toPlainString(),
            maxPositionValue = copyTrading.maxPositionValue?.toPlainString(),
            keywordFilterMode = copyTrading.keywordFilterMode,
            keywords = convertJsonToKeywords(copyTrading.keywords),
            configName = copyTrading.configName,
            pushFailedOrders = copyTrading.pushFailedOrders,
            pushFilteredOrders = copyTrading.pushFilteredOrders,
            notificationRoutes = convertJsonToNotificationRoutes(copyTrading.notificationRoutes),
            maxMarketEndDate = copyTrading.maxMarketEndDate,
            followRules = followRules.map(::toFollowRuleDto),
            createdAt = copyTrading.createdAt,
            updatedAt = copyTrading.updatedAt
        )
    }

    private fun toFollowRuleDto(rule: CopyTradingFollowRule): FollowAmountRuleDto {
        return FollowAmountRuleDto(
            id = rule.id,
            minLeaderAmount = rule.minLeaderAmount.toPlainString(),
            maxLeaderAmount = rule.maxLeaderAmount?.toPlainString(),
            followAmount = rule.followAmount.toPlainString(),
            followMaxAmount = rule.followMaxAmount.toPlainString(),
            sortOrder = rule.sortOrder
        )
    }

    private fun publishMonitoringRefresh(leaderId: Long, accountIds: List<Long>) {
        applicationEventPublisher.publishEvent(
            CopyTradingMonitoringRefreshEvent(
                leaderId = leaderId,
                accountIds = accountIds.distinct()
            )
        )
    }

    private data class CopyTradingConfig(
        val maxOrderSize: BigDecimal,
        val minOrderSize: BigDecimal,
        val maxDailyLoss: BigDecimal,
        val maxDailyOrders: Int,
        val priceTolerance: BigDecimal,
        val delaySeconds: Int,
        val pollIntervalSeconds: Int,
        val useWebSocket: Boolean,
        val websocketReconnectInterval: Int,
        val websocketMaxRetries: Int,
        val supportSell: Boolean,
        val minOrderDepth: BigDecimal?,
        val maxSpread: BigDecimal?,
        val minPrice: BigDecimal?,
        val maxPrice: BigDecimal?,
        val maxPositionValue: BigDecimal?,
        val keywordFilterMode: String,
        val keywords: String?,
        val maxMarketEndDate: Long?,
        val pushFilteredOrders: Boolean
    )

    companion object {
        private const val GROUP_STATUS_ACTIVE = "ACTIVE"
    }
}
