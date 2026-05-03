package com.wrbug.polymarketbot.service.copytrading.monitor

data class CopyTradingMonitoringRefreshEvent(
    val leaderId: Long,
    val accountIds: List<Long>
)
