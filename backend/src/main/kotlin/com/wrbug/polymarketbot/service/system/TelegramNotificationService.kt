package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.repository.LargeBetMonitorConfigRepository
import com.wrbug.polymarketbot.service.market.MarketBettingMarketText
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.DateUtils
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

internal enum class TelegramNotificationAudience {
    ALL,
    STANDARD,
    MONITOR_ONLY
}

internal fun filterTelegramConfigsForAudience(
    configs: List<NotificationConfigDto>,
    audience: TelegramNotificationAudience,
    excludedConfigIds: Set<Long> = emptySet()
): List<NotificationConfigDto> {
    val telegramConfigs = configs.mapNotNull { config ->
        if (config.id != null && config.id in excludedConfigIds) return@mapNotNull null
        val telegramConfig = config.config as? NotificationConfigData.Telegram ?: return@mapNotNull null
        config to telegramConfig
    }

    return when (audience) {
        TelegramNotificationAudience.ALL -> telegramConfigs.map { it.first }
        TelegramNotificationAudience.MONITOR_ONLY -> telegramConfigs
            .filter { (_, telegramConfig) ->
                telegramConfig.data.monitorModeEnabled == true && telegramConfig.data.marketBettingQueryEnabled != true
            }
            .map { it.first }
        TelegramNotificationAudience.STANDARD -> {
            val standardConfigs = telegramConfigs
                .filter { (_, telegramConfig) ->
                    telegramConfig.data.monitorModeEnabled != true && telegramConfig.data.marketBettingQueryEnabled != true
                }
                .map { it.first }

            if (standardConfigs.isNotEmpty()) {
                standardConfigs
            } else {
                telegramConfigs
                    .filter { (_, telegramConfig) -> telegramConfig.data.marketBettingQueryEnabled != true }
                    .map { it.first }
            }
        }
    }
}

internal data class CopyTradingTelegramRoute(
    val telegramConfigId: Long,
    val leaderGroups: List<String> = emptyList()
) {
    fun matches(leaderGroup: String?): Boolean {
        if (leaderGroups.isEmpty()) {
            return true
        }
        val normalizedLeaderGroup = leaderGroup?.trim()?.lowercase().orEmpty()
        return normalizedLeaderGroup.isNotEmpty() && normalizedLeaderGroup in leaderGroups.map { it.trim().lowercase() }
    }
}

internal fun hasCopyTradingRouteFilters(telegramConfig: TelegramConfigData): Boolean {
    return telegramConfig.copyTradingLeaderGroups.isNotEmpty()
}

internal fun telegramConfigMatchesCopyTradingRoute(
    telegramConfig: TelegramConfigData,
    leaderGroup: String?
): Boolean = telegramConfigMatchesCopyTradingRoute(telegramConfig, listOf(leaderGroup))

internal fun telegramConfigMatchesCopyTradingRoute(
    telegramConfig: TelegramConfigData,
    leaderGroups: Collection<String?>
): Boolean {
    val routeLeaderGroups = leaderGroups.ifEmpty { listOf(null) }
    return CopyTradingTelegramRoute(
        telegramConfigId = 0L,
        leaderGroups = telegramConfig.copyTradingLeaderGroups
    ).let { route ->
        routeLeaderGroups.any { leaderGroup -> route.matches(leaderGroup) }
    }
}

internal fun filterMarketBettingQueryTelegramConfigs(configs: List<NotificationConfigDto>): List<NotificationConfigDto> {
    return configs.filter { config ->
        val telegramConfig = config.config as? NotificationConfigData.Telegram ?: return@filter false
        telegramConfig.data.marketBettingQueryEnabled
    }
}

internal data class TelegramChatTarget(
    val chatId: String,
    val messageThreadId: Int? = null
)

internal fun parseTelegramChatTarget(value: String): TelegramChatTarget {
    val normalized = value.trim()
    val parts = normalized.split(":", limit = 2)
    val threadId = parts.getOrNull(1)?.trim()?.toIntOrNull()

    return TelegramChatTarget(
        chatId = parts.first().trim(),
        messageThreadId = threadId
    )
}

internal fun extractTelegramChatIdsFromUpdates(result: JsonNode): List<String> {
    if (!result.isArray) return emptyList()

    val targets = linkedMapOf<String, Int>()

    fun addChat(chat: JsonNode?, messageThreadId: Int?) {
        if (chat == null) return
        val chatId = chat.get("id")?.asText() ?: return
        val path = messageThreadId?.let { "$chatId:$it" } ?: chatId
        val chatType = chat.get("type")?.asText().orEmpty()
        val priority = if (chatType == "private") 1 else 0
        targets[path] = minOf(targets[path] ?: priority, priority)
    }

    result.forEach { update ->
        listOf("message", "edited_message", "channel_post", "edited_channel_post").forEach { field ->
            val message = update.get(field)
            addChat(message?.get("chat"), message?.get("message_thread_id")?.asInt())
        }
        addChat(update.get("my_chat_member")?.get("chat"), null)
    }

    return targets.entries
        .sortedWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .map { it.key }
}

internal fun buildSignalSourceDisplay(configName: String?, leaderName: String?): String? {
    val parts = listOf(configName, leaderName)
        .mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
        .distinct()

    return parts.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

internal fun formatCurrentPositionValueDisplay(currentPositionValue: String?): String? {
    val normalizedAmount = currentPositionValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    return try {
        val amountDecimal = normalizedAmount.toSafeBigDecimal()
        val formatted = if (amountDecimal.scale() > 4) {
            amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
        } else {
            amountDecimal.stripTrailingZeros()
        }
        "${formatted.toPlainString()}u"
    } catch (e: Exception) {
        if (normalizedAmount.lowercase().endsWith("u")) normalizedAmount else "${normalizedAmount}u"
    }
}

internal fun buildSignalSourceDetails(
    configName: String?,
    leaderName: String?,
    signalSourceLabel: String,
    currentPositionValueLabel: String? = null,
    currentPositionValue: String? = null
): String? {
    val signalSource = buildSignalSourceDisplay(configName, leaderName) ?: return null
    val escapedSignalSource = signalSource.replace("<", "&lt;").replace(">", "&gt;")
    val detailLines = mutableListOf("• $signalSourceLabel: $escapedSignalSource")

    val formattedCurrentPositionValue = formatCurrentPositionValueDisplay(currentPositionValue)
    if (!currentPositionValueLabel.isNullOrBlank() && !formattedCurrentPositionValue.isNullOrBlank()) {
        detailLines += "• $currentPositionValueLabel: $formattedCurrentPositionValue"
    }

    return detailLines.joinToString("\n")
}

private fun escapeHtml(value: String): String {
    return value.replace("<", "&lt;").replace(">", "&gt;")
}

internal fun translateTelegramDisplayText(value: String?): String {
    return MarketBettingMarketText.displayEventTitle(value.orEmpty())
}

internal fun shouldSuppressOrderNotificationAmount(amount: String?, minAmount: BigDecimal): Boolean {
    if (minAmount <= BigDecimal.ZERO) return false
    val amountDecimal = amount?.trim()?.takeIf { it.isNotEmpty() }?.toSafeBigDecimal() ?: return false
    return amountDecimal < minAmount
}

@Service
class TelegramNotificationService(
    private val notificationConfigService: NotificationConfigService,
    private val notificationTemplateService: NotificationTemplateService,
    private val objectMapper: ObjectMapper,
    private val messageSource: MessageSource,
    private val largeBetMonitorConfigRepository: LargeBetMonitorConfigRepository,
    private val systemConfigService: SystemConfigService
) {

    private val logger = LoggerFactory.getLogger(TelegramNotificationService::class.java)

    private val okHttpClient = createClient()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val apiBaseUrl = "https://api.telegram.org/bot"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sentOrderIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    suspend fun sendOrderSuccessNotification(
        orderId: String?,
        marketTitle: String,
        marketId: String? = null,
        marketSlug: String? = null,
        side: String,
        price: String? = null,
        avgFilledPrice: String? = null,
        filled: String? = null,
        size: String? = null,
        outcome: String? = null,
        accountName: String? = null,
        walletAddress: String? = null,
        clobApi: PolymarketClobApi? = null,
        apiKey: String? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        walletAddressForApi: String? = null,
        locale: java.util.Locale? = null,
        leaderName: String? = null,
        configName: String? = null,
        orderTime: Long? = null,
        availableBalance: String? = null,
        currentPositionValue: String? = null,
        copyTradingId: Long? = null,
        messageCategory: String? = null
    ) {
        if (orderId != null) {
            val lastSentTime = sentOrderIds[orderId]
            if (lastSentTime != null) {
                if (System.currentTimeMillis() - lastSentTime < 5 * 60 * 1000) {
                    logger.info("订单通知已发送过（5分钟内），跳过: orderId=$orderId")
                    return
                }
            }
            sentOrderIds[orderId] = System.currentTimeMillis()
            if (sentOrderIds.size > 1000) {
                val expiryTime = System.currentTimeMillis() - 5 * 60 * 1000
                sentOrderIds.entries.removeIf { it.value < expiryTime }
            }
        }
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        var actualPrice: String? = avgFilledPrice?.takeIf { it.isNotBlank() } ?: price
        var actualSize: String? = size
        var actualSide: String = side
        var actualOutcome: String? = outcome
        val sizeForAmount: String? = if (avgFilledPrice != null && avgFilledPrice.isNotBlank() && filled != null && filled.isNotBlank()) {
            filled
        } else {
            null
        }
        if ((actualPrice == null || actualSize == null || actualOutcome == null) && orderId != null && clobApi != null && apiKey != null && apiSecret != null && apiPassphrase != null && walletAddressForApi != null) {
            try {
                val orderResponse = clobApi.getOrder(orderId)
                if (orderResponse.isSuccessful) {
                    val order = orderResponse.body()
                    if (order != null) {
                        if (actualPrice == null) {
                            actualPrice = order.price
                        }
                        if (actualSize == null) {
                            actualSize = order.originalSize
                        }
                        if (actualOutcome == null) {
                            actualOutcome = order.outcome
                        }
                    } else {
                        logger.debug("查询订单详情失败: 响应体为空, orderId=$orderId")
                    }
                } else {
                    val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "无错误详情"
                    logger.debug("查询订单详情失败: orderId=$orderId, code=${orderResponse.code()}, errorBody=$errorBody")
                }
            } catch (e: Exception) {
                logger.warn("查询订单详情失败: orderId=$orderId, ${e.message}", e)
            }
        }
        val finalPrice = actualPrice ?: "0"
        val finalSize = if (avgFilledPrice != null && avgFilledPrice.isNotBlank() && filled != null && filled.isNotBlank()) {
            filled
        } else {
            actualSize ?: "0"
        }
        val sizeForCalc = sizeForAmount?.takeIf { it.isNotBlank() } ?: finalSize
        val amount = try {
            val priceDecimal = finalPrice.toSafeBigDecimal()
            val sizeDecimal = sizeForCalc.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }

        val orderNotificationMinAmount = systemConfigService.getOrderNotificationMinAmount()
        if (shouldSuppressOrderNotificationAmount(amount, orderNotificationMinAmount)) {
            logger.info(
                "订单金额低于 Telegram 推送阈值，跳过: orderId={}, amount={}, threshold={}",
                orderId,
                amount,
                orderNotificationMinAmount
            )
            return
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildOrderSuccessVariables(
            orderId = orderId,
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = actualSide,
            outcome = actualOutcome,
            price = finalPrice,
            size = finalSize,
            amount = amount,
            accountName = accountName,
            walletAddress = walletAddress,
            locale = currentLocale,
            leaderName = leaderName,
            configName = configName,
            orderTime = orderTime,
            availableBalance = availableBalance,
            currentPositionValue = currentPositionValue,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed
        )
        val message = notificationTemplateService.renderTemplate("ORDER_SUCCESS", vars)
        sendCopyTradingMessage(message, messageCategory, TelegramNotificationAudience.STANDARD)
    }

    suspend fun sendTestMessage(message: String, configId: Long?): Boolean {
        if (configId == null) {
            return sendTestMessage(message)
        }

        return try {
            val config = notificationConfigService.getConfigById(configId)
            if (config == null) {
                logger.warn("Specified Telegram test configuration does not exist: configId={}", configId)
                return false
            }
            if (!config.enabled) {
                logger.warn("Specified Telegram test configuration is disabled: configId={}", configId)
                return false
            }
            if (!config.type.equals("telegram", ignoreCase = true)) {
                logger.warn("Specified test configuration is not a Telegram config: configId={}, type={}", configId, config.type)
                return false
            }

            when (val configData = config.config) {
                is NotificationConfigData.Telegram -> sendTelegramMessage(configData.data, message)
                else -> false
            }
        } catch (e: Exception) {
            logger.error("Failed to send test message with specified configuration: configId={}, message={}", configId, e.message, e)
            false
        }
    }

    suspend fun sendOrderFailureNotification(
        marketTitle: String,
        marketId: String? = null,
        marketSlug: String? = null,
        side: String,
        outcome: String? = null,
        price: String,
        size: String,
        errorMessage: String,
        accountName: String? = null,
        walletAddress: String? = null,
        leaderName: String? = null,
        configName: String? = null,
        locale: java.util.Locale? = null,
        currentPositionValue: String? = null,
        copyTradingId: Long? = null,
        messageCategory: String? = null
    ) {
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        val amount = try {
            val priceDecimal = price.toSafeBigDecimal()
            val sizeDecimal = size.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildOrderFailureVariables(
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = side,
            outcome = outcome,
            price = price,
            size = size,
            amount = amount,
            errorMessage = errorMessage,
            accountName = accountName,
            walletAddress = walletAddress,
            leaderName = leaderName,
            configName = configName,
            locale = currentLocale,
            currentPositionValue = currentPositionValue,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed
        )
        val message = notificationTemplateService.renderTemplate("ORDER_FAILED", vars)
        sendCopyTradingMessage(message, messageCategory, TelegramNotificationAudience.STANDARD)
    }

    private fun buildOrderFailureVariables(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        errorMessage: String,
        accountName: String?,
        walletAddress: String?,
        leaderName: String?,
        configName: String?,
        locale: java.util.Locale,
        currentPositionValue: String?,
        unknownAccount: String,
        calculateFailed: String
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val signalSourceLabel = messageSource
            .getMessage("notification.order.signal_source", null, "信号源", locale)
            .orEmpty()
            .ifEmpty { "信号源" }
        val currentPositionValueLabel = messageSource
            .getMessage("notification.order.current_position_value", null, "当前持仓金额", locale)
            .orEmpty()
            .ifEmpty { "当前持仓金额" }
        val accountInfo = buildAccountInfoWithSignalSource(
            accountName = accountName,
            walletAddress = walletAddress,
            unknownAccount = unknownAccount,
            leaderName = leaderName,
            configName = configName,
            signalSourceLabel = signalSourceLabel,
            currentPositionValueLabel = currentPositionValueLabel,
            currentPositionValue = currentPositionValue
        )
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = amount?.let { am ->
            try {
                val amountDecimal = am.toSafeBigDecimal()
                (if (amountDecimal.scale() > 4) amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else amountDecimal.stripTrailingZeros()).toPlainString()
            } catch (e: Exception) { am }
        } ?: calculateFailed
        val shortError = if (errorMessage.length > 500) errorMessage.substring(0, 500) + "..." else errorMessage
        return mapOf(
            "market_title" to escapeHtml(translateTelegramDisplayText(marketTitle)),
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to escapeHtml(translateTelegramDisplayText(outcome)),
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "leader_name" to (leaderName?.trim()?.takeIf { it.isNotEmpty() }?.replace("<", "&lt;")?.replace(">", "&gt;") ?: "-"),
            "config_name" to (configName?.trim()?.takeIf { it.isNotEmpty() }?.replace("<", "&lt;")?.replace(">", "&gt;") ?: "-"),
            "error_message" to shortError.replace("<", "&lt;").replace(">", "&gt;"),
            "time" to DateUtils.formatDateTime()
        )
    }

    suspend fun sendOrderFilteredNotification(
        marketTitle: String,
        marketId: String? = null,
        marketSlug: String? = null,
        side: String,
        outcome: String? = null,
        price: String,
        size: String,
        filterReason: String,
        filterType: String,
        accountName: String? = null,
        walletAddress: String? = null,
        locale: java.util.Locale? = null,
        copyTradingId: Long? = null,
        messageCategory: String? = null
    ) {
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        val amount = try {
            val priceDecimal = price.toSafeBigDecimal()
            val sizeDecimal = size.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val vars = buildOrderFilteredVariables(
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = side,
            outcome = outcome,
            price = price,
            size = size,
            amount = amount,
            filterReason = filterReason,
            filterType = filterType,
            accountName = accountName,
            walletAddress = walletAddress,
            locale = currentLocale,
            unknownAccount = unknownAccount,
            calculateFailed = calculateFailed
        )
        val message = notificationTemplateService.renderTemplate("ORDER_FILTERED", vars)
        sendCopyTradingMessage(message, messageCategory, TelegramNotificationAudience.STANDARD)
    }

    private fun buildOrderFilteredVariables(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        filterReason: String,
        filterType: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        unknownAccount: String,
        calculateFailed: String
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val filterTypeDisplay = when (filterType.uppercase()) {
            "ORDER_DEPTH" -> messageSource.getMessage("notification.filter.type.order_depth", null, "订单深度不足", locale).orEmpty().ifEmpty { "订单深度不足" }
            "SPREAD" -> messageSource.getMessage("notification.filter.type.spread", null, "价差过大", locale).orEmpty().ifEmpty { "价差过大" }
            "ORDERBOOK_DEPTH" -> messageSource.getMessage("notification.filter.type.orderbook_depth", null, "订单簿深度不足", locale).orEmpty().ifEmpty { "订单簿深度不足" }
            "PRICE_VALIDITY" -> messageSource.getMessage("notification.filter.type.price_validity", null, "价格不合理", locale).orEmpty().ifEmpty { "价格不合理" }
            "MARKET_STATUS" -> messageSource.getMessage("notification.filter.type.market_status", null, "市场状态不可交易", locale).orEmpty().ifEmpty { "市场状态不可交易" }
            else -> filterType
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = amount?.let { am ->
            try {
                (am.toSafeBigDecimal().let { if (it.scale() > 4) it.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else it.stripTrailingZeros() }.toPlainString())
            } catch (e: Exception) { am }
        } ?: calculateFailed
        return mapOf(
            "market_title" to escapeHtml(translateTelegramDisplayText(marketTitle)),
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to escapeHtml(translateTelegramDisplayText(outcome)),
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "filter_type" to filterTypeDisplay,
            "filter_reason" to filterReason.replace("<", "&lt;").replace(">", "&gt;"),
            "time" to DateUtils.formatDateTime()
        )
    }

    suspend fun sendCryptoTailOrderSuccessNotification(
        orderId: String?,
        marketTitle: String,
        marketId: String? = null,
        marketSlug: String? = null,
        side: String,
        outcome: String? = null,
        price: String,
        size: String,
        avgFilledPrice: String? = null,
        filled: String? = null,
        strategyName: String? = null,
        accountName: String? = null,
        walletAddress: String? = null,
        locale: java.util.Locale? = null,
        orderTime: Long? = null
    ) {
        if (orderId != null) {
            val lastSentTime = sentOrderIds[orderId]
            if (lastSentTime != null && System.currentTimeMillis() - lastSentTime < 5 * 60 * 1000) {
                logger.info("加密价差策略订单通知已发送过（5分钟内），跳过: orderId=$orderId")
                return
            }
            sentOrderIds[orderId] = System.currentTimeMillis()
            if (sentOrderIds.size > 1000) {
                val expiryTime = System.currentTimeMillis() - 5 * 60 * 1000
                sentOrderIds.entries.removeIf { it.value < expiryTime }
            }
        }
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        val displayPrice = avgFilledPrice?.takeIf { it.isNotBlank() } ?: price
        val hasAvgFilled = avgFilledPrice != null && avgFilledPrice.isNotBlank() && filled != null && filled.isNotBlank()
        val sizeForAmount = if (hasAvgFilled) filled else size
        val quantityDisplay = if (hasAvgFilled) filled else size
        val amount = try {
            val priceDecimal = displayPrice.toSafeBigDecimal()
            val sizeDecimal = sizeForAmount.toSafeBigDecimal()
            priceDecimal.multiply(sizeDecimal).toString()
        } catch (e: Exception) {
            logger.warn("计算订单金额失败: ${e.message}", e)
            null
        }
        val unknown = messageSource.getMessage("common.unknown", null, "未知", currentLocale).orEmpty().ifEmpty { "未知" }
        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale).orEmpty().ifEmpty { "未知账户" }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale).orEmpty().ifEmpty { "计算失败" }
        val message = buildCryptoTailOrderSuccessMessage(
            orderId = orderId,
            marketTitle = marketTitle,
            marketId = marketId,
            marketSlug = marketSlug,
            side = side,
            outcome = outcome,
            price = displayPrice,
            size = quantityDisplay.orEmpty(),
            amount = amount,
            strategyName = strategyName,
            accountName = accountName,
            walletAddress = walletAddress,
            locale = currentLocale,
            orderTime = orderTime
        )
        sendStandardMessage(message)
    }

    private fun buildCryptoTailOrderSuccessVariables(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        strategyName: String?,
        accountName: String?,
        walletAddress: String?,
        orderTime: Long?,
        unknown: String,
        unknownAccount: String,
        calculateFailed: String,
        locale: java.util.Locale
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = if (orderTime != null) DateUtils.formatDateTime(orderTime) else DateUtils.formatDateTime()
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = amount?.let { am ->
            try {
                (am.toSafeBigDecimal().let { if (it.scale() > 4) it.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else it.stripTrailingZeros() }.toPlainString())
            } catch (e: Exception) { am }
        } ?: calculateFailed
        return mapOf(
            "order_id" to (orderId ?: unknown),
            "market_title" to escapeHtml(translateTelegramDisplayText(marketTitle)),
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to escapeHtml(translateTelegramDisplayText(outcome)),
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "strategy_name" to (strategyName?.takeIf { it.isNotBlank() } ?: unknown),
            "time" to time
        )
    }

    private fun buildOrderFilteredMessage(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        filterReason: String,
        filterType: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale
    ): String {
        val orderFiltered = messageSource.getMessage("notification.order.filtered", null, "订单被过滤", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val filterReasonLabel = messageSource.getMessage("notification.order.filter_reason", null, "过滤原因", locale)
        val filterTypeLabel = messageSource.getMessage("notification.order.filter_type", null, "过滤类型", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        val filterTypeDisplay = when (filterType.uppercase()) {
            "ORDER_DEPTH" -> messageSource.getMessage("notification.filter.type.order_depth", null, "订单深度不足", locale)
            "SPREAD" -> messageSource.getMessage("notification.filter.type.spread", null, "价差过大", locale)
            "ORDERBOOK_DEPTH" -> messageSource.getMessage("notification.filter.type.orderbook_depth", null, "订单簿深度不足", locale)
            "PRICE_VALIDITY" -> messageSource.getMessage("notification.filter.type.price_validity", null, "价格不合理", locale)
            "MARKET_STATUS" -> messageSource.getMessage("notification.filter.type.market_status", null, "市场状态不可交易", locale)
            else -> filterType
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)

        val time = DateUtils.formatDateTime()
        val escapedMarketTitle = escapeHtml(translateTelegramDisplayText(marketTitle))
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedFilterReason = filterReason.replace("<", "&lt;").replace(">", "&gt;")
        val amountDisplay = if (amount != null) {
            try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) {
                    amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    amountDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                amount
            }
        } else {
            calculateFailed
        }
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> {
                "https://polymarket.com/event/$marketSlug"
            }
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> {
                "https://polymarket.com/condition/$marketId"
            }
            else -> null
        }
        
        val marketDisplay = if (marketLink != null) {
            "<a href=\"$marketLink\">$escapedMarketTitle</a>"
        } else {
            escapedMarketTitle
        }
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = escapeHtml(translateTelegramDisplayText(outcome))
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else {
            ""
        }
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)

        return """🚫 <b>$orderFiltered</b>

📊 <b>$orderInfo：</b>
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> 份
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo

⚠️ <b>$filterTypeLabel：</b> <code>$filterTypeDisplay</code>

📝 <b>$filterReasonLabel：</b>
<code>$escapedFilterReason</code>

⏰ $timeLabel: <code>$time</code>"""
    }

    suspend fun sendTestMessage(message: String = "这是一条测试消息"): Boolean {
        return try {
            val configs = filterTelegramConfigsForAudience(
                notificationConfigService.getEnabledConfigsByType("telegram"),
                TelegramNotificationAudience.ALL
            )
            if (configs.isEmpty()) {
                logger.warn("没有启用的 Telegram 配置")
                return false
            }

            return coroutineScope {
                val results = configs.map { config ->
                    async(Dispatchers.IO) {
                        when (val configData = config.config) {
                            is NotificationConfigData.Telegram -> {
                                sendTelegramMessage(configData.data, message)
                            }

                            else -> false
                        }
                    }
                }.awaitAll()

                results.any { it }
            }
        } catch (e: Exception) {
            logger.error("发送测试消息失败: ${e.message}", e)
            false
        }
    }

    suspend fun isMonitorModeEnabled(): Boolean {
        return try {
            val configs = notificationConfigService.getEnabledConfigsByType("telegram")
            filterTelegramConfigsForAudience(configs, TelegramNotificationAudience.MONITOR_ONLY).isNotEmpty()
        } catch (e: Exception) {
            logger.error("Failed to check monitor mode status: ${e.message}", e)
            false
        }
    }

    suspend fun sendMessage(message: String) {
        sendMessage(message, TelegramNotificationAudience.ALL)
    }

    suspend fun sendStandardMessage(message: String) {
        sendMessage(message, TelegramNotificationAudience.STANDARD)
    }

    suspend fun sendMonitorMessage(message: String) {
        sendMessage(message, TelegramNotificationAudience.MONITOR_ONLY)
    }

    suspend fun sendMonitorMessageToConfigs(message: String, configs: List<NotificationConfigDto>) {
        val excludedConfigIds = largeBetAssignedTelegramConfigIds()
        sendMessageToConfigs(
            message,
            configs.filter { config -> config.id == null || config.id !in excludedConfigIds }
        )
    }

    suspend fun sendLargeBetMonitorMessage(message: String, configId: Long?): Boolean {
        if (configId != null) {
            return sendTestMessage(message, configId)
        }
        sendMonitorMessage(message)
        return true
    }

    private suspend fun sendCopyTradingMessage(
        message: String,
        category: String?,
        fallbackAudience: TelegramNotificationAudience
    ) {
        sendCopyTradingMessage(message, listOf(category), fallbackAudience)
    }

    private suspend fun sendCopyTradingMessage(
        message: String,
        categories: Collection<String?>,
        fallbackAudience: TelegramNotificationAudience
    ) {
        val routedConfigs = findCopyTradingRouteConfigs(categories, fallbackAudience)
        if (routedConfigs != null) {
            sendMessageToConfigs(message, routedConfigs)
            return
        }

        sendMessage(message, fallbackAudience)
    }

    private suspend fun findCopyTradingRouteConfigs(
        leaderGroups: Collection<String?>,
        fallbackAudience: TelegramNotificationAudience
    ): List<NotificationConfigDto>? = withContext(Dispatchers.IO) {
        if (fallbackAudience != TelegramNotificationAudience.MONITOR_ONLY) {
            return@withContext null
        }

        val audienceConfigs = filterTelegramConfigsForAudience(
            notificationConfigService.getEnabledConfigsByType("telegram"),
            fallbackAudience,
            largeBetAssignedTelegramConfigIds()
        )
        val hasRobotFilters = audienceConfigs.any { config ->
            val telegramConfig = config.config as? NotificationConfigData.Telegram ?: return@any false
            hasCopyTradingRouteFilters(telegramConfig.data)
        }
        if (hasRobotFilters) {
            return@withContext audienceConfigs.filter { config ->
                val telegramConfig = config.config as? NotificationConfigData.Telegram ?: return@filter false
                telegramConfigMatchesCopyTradingRoute(telegramConfig.data, leaderGroups)
            }
        }

        null
    }

    private fun sendMessageToConfigs(message: String, configs: List<NotificationConfigDto>) {
        if (configs.isEmpty()) {
            logger.debug("没有匹配的 Telegram 路由配置，跳过发送消息")
            return
        }

        configs.forEach { config ->
            scope.launch {
                try {
                    when (val configData = config.config) {
                        is NotificationConfigData.Telegram -> sendTelegramMessage(configData.data, message)
                        else -> logger.warn("不支持的配置类型: ${config.type}")
                    }
                } catch (e: Exception) {
                    logger.error("发送 Telegram 路由消息失败 (configId=${config.id}): ${e.message}", e)
                }
            }
        }
    }

    private suspend fun sendMessage(message: String, audience: TelegramNotificationAudience) {
        try {
            val excludedConfigIds = largeBetAssignedTelegramConfigIds()
            val configs = filterTelegramConfigsForAudience(
                notificationConfigService.getEnabledConfigsByType("telegram"),
                audience,
                excludedConfigIds
            )
            if (configs.isEmpty()) {
                logger.debug("没有启用的 Telegram 配置，跳过发送消息")
                return
            }
            configs.forEach { config ->
                scope.launch {
                    try {
                        when (val configData = config.config) {
                            is NotificationConfigData.Telegram -> {
                                sendTelegramMessage(configData.data, message)
                            }

                            else -> {
                                logger.warn("不支持的配置类型: ${config.type}")
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("发送 Telegram 消息失败 (configId=${config.id}): ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("发送通知消息失败: ${e.message}", e)
        }
    }

    private suspend fun largeBetAssignedTelegramConfigIds(): Set<Long> = withContext(Dispatchers.IO) {
        largeBetMonitorConfigRepository.findAll()
            .mapNotNull { it.telegramConfigId }
            .toSet()
    }

    suspend fun sendMonitorPushNotification(
        marketTitle: String,
        marketLink: String,
        leaderName: String,
        side: String,
        outcome: String,
        price: String,
        size: String,
        currentPositionSummary: String,
        locale: java.util.Locale? = null,
        copyTradingId: Long? = null,
        leaderGroup: String? = null
    ) {
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", currentLocale)
            .orEmpty()
            .ifEmpty { "计算失败" }
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", currentLocale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", currentLocale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val amount = try {
            price.toSafeBigDecimal().multiply(size.toSafeBigDecimal()).toString()
        } catch (e: Exception) {
            null
        }
        val orderNotificationMinAmount = systemConfigService.getOrderNotificationMinAmount()
        if (shouldSuppressOrderNotificationAmount(amount, orderNotificationMinAmount)) {
            logger.info(
                "监控推送金额低于 Telegram 推送阈值，跳过: marketTitle={}, leaderName={}, amount={}, threshold={}",
                marketTitle,
                leaderName,
                amount,
                orderNotificationMinAmount
            )
            return
        }
        val amountDisplay = try {
            val amountDecimal = amount?.toSafeBigDecimal() ?: throw IllegalArgumentException("amount is null")
            if (amountDecimal.scale() > 4) {
                amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
            } else {
                amountDecimal.stripTrailingZeros().toPlainString()
            }
        } catch (e: Exception) {
            calculateFailed
        }
        val message = notificationTemplateService.renderTemplate(
            "MONITOR_PUSH",
            mapOf(
                "market_title" to escapeHtml(translateTelegramDisplayText(marketTitle)),
                "market_link" to marketLink,
                "leader_name" to escapeHtml(leaderName),
                "side" to escapeHtml(sideDisplay),
                "outcome" to escapeHtml(translateTelegramDisplayText(outcome)),
                "price" to formatPrice(price),
                "quantity" to formatQuantity(size),
                "amount" to amountDisplay,
                "current_position_summary" to escapeHtml(translateTelegramDisplayText(currentPositionSummary)),
                "time" to DateUtils.formatDateTime()
            )
        )
        sendCopyTradingMessage(message, leaderGroup, TelegramNotificationAudience.MONITOR_ONLY)
    }

    suspend fun sendMonitorSameSideNotification(
        marketTitle: String,
        marketLink: String,
        outcome: String,
        sameSidePositionReport: String,
        sameSideCount: Int,
        leaderGroups: Collection<String?> = emptyList()
    ) {
        val message = notificationTemplateService.renderTemplate(
            "MONITOR_SAME_SIDE",
            mapOf(
                "market_title" to escapeHtml(translateTelegramDisplayText(marketTitle)),
                "market_link" to marketLink,
                "outcome" to escapeHtml(translateTelegramDisplayText(outcome)),
                "same_side_position_report" to translateTelegramDisplayText(sameSidePositionReport),
                "same_side_count" to sameSideCount.toString(),
                "time" to DateUtils.formatDateTime()
            )
        )
        sendCopyTradingMessage(message, leaderGroups, TelegramNotificationAudience.MONITOR_ONLY)
    }

    suspend fun sendMonitorOppositeNotification(
        marketTitle: String,
        marketLink: String,
        outcomeA: String,
        sideAPositionReport: String,
        outcomeB: String,
        sideBPositionReport: String,
        hedgePositionReport: String,
        leaderGroups: Collection<String?> = emptyList()
    ) {
        val message = notificationTemplateService.renderTemplate(
            "MONITOR_OPPOSITE_SIDE",
            mapOf(
                "market_title" to escapeHtml(translateTelegramDisplayText(marketTitle)),
                "market_link" to marketLink,
                "outcome_a" to escapeHtml(translateTelegramDisplayText(outcomeA)),
                "side_a_position_report" to translateTelegramDisplayText(sideAPositionReport),
                "outcome_b" to escapeHtml(translateTelegramDisplayText(outcomeB)),
                "side_b_position_report" to translateTelegramDisplayText(sideBPositionReport),
                "hedge_position_report" to translateTelegramDisplayText(hedgePositionReport),
                "time" to DateUtils.formatDateTime()
            )
        )
        sendCopyTradingMessage(message, leaderGroups, TelegramNotificationAudience.MONITOR_ONLY)
    }

    private suspend fun sendTelegramMessage(config: TelegramConfigData, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val results = config.chatIds.map { chatId ->
                    async {
                        sendToSingleChat(config.botToken, chatId, message)
                    }
                }.awaitAll()

                results.any { it }
            } catch (e: Exception) {
                logger.error("发送 Telegram 消息失败: ${e.message}", e)
                false
            }
        }
    }

    suspend fun getChatIds(botToken: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$apiBaseUrl$botToken/getUpdates"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val responseBody = okHttpClient.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    return@withContext Result.failure(
                        Exception("获取 Chat IDs 失败: code=${response.code}, body=$errorBody")
                    )
                }

                    response.body?.string() ?: ""
                }
                val jsonNode = objectMapper.readTree(responseBody)

                if (jsonNode.get("ok")?.asBoolean()?.not() ?: false) {
                    val description = jsonNode.get("description")?.asText() ?: "未知错误"
                    return@withContext Result.failure(Exception("Telegram API 错误: $description"))
                }

                val result = jsonNode.get("result")
                if (result == null || !result.isArray) {
                    return@withContext Result.failure(Exception("未找到消息记录，请先向机器人发送一条消息（如 /start）"))
                }
                val chatIds = extractTelegramChatIdsFromUpdates(result)

                if (chatIds.isEmpty()) {
                    return@withContext Result.failure(
                        Exception("未找到 Chat ID，请先向机器人发送一条消息（如 /start），然后重试")
                    )
                }

                Result.success(chatIds)
            } catch (e: Exception) {
                logger.error("获取 Chat IDs 异常: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun getUpdates(botToken: String, offset: Long?): Result<List<TelegramIncomingUpdate>> {
        return withContext(Dispatchers.IO) {
            try {
                val url = buildString {
                    append(apiBaseUrl).append(botToken).append("/getUpdates?timeout=0")
                    if (offset != null) append("&offset=").append(offset)
                }
                val request = Request.Builder().url(url).get().build()
                val responseBody = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        return@withContext Result.failure(Exception("Telegram getUpdates 失败: code=${response.code}, body=$errorBody"))
                    }
                    response.body?.string().orEmpty()
                }
                val root = objectMapper.readTree(responseBody)
                if (root.get("ok")?.asBoolean() == false) {
                    return@withContext Result.failure(Exception(root.get("description")?.asText() ?: "Telegram getUpdates 失败"))
                }
                val updates = root.get("result")?.mapNotNull { node ->
                    val updateId = node.get("update_id")?.asLong() ?: return@mapNotNull null
                    val callbackQuery = node.get("callback_query")
                    if (callbackQuery != null && !callbackQuery.isNull) {
                        val message = callbackQuery.get("message") ?: return@mapNotNull null
                        val chatId = message.get("chat")?.get("id")?.asText() ?: return@mapNotNull null
                        val messageThreadId = message.get("message_thread_id")?.asInt()
                        val data = callbackQuery.get("data")?.asText() ?: return@mapNotNull null
                        return@mapNotNull TelegramIncomingUpdate(
                            updateId = updateId,
                            chatId = chatId,
                            text = data,
                            messageThreadId = messageThreadId,
                            callbackQueryId = callbackQuery.get("id")?.asText(),
                            callbackData = data
                        )
                    }
                    val message = node.get("message") ?: node.get("edited_message") ?: return@mapNotNull null
                    val chatId = message.get("chat")?.get("id")?.asText() ?: return@mapNotNull null
                    val messageThreadId = message.get("message_thread_id")?.asInt()
                    val text = message.get("text")?.asText() ?: return@mapNotNull null
                    TelegramIncomingUpdate(updateId, chatId, text, messageThreadId)
                }.orEmpty()
                Result.success(updates)
            } catch (e: Exception) {
                logger.error("Telegram getUpdates 异常: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    suspend fun sendMessage(
        botToken: String,
        chatId: String,
        message: String,
        disableWebPagePreview: Boolean = false
    ): Boolean {
        return sendToSingleChat(botToken, chatId, message, disableWebPagePreview)
    }

    suspend fun sendMonitorPhaseControlMessage(
        botToken: String,
        chatId: String,
        liveOnlyModeEnabled: Boolean
    ): Boolean {
        return sendToSingleChat(
            botToken = botToken,
            chatId = chatId,
            message = buildMonitorPhaseControlMessage(liveOnlyModeEnabled),
            replyMarkup = buildMonitorPhaseControlKeyboard(liveOnlyModeEnabled)
        )
    }

    suspend fun answerCallbackQuery(
        botToken: String,
        callbackQueryId: String,
        message: String
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val payload = mapOf(
                    "callback_query_id" to callbackQueryId,
                    "text" to message,
                    "show_alert" to false
                )
                val request = Request.Builder()
                    .url("$apiBaseUrl$botToken/answerCallbackQuery")
                    .post(objectMapper.writeValueAsString(payload).toRequestBody("application/json".toMediaType()))
                    .build()
                okHttpClient.newCall(request).execute().use { it.isSuccessful }
            } catch (e: Exception) {
                logger.warn("Telegram callback answer failed: {}", e.message)
                false
            }
        }
    }

    private suspend fun sendToSingleChat(
        botToken: String,
        chatId: String,
        message: String,
        disableWebPagePreview: Boolean = false,
        replyMarkup: Map<String, Any>? = null
    ): Boolean {
        return try {
            val url = "$apiBaseUrl$botToken/sendMessage"
            val target = parseTelegramChatTarget(chatId)

            val payload = mutableMapOf<String, Any>(
                "chat_id" to target.chatId,
                "text" to message,
                "parse_mode" to "HTML",
                "disable_web_page_preview" to disableWebPagePreview
            )
            target.messageThreadId?.let { payload["message_thread_id"] = it }
            replyMarkup?.let { payload["reply_markup"] = it }

            val requestBody = objectMapper.writeValueAsString(payload)

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            okHttpClient.newCall(request).execute().use { response ->
            val isSuccess = response.isSuccessful

            if (!isSuccess) {
                val errorBody = response.body?.string()
                logger.error("Telegram API 调用失败: code=${response.code}, body=$errorBody")
            }

                isSuccess
            }
        } catch (e: Exception) {
            logger.error("发送 Telegram 消息异常: ${e.message}", e)
            false
        }
    }

    private fun formatPrice(price: String): String {
        return try {
            val priceDecimal = price.toSafeBigDecimal()
            val formatted = if (priceDecimal.scale() > 4) {
                priceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
            } else {
                priceDecimal.stripTrailingZeros()
            }
            formatted.toPlainString()
        } catch (e: Exception) {
            price
        }
    }

    private fun formatQuantity(quantity: String): String {
        return try {
            val quantityDecimal = quantity.toSafeBigDecimal()
            val formatted = if (quantityDecimal.scale() > 2) {
                quantityDecimal.setScale(2, java.math.RoundingMode.DOWN).stripTrailingZeros()
            } else {
                quantityDecimal.stripTrailingZeros()
            }
            formatted.toPlainString()
        } catch (e: Exception) {
            quantity
        }
    }

    private fun buildAccountInfo(
        accountName: String?,
        walletAddress: String?,
        unknownAccount: String
    ): String {
        return when {
            !accountName.isNullOrBlank() && !walletAddress.isNullOrBlank() -> {
                "${accountName}(${maskAddress(walletAddress)})"
            }
            !accountName.isNullOrBlank() -> {
                accountName
            }
            !walletAddress.isNullOrBlank() -> {
                maskAddress(walletAddress)
            }
            else -> {
                unknownAccount
            }
        }
    }

    private fun buildAccountInfoWithSignalSource(
        accountName: String?,
        walletAddress: String?,
        unknownAccount: String,
        leaderName: String?,
        configName: String?,
        signalSourceLabel: String,
        currentPositionValueLabel: String? = null,
        currentPositionValue: String? = null
    ): String {
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val signalSourceDetails = buildSignalSourceDetails(
            configName = configName,
            leaderName = leaderName,
            signalSourceLabel = signalSourceLabel,
            currentPositionValueLabel = currentPositionValueLabel,
            currentPositionValue = currentPositionValue
        ) ?: return accountInfo
        return "$accountInfo\n$signalSourceDetails"
    }

    private fun buildOrderSuccessVariables(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        leaderName: String?,
        configName: String?,
        orderTime: Long?,
        availableBalance: String?,
        currentPositionValue: String?,
        unknownAccount: String,
        calculateFailed: String
    ): Map<String, String> {
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale).orEmpty().ifEmpty { "买入" }
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale).orEmpty().ifEmpty { "卖出" }
            else -> side
        }
        val unknown = messageSource.getMessage("common.unknown", null, "未知", locale).orEmpty().ifEmpty { "未知" }
        val signalSourceLabel = messageSource.getMessage("notification.order.signal_source", null, "信号源", locale)
            .orEmpty()
            .ifEmpty { "信号源" }
        val currentPositionValueLabel = messageSource.getMessage("notification.order.current_position_value", null, "当前持仓金额", locale)
            .orEmpty()
            .ifEmpty { "当前持仓金额" }
        val accountInfo = buildAccountInfoWithSignalSource(
            accountName = accountName,
            walletAddress = walletAddress,
            unknownAccount = unknownAccount,
            leaderName = leaderName,
            configName = configName,
            signalSourceLabel = signalSourceLabel,
            currentPositionValueLabel = currentPositionValueLabel,
            currentPositionValue = currentPositionValue
        )
        val time = if (orderTime != null) DateUtils.formatDateTime(orderTime) else DateUtils.formatDateTime()
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> ""
        }
        val amountDisplay = when {
            amount != null -> try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else amountDecimal.stripTrailingZeros()
                formatted.toPlainString()
            } catch (e: Exception) { amount ?: calculateFailed }
            else -> calculateFailed
        }
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else balanceDecimal.stripTrailingZeros()
                formatted.toPlainString()
            } catch (e: Exception) { availableBalance ?: "" }
        } else { "" }
        val escapedMarketTitle = escapeHtml(translateTelegramDisplayText(marketTitle))
        val escapedOutcome = escapeHtml(translateTelegramDisplayText(outcome))
        return mapOf(
            "order_id" to (orderId ?: unknown),
            "market_title" to escapedMarketTitle,
            "market_link" to marketLink,
            "side" to sideDisplay,
            "outcome" to escapedOutcome,
            "price" to formatPrice(price),
            "quantity" to formatQuantity(size),
            "amount" to amountDisplay,
            "account_name" to accountInfo,
            "available_balance" to availableBalanceDisplay,
            "leader_name" to (leaderName ?: ""),
            "config_name" to (configName ?: ""),
            "time" to time
        )
    }

    private fun buildOrderSuccessMessage(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        leaderName: String? = null,
        configName: String? = null,
        orderTime: Long? = null,
        availableBalance: String? = null
    ): String {
        val orderCreatedSuccess = messageSource.getMessage("notification.order.created.success", null, "订单创建成功", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val orderIdLabel = messageSource.getMessage("notification.order.id", null, "订单ID", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val availableBalanceLabel = messageSource.getMessage("notification.order.available_balance", null, "可用余额", locale)
        val unknown = messageSource.getMessage("common.unknown", null, "未知", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        val icon = when (side.uppercase()) {
            "BUY" -> "🚀"
            "SELL" -> "💰"
            else -> "📣"
        }
        val signalSourceLabel = messageSource
            .getMessage("notification.order.signal_source", null, "信号来源", locale)
            .orEmpty()
            .ifEmpty { "信号来源" }
        val accountInfo = buildAccountInfoWithSignalSource(
            accountName = accountName,
            walletAddress = walletAddress,
            unknownAccount = unknownAccount,
            leaderName = leaderName,
            configName = configName,
            signalSourceLabel = signalSourceLabel
        )
        val copyTradingInfo = mutableListOf<String>()
        if (!configName.isNullOrBlank()) {
            copyTradingInfo.add("配置: ${configName!!}")
        }
        if (!leaderName.isNullOrBlank()) {
            copyTradingInfo.add("下注人: ${leaderName!!}")
        }
        val copyTradingInfoText = if (copyTradingInfo.isNotEmpty()) {
            "\n• 跟单: ${copyTradingInfo.joinToString(", ")}"
        } else {
            ""
        }
        val time = if (orderTime != null) {
            DateUtils.formatDateTime(orderTime)
        } else {
            DateUtils.formatDateTime()
        }
        val escapedMarketTitle = escapeHtml(translateTelegramDisplayText(marketTitle))
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedCopyTradingInfo = if (copyTradingInfoText.isNotEmpty()) {
            copyTradingInfoText.replace("<", "&lt;").replace(">", "&gt;")
        } else {
            ""
        }
        val amountDisplay = if (amount != null) {
            try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) {
                    amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    amountDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                amount
            }
        } else {
            calculateFailed
        }
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> {
                "https://polymarket.com/event/$marketSlug"
            }
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> {
                "https://polymarket.com/condition/$marketId"
            }
            else -> null
        }
        
        val marketDisplay = if (marketLink != null) {
            "<a href=\"$marketLink\">$escapedMarketTitle</a>"
        } else {
            escapedMarketTitle
        }
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = escapeHtml(translateTelegramDisplayText(outcome))
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else {
            ""
        }
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) {
                    balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    balanceDecimal.stripTrailingZeros()
                }
                "\n• $availableBalanceLabel: <code>${formatted.toPlainString()}</code> USDC"
            } catch (e: Exception) {
                "\n• $availableBalanceLabel: <code>$availableBalance</code> USDC"
            }
        } else {
            ""
        }

        return """$icon <b>$orderCreatedSuccess</b>

📊 <b>$orderInfo：</b>
• $orderIdLabel: <code>${orderId ?: unknown}</code>
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> 份
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo$escapedCopyTradingInfo$availableBalanceDisplay

⏰ $timeLabel: <code>$time</code>"""
    }

    private fun buildCryptoTailOrderSuccessMessage(
        orderId: String?,
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        strategyName: String?,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale,
        orderTime: Long?
    ): String {
        val tailOrderSuccess = messageSource.getMessage("notification.tail.order.success", null, "加密价差策略下单成功", locale)
        val strategyLabel = messageSource.getMessage("notification.tail.strategy", null, "策略", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val orderIdLabel = messageSource.getMessage("notification.order.id", null, "订单ID", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val unknown: String = messageSource.getMessage("common.unknown", null, "未知", locale) ?: "未知"
        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = if (orderTime != null) DateUtils.formatDateTime(orderTime) else DateUtils.formatDateTime()
        val escapedMarketTitle = escapeHtml(translateTelegramDisplayText(marketTitle))
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val strategyDisplay = strategyName?.takeIf { it.isNotBlank() } ?: unknown
        val escapedStrategyName = strategyDisplay.replace("<", "&lt;").replace(">", "&gt;")
        val amountDisplay = if (amount != null) {
            try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else amountDecimal.stripTrailingZeros()
                formatted.toPlainString()
            } catch (e: Exception) { amount }
        } else calculateFailed
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> "https://polymarket.com/event/$marketSlug"
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> "https://polymarket.com/condition/$marketId"
            else -> null
        }
        val marketDisplay = if (marketLink != null) "<a href=\"$marketLink\">$escapedMarketTitle</a>" else escapedMarketTitle
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = escapeHtml(translateTelegramDisplayText(outcome))
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else ""
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)
        return """🚀 <b>$tailOrderSuccess</b>

📊 <b>$orderInfo：</b>
• $orderIdLabel: <code>${orderId ?: unknown}</code>
• $strategyLabel: $escapedStrategyName
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> 份
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo

⏰ $timeLabel: <code>$time</code>"""
    }

    private fun buildOrderFailureMessage(
        marketTitle: String,
        marketId: String?,
        marketSlug: String?,
        side: String,
        outcome: String?,
        price: String,
        size: String,
        amount: String?,
        errorMessage: String,
        accountName: String?,
        walletAddress: String?,
        locale: java.util.Locale
    ): String {
        val orderCreatedFailed = messageSource.getMessage("notification.order.created.failed", null, "订单创建失败", locale)
        val orderInfo = messageSource.getMessage("notification.order.info", null, "订单信息", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val sideLabel = messageSource.getMessage("notification.order.side", null, "方向", locale)
        val outcomeLabel = messageSource.getMessage("notification.order.outcome", null, "市场方向", locale)
        val priceLabel = messageSource.getMessage("notification.order.price", null, "价格", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val amountLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val errorInfo = messageSource.getMessage("notification.order.error_info", null, "错误信息", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val calculateFailed = messageSource.getMessage("notification.order.calculate_failed", null, "计算失败", locale)
        val sideDisplay = when (side.uppercase()) {
            "BUY" -> messageSource.getMessage("notification.order.side.buy", null, "买入", locale)
            "SELL" -> messageSource.getMessage("notification.order.side.sell", null, "卖出", locale)
            else -> side
        }
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)

        val time = DateUtils.formatDateTime()
        val shortErrorMessage = if (errorMessage.length > 500) {
            errorMessage.substring(0, 500) + "..."
        } else {
            errorMessage
        }
        val escapedMarketTitle = escapeHtml(translateTelegramDisplayText(marketTitle))
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedErrorMessage = shortErrorMessage.replace("<", "&lt;").replace(">", "&gt;")
        val amountDisplay = if (amount != null) {
            try {
                val amountDecimal = amount.toSafeBigDecimal()
                val formatted = if (amountDecimal.scale() > 4) {
                    amountDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    amountDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                amount
            }
        } else {
            calculateFailed
        }
        val marketLink = when {
            !marketSlug.isNullOrBlank() -> {
                "https://polymarket.com/event/$marketSlug"
            }
            !marketId.isNullOrBlank() && marketId.startsWith("0x") -> {
                "https://polymarket.com/condition/$marketId"
            }
            else -> null
        }
        
        val marketDisplay = if (marketLink != null) {
            "<a href=\"$marketLink\">$escapedMarketTitle</a>"
        } else {
            escapedMarketTitle
        }
        val outcomeDisplay = if (!outcome.isNullOrBlank()) {
            val escapedOutcome = escapeHtml(translateTelegramDisplayText(outcome))
            "\n• $outcomeLabel: <b>$escapedOutcome</b>"
        } else {
            ""
        }
        val priceDisplay = formatPrice(price)
        val sizeDisplay = formatQuantity(size)

        return """❌ <b>$orderCreatedFailed</b>

📊 <b>$orderInfo：</b>
• $marketLabel: $marketDisplay$outcomeDisplay
• $sideLabel: <b>$sideDisplay</b>
• $priceLabel: <code>$priceDisplay</code>
• $quantityLabel: <code>$sizeDisplay</code> 份
• $amountLabel: <code>$amountDisplay</code> USDC
• $accountLabel: $escapedAccountInfo

⚠️ <b>$errorInfo：</b>
<code>$escapedErrorMessage</code>

⏰ $timeLabel: <code>$time</code>"""
    }

    suspend fun sendRedeemNotification(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        totalRedeemedValue: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale? = null,
        availableBalance: String? = null
    ) {
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }
        
        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale) ?: "未知账户"
        val vars = buildRedeemSuccessVariables(
            accountName = accountName,
            walletAddress = walletAddress,
            transactionHash = transactionHash,
            totalRedeemedValue = totalRedeemedValue,
            availableBalance = availableBalance,
            unknownAccount = unknownAccount
        )
        val message = notificationTemplateService.renderTemplate("REDEEM_SUCCESS", vars)
        sendStandardMessage(message)
    }

    private fun buildRedeemSuccessVariables(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        totalRedeemedValue: String,
        availableBalance: String?,
        unknownAccount: String
    ): Map<String, String> {
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val totalValueDisplay = try {
            val d = totalRedeemedValue.toSafeBigDecimal()
            (if (d.scale() > 4) d.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else d.stripTrailingZeros()).toPlainString()
        } catch (e: Exception) { totalRedeemedValue }
        val availableBalanceDisplay = availableBalance?.let { ab ->
            try {
                val d = ab.toSafeBigDecimal()
                (if (d.scale() > 4) d.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else d.stripTrailingZeros()).toPlainString()
            } catch (e: Exception) { ab }
        } ?: ""
        return mapOf(
            "account_name" to accountInfo,
            "transaction_hash" to transactionHash.replace("<", "&lt;").replace(">", "&gt;"),
            "total_value" to totalValueDisplay,
            "available_balance" to availableBalanceDisplay,
            "time" to DateUtils.formatDateTime()
        )
    }
    
    private fun buildRedeemMessage(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        totalRedeemedValue: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale,
        availableBalance: String? = null
    ): String {
        val redeemSuccess = messageSource.getMessage("notification.redeem.success", null, "仓位赎回成功", locale)
        val redeemInfo = messageSource.getMessage("notification.redeem.info", null, "赎回信息", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val transactionHashLabel = messageSource.getMessage("notification.redeem.transaction_hash", null, "交易哈希", locale)
        val totalValueLabel = messageSource.getMessage("notification.redeem.total_value", null, "赎回总价值", locale)
        val positionsLabel = messageSource.getMessage("notification.redeem.positions", null, "赎回仓位", locale)
        val marketLabel = messageSource.getMessage("notification.order.market", null, "市场", locale)
        val quantityLabel = messageSource.getMessage("notification.order.quantity", null, "数量", locale)
        val valueLabel = messageSource.getMessage("notification.order.amount", null, "金额", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val availableBalanceLabel = messageSource.getMessage("notification.redeem.available_balance", null, "可用余额", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        
        val time = DateUtils.formatDateTime()
        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedTxHash = transactionHash.replace("<", "&lt;").replace(">", "&gt;")
        val totalValueDisplay = try {
            val totalValueDecimal = totalRedeemedValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
            } else {
                totalValueDecimal.stripTrailingZeros()
            }
            formatted.toPlainString()
        } catch (e: Exception) {
            totalRedeemedValue
        }
        val positionsText = positions.joinToString("\n") { position ->
            val quantityDisplay = formatQuantity(position.quantity)
            val valueDisplay = try {
                val valueDecimal = position.value.toSafeBigDecimal()
                val formatted = if (valueDecimal.scale() > 4) {
                    valueDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    valueDecimal.stripTrailingZeros()
                }
                formatted.toPlainString()
            } catch (e: Exception) {
                position.value
            }
            "  • ${position.marketId.substring(0, 8)}... (${translateTelegramDisplayText(position.side)}): $quantityDisplay 份 = $valueDisplay USDC"
        }
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) {
                    balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    balanceDecimal.stripTrailingZeros()
                }
                "\n• $availableBalanceLabel: <code>${formatted.toPlainString()}</code> USDC"
            } catch (e: Exception) {
                "\n• $availableBalanceLabel: <code>$availableBalance</code> USDC"
            }
        } else {
            ""
        }
        
        return """💸 <b>$redeemSuccess</b>

📊 <b>$redeemInfo：</b>
• $accountLabel: $escapedAccountInfo
• $transactionHashLabel: <code>$escapedTxHash</code>
• $totalValueLabel: <code>$totalValueDisplay</code> USDC$availableBalanceDisplay

📦 <b>$positionsLabel：</b>
$positionsText

⏰ $timeLabel: <code>$time</code>"""
    }

    suspend fun sendRedeemNoReturnNotification(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale? = null,
        availableBalance: String? = null
    ) {
        val currentLocale = locale ?: try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            logger.warn("获取语言设置失败，使用默认语言: ${e.message}", e)
            java.util.Locale("zh", "CN")
        }

        val unknownAccount = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", currentLocale) ?: "未知账户"
        val vars = buildRedeemNoReturnVariables(
            accountName = accountName,
            walletAddress = walletAddress,
            transactionHash = transactionHash,
            availableBalance = availableBalance,
            unknownAccount = unknownAccount
        )
        val message = notificationTemplateService.renderTemplate("REDEEM_NO_RETURN", vars)
        sendStandardMessage(message)
    }

    private fun buildRedeemNoReturnVariables(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        availableBalance: String?,
        unknownAccount: String
    ): Map<String, String> {
        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val availableBalanceDisplay = availableBalance?.let { ab ->
            try {
                val d = ab.toSafeBigDecimal()
                (if (d.scale() > 4) d.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros() else d.stripTrailingZeros()).toPlainString()
            } catch (e: Exception) { ab }
        } ?: ""
        return mapOf(
            "account_name" to accountInfo,
            "transaction_hash" to transactionHash.replace("<", "&lt;").replace(">", "&gt;"),
            "available_balance" to availableBalanceDisplay,
            "time" to DateUtils.formatDateTime()
        )
    }

    private fun buildRedeemNoReturnMessage(
        accountName: String?,
        walletAddress: String?,
        transactionHash: String,
        positions: List<com.wrbug.polymarketbot.dto.RedeemedPositionInfo>,
        locale: java.util.Locale,
        availableBalance: String? = null
    ): String {
        val noReturnTitle = messageSource.getMessage("notification.redeem.no_return.title", null, "仓位已结算（无收益）", locale)
        val noReturnInfo = messageSource.getMessage("notification.redeem.no_return.info", null, "结算信息", locale)
        val noReturnMessage = messageSource.getMessage("notification.redeem.no_return.message", null, "市场已结算，您的预测未命中，赎回价值为 0。", locale)
        val accountLabel = messageSource.getMessage("notification.order.account", null, "账户", locale)
        val transactionHashLabel = messageSource.getMessage("notification.redeem.transaction_hash", null, "交易哈希", locale)
        val positionsLabel = messageSource.getMessage("notification.redeem.no_return.positions", null, "结算仓位", locale)
        val timeLabel = messageSource.getMessage("notification.order.time", null, "时间", locale)
        val availableBalanceLabel = messageSource.getMessage("notification.redeem.available_balance", null, "可用余额", locale)
        val unknownAccount: String = messageSource.getMessage("notification.order.unknown_account", null, "未知账户", locale) ?: "未知账户"

        val accountInfo = buildAccountInfo(accountName, walletAddress, unknownAccount)
        val time = DateUtils.formatDateTime()

        val escapedAccountInfo = accountInfo.replace("<", "&lt;").replace(">", "&gt;")
        val escapedTxHash = transactionHash.replace("<", "&lt;").replace(">", "&gt;")

        val positionsText = positions.joinToString("\n") { position ->
            val quantityDisplay = formatQuantity(position.quantity)
            "  • ${position.marketId.substring(0, 8)}... (${translateTelegramDisplayText(position.side)}): $quantityDisplay 份"
        }
        val availableBalanceDisplay = if (!availableBalance.isNullOrBlank()) {
            try {
                val balanceDecimal = availableBalance.toSafeBigDecimal()
                val formatted = if (balanceDecimal.scale() > 4) {
                    balanceDecimal.setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros()
                } else {
                    balanceDecimal.stripTrailingZeros()
                }
                "\n• $availableBalanceLabel: <code>${formatted.toPlainString()}</code> USDC"
            } catch (e: Exception) {
                "\n• $availableBalanceLabel: <code>$availableBalance</code> USDC"
            }
        } else {
            ""
        }

        return """📋 <b>$noReturnTitle</b>

📊 <b>$noReturnInfo：</b>
<i>$noReturnMessage</i>

• $accountLabel: $escapedAccountInfo
• $transactionHashLabel: <code>$escapedTxHash</code>$availableBalanceDisplay

📦 <b>$positionsLabel：</b>
$positionsText

⏰ $timeLabel: <code>$time</code>"""
    }

    private fun maskAddress(address: String): String {
        if (address.length <= 10) {
            return address
        }
        return "${address.substring(0, 6)}...${address.substring(address.length - 4)}"
    }
}
