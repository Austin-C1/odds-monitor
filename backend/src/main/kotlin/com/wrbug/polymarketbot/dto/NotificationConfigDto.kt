package com.wrbug.polymarketbot.dto

data class NotificationConfigDto(
    val id: Long? = null,
    val type: String,
    val name: String,
    val enabled: Boolean,
    val config: NotificationConfigData,
    val createdAt: Long? = null,
    val updatedAt: Long? = null
)

data class TelegramConfigData(
    val botToken: String,
    val chatIds: List<String>,
    val monitorModeEnabled: Boolean = false,
    val liveOnlyModeEnabled: Boolean = false,
    val testModeEnabled: Boolean = false,
    val prematchWindowMinutes: Int? = null,
    val handicapCombinedWaterMin: String? = null,
    val totalCombinedWaterMin: String? = null,
    val handicapOddsMoveMin: String? = null,
    val totalOddsMoveMin: String? = null,
    val moneylineOddsMoveMin: String? = null
)

sealed class NotificationConfigData {
    data class Telegram(val data: TelegramConfigData) : NotificationConfigData()
    // data class Discord(val data: DiscordConfigData) : NotificationConfigData()
    // data class Slack(val data: SlackConfigData) : NotificationConfigData()
}

data class NotificationConfigRequest(
    val type: String,
    val name: String,
    val enabled: Boolean? = true,
    val config: Map<String, Any>
)

data class TestNotificationRequest(
    val configId: Long? = null,
    val message: String? = null
)

