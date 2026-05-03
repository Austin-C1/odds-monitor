package com.wrbug.polymarketbot.dto

data class LeaderAddRequest(
    val leaderAddress: String,
    val leaderName: String? = null,
    val category: String? = null,
    val customGroup: String? = null,
    val remark: String? = null,
    val website: String? = null
)

data class LeaderUpdateRequest(
    val leaderId: Long,
    val leaderName: String? = null,
    val category: String? = null,
    val customGroup: String? = null,
    val remark: String? = null,
    val website: String? = null
)

data class LeaderDeleteRequest(
    val leaderId: Long
)

data class LeaderListRequest(
    val category: String? = null
)

data class LeaderBalanceRequest(
    val leaderId: Long
)

data class LeaderDto(
    val id: Long,
    val leaderAddress: String,
    val leaderName: String?,
    val category: String?,
    val customGroup: String? = null,
    val remark: String? = null,
    val website: String? = null,
    val copyTradingCount: Long = 0,
    val monitoringEnabled: Boolean = false,
    val backtestCount: Long = 0,
    val totalOrders: Long? = null,
    val totalPnl: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

data class LeaderListResponse(
    val list: List<LeaderDto>,
    val total: Long
)

data class LeaderBalanceResponse(
    val leaderId: Long,
    val leaderAddress: String,
    val leaderName: String?,
    val availableBalance: String,
    val positionBalance: String,
    val totalBalance: String,
    val positions: List<PositionDto> = emptyList()
)
