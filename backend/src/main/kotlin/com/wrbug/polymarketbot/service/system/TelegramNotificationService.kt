package com.wrbug.polymarketbot.service.system

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.NotificationConfigData
import com.wrbug.polymarketbot.dto.NotificationConfigDto
import com.wrbug.polymarketbot.dto.TelegramConfigData
import com.wrbug.polymarketbot.util.TextEncodingUtils
import com.wrbug.polymarketbot.util.createClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

internal enum class TelegramNotificationAudience {
    ALL,
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
            .filter { (_, telegramConfig) -> telegramConfig.data.monitorModeEnabled }
            .map { it.first }
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

private val telegramDisplayTextTranslations = listOf(
    Regex("""\bvs\.?""", RegexOption.IGNORE_CASE) to "对",
    Regex("""\bMoneyline:\s*""", RegexOption.IGNORE_CASE) to "胜负：",
    Regex("""\bSpread:\s*""", RegexOption.IGNORE_CASE) to "让分：",
    Regex("""\bSpreads:\s*""", RegexOption.IGNORE_CASE) to "让分：",
    Regex("""\bTotal Goals:\s*""", RegexOption.IGNORE_CASE) to "总进球：",
    Regex("""\bTotal:\s*""", RegexOption.IGNORE_CASE) to "大小：",
    Regex("""\bTotals:\s*""", RegexOption.IGNORE_CASE) to "大小：",
    Regex("""\bMoneyline\b""", RegexOption.IGNORE_CASE) to "胜负",
    Regex("""\bSpread\b""", RegexOption.IGNORE_CASE) to "让分",
    Regex("""\bSpreads\b""", RegexOption.IGNORE_CASE) to "让分",
    Regex("""\bTotal Goals\b""", RegexOption.IGNORE_CASE) to "总进球",
    Regex("""\bTotal\b""", RegexOption.IGNORE_CASE) to "大小",
    Regex("""\bTotals\b""", RegexOption.IGNORE_CASE) to "大小",
    Regex("""\bOdds\s*&\s*Predictions\b""", RegexOption.IGNORE_CASE) to "赔率与预测",
    Regex("""\bOdds\b""", RegexOption.IGNORE_CASE) to "赔率",
    Regex("""\bPredictions\b""", RegexOption.IGNORE_CASE) to "预测",
    Regex("""\bYes\b""", RegexOption.IGNORE_CASE) to "是",
    Regex("""\bNo\b""", RegexOption.IGNORE_CASE) to "否",
    Regex("""\bSeries\b""", RegexOption.IGNORE_CASE) to "系列赛",
    Regex("""\bPlayoffs\b""", RegexOption.IGNORE_CASE) to "季后赛",
    Regex("""\bMagic\b""", RegexOption.IGNORE_CASE) to "魔术",
    Regex("""\bPistons\b""", RegexOption.IGNORE_CASE) to "活塞",
    Regex("""\bCeltics\b""", RegexOption.IGNORE_CASE) to "凯尔特人",
    Regex("""\bLakers\b""", RegexOption.IGNORE_CASE) to "湖人",
    Regex("""\bWarriors\b""", RegexOption.IGNORE_CASE) to "勇士",
    Regex("""\bKnicks\b""", RegexOption.IGNORE_CASE) to "尼克斯",
    Regex("""\bNets\b""", RegexOption.IGNORE_CASE) to "篮网",
    Regex("""\bBulls\b""", RegexOption.IGNORE_CASE) to "公牛",
    Regex("""\bHeat\b""", RegexOption.IGNORE_CASE) to "热火",
    Regex("""\bBucks\b""", RegexOption.IGNORE_CASE) to "雄鹿",
    Regex("""\bSuns\b""", RegexOption.IGNORE_CASE) to "太阳",
    Regex("""\bMavericks\b""", RegexOption.IGNORE_CASE) to "独行侠",
    Regex("""\bNuggets\b""", RegexOption.IGNORE_CASE) to "掘金",
    Regex("""\bClippers\b""", RegexOption.IGNORE_CASE) to "快船",
    Regex("""\bKings\b""", RegexOption.IGNORE_CASE) to "国王",
    Regex("""\bGrizzlies\b""", RegexOption.IGNORE_CASE) to "灰熊",
    Regex("""\bPelicans\b""", RegexOption.IGNORE_CASE) to "鹈鹕",
    Regex("""\bTimberwolves\b""", RegexOption.IGNORE_CASE) to "森林狼",
    Regex("""\bThunder\b""", RegexOption.IGNORE_CASE) to "雷霆",
    Regex("""\bRockets\b""", RegexOption.IGNORE_CASE) to "火箭",
    Regex("""\bSpurs\b""", RegexOption.IGNORE_CASE) to "马刺",
    Regex("""\bJazz\b""", RegexOption.IGNORE_CASE) to "爵士",
    Regex("""\bTrail Blazers\b""", RegexOption.IGNORE_CASE) to "开拓者",
    Regex("""\bHawks\b""", RegexOption.IGNORE_CASE) to "老鹰",
    Regex("""\bHornets\b""", RegexOption.IGNORE_CASE) to "黄蜂",
    Regex("""\bWizards\b""", RegexOption.IGNORE_CASE) to "奇才",
    Regex("""\bRaptors\b""", RegexOption.IGNORE_CASE) to "猛龙",
    Regex("""\bPacers\b""", RegexOption.IGNORE_CASE) to "步行者",
    Regex("""\bCavaliers\b""", RegexOption.IGNORE_CASE) to "骑士",
    Regex("""\b76ers\b""", RegexOption.IGNORE_CASE) to "76人",
    Regex("""\bOrlando\b""", RegexOption.IGNORE_CASE) to "奥兰多",
    Regex("""\bDetroit\b""", RegexOption.IGNORE_CASE) to "底特律",
    Regex("""\bWild\b""", RegexOption.IGNORE_CASE) to "野队",
    Regex("""\bStars\b""", RegexOption.IGNORE_CASE) to "星队",
    Regex("""\bPoints\b""", RegexOption.IGNORE_CASE) to "得分",
    Regex("""\bPoint\b""", RegexOption.IGNORE_CASE) to "得分",
    Regex("""\bGoals\b""", RegexOption.IGNORE_CASE) to "进球",
    Regex("""\bGoal\b""", RegexOption.IGNORE_CASE) to "进球",
    Regex("""\bOver\b""", RegexOption.IGNORE_CASE) to "大",
    Regex("""\bUnder\b""", RegexOption.IGNORE_CASE) to "小",
    Regex("""\bDraw\b""", RegexOption.IGNORE_CASE) to "平局"
)

internal fun translateTelegramDisplayText(value: String?): String {
    val repaired = TextEncodingUtils.repairMojibake(value.orEmpty())
    return telegramDisplayTextTranslations.fold(repaired) { current, (pattern, replacement) ->
        pattern.replace(current, replacement)
    }
}

@Service
class TelegramNotificationService(
    private val notificationConfigService: NotificationConfigService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(TelegramNotificationService::class.java)
    private val okHttpClient = createClient()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    private val apiBaseUrl = "https://api.telegram.org/bot"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun sendTestMessage(message: String, configId: Long?): Boolean {
        if (configId == null) {
            return sendTestMessage(message)
        }

        return try {
            val config = notificationConfigService.getConfigById(configId) ?: return false
            if (!config.enabled || !isTelegramNotificationConfigType(config.type)) {
                return false
            }
            when (val configData = config.config) {
                is NotificationConfigData.Telegram -> sendTelegramMessage(configData.data, message)
            }
        } catch (e: Exception) {
            logger.error("使用指定配置发送测试消息失败: configId={}, message={}", configId, e.message, e)
            false
        }
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

            coroutineScope {
                configs.map { config ->
                    async(Dispatchers.IO) {
                        when (val configData = config.config) {
                            is NotificationConfigData.Telegram -> sendTelegramMessage(configData.data, message)
                        }
                    }
                }.awaitAll().any { it }
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
            logger.error("检查监控模式状态失败: ${e.message}", e)
            false
        }
    }

    suspend fun sendMessage(message: String) {
        sendMessage(message, TelegramNotificationAudience.ALL)
    }

    suspend fun sendBettingSuccessMessage(message: String) {
        try {
            val configs = notificationConfigService.getEnabledConfigsByType(BETTING_SUCCESS_TELEGRAM_TYPE)
            if (configs.isEmpty()) {
                logger.debug("No betting success Telegram configs enabled, skipping message")
                return
            }
            sendMessageToConfigs(message, configs)
        } catch (e: Exception) {
            logger.error("Failed to send betting success Telegram message: ${e.message}", e)
        }
    }

    suspend fun sendMonitorMessage(message: String) {
        sendMessage(message, TelegramNotificationAudience.MONITOR_ONLY)
    }

    suspend fun sendMonitorMessageToConfigs(message: String, configs: List<NotificationConfigDto>) {
        sendMessageToConfigs(message, configs)
    }

    private suspend fun sendMessage(message: String, audience: TelegramNotificationAudience) {
        try {
            val configs = filterTelegramConfigsForAudience(
                notificationConfigService.getEnabledConfigsByType("telegram"),
                audience
            )
            if (configs.isEmpty()) {
                logger.debug("没有启用的 Telegram 配置，跳过发送消息")
                return
            }
            sendMessageToConfigs(message, configs)
        } catch (e: Exception) {
            logger.error("发送通知消息失败: ${e.message}", e)
        }
    }

    private fun sendMessageToConfigs(message: String, configs: List<NotificationConfigDto>) {
        if (configs.isEmpty()) {
            logger.debug("没有匹配的 Telegram 配置，跳过发送消息")
            return
        }

        configs.forEach { config ->
            scope.launch {
                try {
                    when (val configData = config.config) {
                        is NotificationConfigData.Telegram -> sendTelegramMessage(configData.data, message)
                    }
                } catch (e: Exception) {
                    logger.error("发送 Telegram 消息失败: configId={}, message={}", config.id, e.message, e)
                }
            }
        }
    }

    private suspend fun sendTelegramMessage(config: TelegramConfigData, message: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                config.chatIds.map { chatId ->
                    async { sendToSingleChat(config.botToken, chatId, message) }
                }.awaitAll().any { it }
            } catch (e: Exception) {
                logger.error("发送 Telegram 消息失败: ${e.message}", e)
                false
            }
        }
    }

    suspend fun getChatIds(botToken: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$apiBaseUrl$botToken/getUpdates")
                    .get()
                    .build()
                val responseBody = okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string()
                        return@withContext Result.failure(
                            Exception("获取 Chat IDs 失败: code=${response.code}, body=$errorBody")
                        )
                    }
                    response.body?.string().orEmpty()
                }
                val jsonNode = objectMapper.readTree(responseBody)

                if (jsonNode.get("ok")?.asBoolean() == false) {
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
            val target = parseTelegramChatTarget(chatId)
            val payload = mutableMapOf<String, Any>(
                "chat_id" to target.chatId,
                "text" to message,
                "parse_mode" to "HTML",
                "disable_web_page_preview" to disableWebPagePreview
            )
            target.messageThreadId?.let { payload["message_thread_id"] = it }
            replyMarkup?.let { payload["reply_markup"] = it }

            val request = Request.Builder()
                .url("$apiBaseUrl$botToken/sendMessage")
                .post(objectMapper.writeValueAsString(payload).toRequestBody("application/json".toMediaType()))
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
}
