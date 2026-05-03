package com.wrbug.polymarketbot.dto

data class LargeBetMonitorConfigDto(
    val id: Long?,
    val enabled: Boolean,
    val footballEnabled: Boolean,
    val basketballEnabled: Boolean,
    val singleTradeThreshold: String,
    val cumulativeTradeThreshold: String,
    val rollingWindowMinutes: Int,
    val checkIntervalSeconds: Int,
    val telegramConfigId: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

data class LargeBetMonitorConfigUpdateRequest(
    val enabled: Boolean,
    val footballEnabled: Boolean,
    val basketballEnabled: Boolean,
    val singleTradeThreshold: String,
    val cumulativeTradeThreshold: String,
    val rollingWindowMinutes: Int,
    val checkIntervalSeconds: Int,
    val telegramConfigId: Long?
)

data class LargeBetWatchRecordDto(
    val id: Long?,
    val traderName: String?,
    val traderAddress: String,
    val profileUrl: String,
    val marketTitle: String,
    val marketSlug: String?,
    val marketId: String,
    val sportType: String,
    val outcome: String,
    val triggerReason: String,
    val lastSingleAmount: String,
    val lastCumulativeAmount: String,
    val firstTriggeredAt: Long,
    val lastTriggeredAt: Long,
    val triggerCount: Int
)

data class LargeBetMonitorStatusDto(
    val enabled: Boolean,
    val connected: Boolean,
    val trackedBuckets: Int
)
