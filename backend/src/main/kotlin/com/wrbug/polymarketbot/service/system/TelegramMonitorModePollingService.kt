package com.wrbug.polymarketbot.service.system

import com.wrbug.polymarketbot.dto.NotificationConfigData
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

data class TelegramIncomingUpdate(
    val updateId: Long,
    val chatId: String,
    val text: String,
    val messageThreadId: Int? = null,
    val callbackQueryId: String? = null,
    val callbackData: String? = null
)

private const val MONITOR_PHASE_LIVE_CALLBACK = "odds-monitor:phase:live"
private const val MONITOR_PHASE_PREMATCH_CALLBACK = "odds-monitor:phase:prematch"

internal fun isMonitorPhaseControlCommand(text: String): Boolean {
    val normalized = text.trim().lowercase()
    return normalized in setOf("/start", "/monitor", "/mode", "/phase", "滚球", "赛前")
}

internal fun monitorPhaseCallbackMode(data: String?): Boolean? {
    return when (data?.trim()) {
        MONITOR_PHASE_LIVE_CALLBACK -> true
        MONITOR_PHASE_PREMATCH_CALLBACK -> false
        else -> null
    }
}

internal fun buildMonitorPhaseControlMessage(liveOnlyModeEnabled: Boolean): String {
    val mode = if (liveOnlyModeEnabled) "滚球" else "赛前"
    val description = if (liveOnlyModeEnabled) {
        "只推送已经开赛的赔率变化。"
    } else {
        "只推送未开赛的赔率变化。"
    }
    return "赔率监控模式\n\n当前模式：$mode\n$description\n\n点击下方按钮切换。"
}

internal fun buildMonitorPhaseControlKeyboard(liveOnlyModeEnabled: Boolean): Map<String, Any> {
    val liveLabel = if (liveOnlyModeEnabled) "滚球 ✓" else "滚球"
    val prematchLabel = if (liveOnlyModeEnabled) "赛前" else "赛前 ✓"
    return mapOf(
        "inline_keyboard" to listOf(
            listOf(
                mapOf("text" to liveLabel, "callback_data" to MONITOR_PHASE_LIVE_CALLBACK),
                mapOf("text" to prematchLabel, "callback_data" to MONITOR_PHASE_PREMATCH_CALLBACK)
            )
        )
    )
}

internal fun telegramUpdateMatchesConfiguredTarget(
    update: TelegramIncomingUpdate,
    chatIds: List<String>
): Boolean {
    if (chatIds.isEmpty()) {
        return false
    }

    return chatIds.any { configured ->
        val target = parseTelegramChatTarget(configured)
        target.chatId == update.chatId &&
            (target.messageThreadId == null || target.messageThreadId == update.messageThreadId)
    }
}

internal fun telegramUpdateTargetPath(update: TelegramIncomingUpdate): String {
    return update.messageThreadId?.let { "${update.chatId}:$it" } ?: update.chatId
}

@Service
class TelegramMonitorModePollingService(
    private val notificationConfigService: NotificationConfigService,
    private val telegramNotificationService: TelegramNotificationService
) {
    private val logger = LoggerFactory.getLogger(TelegramMonitorModePollingService::class.java)
    private val offsets = ConcurrentHashMap<String, Long>()

    @Scheduled(fixedDelay = 5_000)
    fun poll() {
        runBlocking {
            val configs = notificationConfigService.getEnabledConfigsByType("telegram").filter { config ->
                val telegram = config.config as? NotificationConfigData.Telegram ?: return@filter false
                telegram.data.monitorModeEnabled
            }
            configs.forEach { config ->
                val telegram = config.config as? NotificationConfigData.Telegram ?: return@forEach
                val token = telegram.data.botToken.takeIf { it.isNotBlank() } ?: return@forEach
                val updates = telegramNotificationService.getUpdates(token, offsets[token]).getOrElse {
                    logger.warn("Telegram 监控模式轮询失败: {}", it.message)
                    return@forEach
                }
                if (updates.isEmpty()) return@forEach

                val nextOffset = updates.maxOf { it.updateId } + 1
                offsets[token] = nextOffset

                updates.forEach updateLoop@{ update ->
                    if (!telegramUpdateMatchesConfiguredTarget(update, telegram.data.chatIds)) {
                        return@updateLoop
                    }
                    handleMonitorPhaseUpdate(config.id, telegram.data.monitorModeEnabled, telegram.data.liveOnlyModeEnabled, token, update)
                }
            }
        }
    }

    private suspend fun handleMonitorPhaseUpdate(
        configId: Long?,
        monitorModeEnabled: Boolean,
        currentLiveOnlyModeEnabled: Boolean,
        botToken: String,
        update: TelegramIncomingUpdate
    ): Boolean {
        val targetPath = telegramUpdateTargetPath(update)
        val callbackMode = monitorPhaseCallbackMode(update.callbackData)
        if (callbackMode != null) {
            if (configId == null || !monitorModeEnabled) {
                update.callbackQueryId?.let {
                    telegramNotificationService.answerCallbackQuery(botToken, it, "请先开启监控模式")
                }
                return true
            }
            notificationConfigService.updateTelegramLiveOnlyMode(configId, callbackMode)
                .onSuccess {
                    update.callbackQueryId?.let { callbackId ->
                        telegramNotificationService.answerCallbackQuery(
                            botToken,
                            callbackId,
                            if (callbackMode) "已切换为滚球" else "已切换为赛前"
                        )
                    }
                    telegramNotificationService.sendMonitorPhaseControlMessage(botToken, targetPath, callbackMode)
                }
                .onFailure { error ->
                    logger.warn("Telegram 监控模式切换失败: {}", error.message)
                    update.callbackQueryId?.let {
                        telegramNotificationService.answerCallbackQuery(botToken, it, "切换失败")
                    }
                }
            return true
        }

        if (!isMonitorPhaseControlCommand(update.text)) {
            return false
        }
        if (!monitorModeEnabled) {
            telegramNotificationService.sendMessage(botToken, targetPath, "请先在通知设置里开启监控模式。")
            return true
        }
        telegramNotificationService.sendMonitorPhaseControlMessage(
            botToken = botToken,
            chatId = targetPath,
            liveOnlyModeEnabled = currentLiveOnlyModeEnabled
        )
        return true
    }
}
