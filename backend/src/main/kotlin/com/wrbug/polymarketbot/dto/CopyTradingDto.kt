package com.wrbug.polymarketbot.dto

data class CopyTradingCreateRequest(
    val accountId: Long,
    val leaderId: Long,
    val enabled: Boolean = true,
    val followSettingsEnabled: Boolean? = null,
    val templateId: Long? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    val minOrderDepth: String? = null,
    val maxSpread: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val maxPositionValue: String? = null,
    val keywordFilterMode: String? = null,
    val keywords: List<String>? = null,
    val configName: String? = null,
    val pushFailedOrders: Boolean? = null,
    val pushFilteredOrders: Boolean? = null,
    val notificationRoutes: List<CopyTradingNotificationRouteDto>? = null,
    val maxMarketEndDate: Long? = null
)

data class CopyTradingUpdateRequest(
    val copyTradingId: Long,
    val enabled: Boolean? = null,
    val followSettingsEnabled: Boolean? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val priceTolerance: String? = null,
    val delaySeconds: Int? = null,
    val pollIntervalSeconds: Int? = null,
    val useWebSocket: Boolean? = null,
    val websocketReconnectInterval: Int? = null,
    val websocketMaxRetries: Int? = null,
    val supportSell: Boolean? = null,
    val minOrderDepth: String? = null,
    val maxSpread: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val maxPositionValue: String? = null,
    val keywordFilterMode: String? = null,
    val keywords: List<String>? = null,
    val configName: String? = null,
    val pushFailedOrders: Boolean? = null,
    val pushFilteredOrders: Boolean? = null,
    val notificationRoutes: List<CopyTradingNotificationRouteDto>? = null,
    val maxMarketEndDate: Long? = null
)

data class CopyTradingNotificationRouteDto(
    val telegramConfigId: Long,
    val categories: List<String> = emptyList(),
    val notificationTypes: List<String> = emptyList()
)

data class CopyTradingListRequest(
    val accountId: Long? = null,
    val leaderId: Long? = null,
    val enabled: Boolean? = null
)

data class CopyTradingDetailRequest(
    val copyTradingId: Long
)

data class CopyTradingUpdateStatusRequest(
    val copyTradingId: Long,
    val enabled: Boolean
)

data class CopyTradingDeleteRequest(
    val copyTradingId: Long
)

data class AccountTemplatesRequest(
    val accountId: Long
)

data class FollowAmountRuleDto(
    val id: Long? = null,
    val minLeaderAmount: String,
    val maxLeaderAmount: String? = null,
    val followAmount: String,
    val followMaxAmount: String,
    val sortOrder: Int
)

data class CopyTradingDto(
    val id: Long,
    val accountId: Long,
    val accountName: String?,
    val walletAddress: String,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val enabled: Boolean,
    val followSettingsEnabled: Boolean,
    val maxOrderSize: String,
    val minOrderSize: String,
    val maxDailyLoss: String,
    val maxDailyOrders: Int,
    val priceTolerance: String,
    val delaySeconds: Int,
    val pollIntervalSeconds: Int,
    val useWebSocket: Boolean,
    val websocketReconnectInterval: Int,
    val websocketMaxRetries: Int,
    val supportSell: Boolean,
    val minOrderDepth: String?,
    val maxSpread: String?,
    val minPrice: String?,
    val maxPrice: String?,
    val maxPositionValue: String? = null,
    val keywordFilterMode: String? = null,
    val keywords: List<String>? = null,
    val configName: String? = null,
    val pushFailedOrders: Boolean = false,
    val pushFilteredOrders: Boolean = false,
    val notificationRoutes: List<CopyTradingNotificationRouteDto> = emptyList(),
    val maxMarketEndDate: Long? = null,
    val followRules: List<FollowAmountRuleDto> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

data class CopyTradingListResponse(
    val list: List<CopyTradingDto>,
    val total: Long
)

data class AccountTemplateDto(
    val templateId: Long? = null,
    val templateName: String? = null,
    val copyTradingId: Long,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val enabled: Boolean
)

data class AccountTemplatesResponse(
    val list: List<AccountTemplateDto>,
    val total: Long
)

data class FollowAmountRuleSaveItem(
    val minLeaderAmount: String,
    val maxLeaderAmount: String? = null,
    val followAmount: String,
    val followMaxAmount: String
)

data class FollowSettingsDetailRequest(
    val copyTradingId: Long
)

data class FollowSettingsSaveRequest(
    val copyTradingId: Long,
    val enabled: Boolean,
    val rules: List<FollowAmountRuleSaveItem> = emptyList()
)

data class FollowSettingsResponse(
    val copyTradingId: Long,
    val enabled: Boolean,
    val rules: List<FollowAmountRuleDto>
)

data class LeaderGroupControlUpdateRequest(
    val leaderId: Long,
    val autoPauseEnabled: Boolean,
    val profitTakeEnabled: Boolean,
    val profitTakePrice: String,
    val drawdownThresholdPercent: String
)

data class LeaderGroupControlDto(
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val autoPauseEnabled: Boolean,
    val profitTakeEnabled: Boolean,
    val profitTakePrice: String,
    val status: String,
    val pausedReason: String?,
    val lastPeakPnl: String,
    val currentPnl: String,
    val currentDrawdownPercent: String,
    val drawdownThresholdPercent: String,
    val autoPausedAt: Long?,
    val lastEvaluatedAt: Long?,
    val trackedWindowDays: Int = 7
)

data class LeaderGroupControlListRequest(
    val leaderIds: List<Long>? = null
)

data class LeaderGroupControlListResponse(
    val list: List<LeaderGroupControlDto>
)

data class LeaderGroupActionRequest(
    val leaderId: Long
)
