package com.wrbug.polymarketbot.dto

data class FilteredOrderListRequest(
    val copyTradingId: Long,
    val filterType: String? = null,
    val page: Int? = 1,
    val limit: Int? = 20,
    val startTime: Long? = null,
    val endTime: Long? = null
)

data class FilteredOrderDto(
    val id: Long,
    val copyTradingId: Long,
    val accountId: Long,
    val accountName: String?,
    val leaderId: Long,
    val leaderName: String?,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val side: String,
    val outcomeIndex: Int?,
    val outcome: String?,
    val price: String,
    val size: String,
    val calculatedQuantity: String?,
    val filterReason: String,
    val filterType: String,
    val createdAt: Long
)

data class FilteredOrderListResponse(
    val list: List<FilteredOrderDto>,
    val total: Long,
    val page: Int,
    val limit: Int
)

