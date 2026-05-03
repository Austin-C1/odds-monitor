package com.wrbug.polymarketbot.dto

import java.math.BigDecimal

data class BacktestCreateRequest(
    val taskName: String,
    val leaderId: Long,  // Leader ID
    val initialBalance: String,
    val backtestDays: Int,
    val copyMode: String? = null,
    val copyRatio: String? = null,
    val fixedAmount: String? = null,
    val maxOrderSize: String? = null,
    val minOrderSize: String? = null,
    val maxDailyLoss: String? = null,
    val maxDailyOrders: Int? = null,
    val supportSell: Boolean? = null,
    val keywordFilterMode: String? = null,
    val keywords: List<String>? = null,
    val maxPositionValue: String? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val pageForResume: Int? = null
)

data class BacktestListRequest(
    val leaderId: Long? = null,
    val status: String? = null,  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED
    val sortBy: String? = null,  // profitAmount / profitRate / createdAt
    val sortOrder: String? = null,  // asc / desc
    val page: Int = 1,
    val size: Int = 20
)

data class BacktestDetailRequest(
    val id: Long
)

data class BacktestTradeListRequest(
    val taskId: Long,
    val page: Int = 1,
    val size: Int = 20
)

data class BacktestProgressRequest(
    val id: Long
)

data class BacktestStopRequest(
    val id: Long
)

data class BacktestDeleteRequest(
    val id: Long
)

data class BacktestRetryRequest(
    val id: Long
)

data class BacktestRerunRequest(
    val id: Long,
    val taskName: String? = null
)

data class BacktestListResponse(
    val list: List<BacktestTaskDto>,
    val total: Long,
    val page: Int,
    val size: Int
)

data class BacktestDetailResponse(
    val task: BacktestTaskDto,
    val config: BacktestConfigDto,
    val statistics: BacktestStatisticsDto
)

data class BacktestTradeListResponse(
    val list: List<BacktestTradeDto>,
    val total: Long,
    val page: Int,
    val size: Int
)

data class BacktestProgressResponse(
    val progress: Int,
    val currentBalance: String,
    val totalTrades: Int,
    val status: String
)

data class BacktestTaskDto(
    val id: Long,
    val taskName: String,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String?,
    val initialBalance: String,
    val finalBalance: String?,
    val profitAmount: String?,
    val profitRate: String?,
    val backtestDays: Int,
    val startTime: Long,
    val endTime: Long?,
    val status: String,  // PENDING/RUNNING/COMPLETED/STOPPED/FAILED
    val progress: Int,
    val totalTrades: Int,
    val createdAt: Long,
    val executionStartedAt: Long?,
    val executionFinishedAt: Long?
)

data class BacktestConfigDto(
    val copyMode: String,
    val copyRatio: String,
    val fixedAmount: String?,
    val maxOrderSize: String,
    val minOrderSize: String,
    val maxDailyLoss: String,
    val maxDailyOrders: Int,
    val supportSell: Boolean,
    val keywordFilterMode: String?,
    val keywords: List<String>?,
    val maxPositionValue: String?,
    val minPrice: String?,
    val maxPrice: String?
)

data class BacktestStatisticsDto(
    val totalTrades: Int,
    val buyTrades: Int,
    val sellTrades: Int,
    val winTrades: Int,
    val lossTrades: Int,
    val winRate: String,
    val maxProfit: String,
    val maxLoss: String,
    val maxDrawdown: String,
    val avgHoldingTime: Long?
)

data class BacktestTradeDto(
    val id: Long,
    val tradeTime: Long,
    val marketId: String,
    val marketTitle: String?,
    val side: String,  // BUY/SELL/SETTLEMENT
    val outcome: String,
    val outcomeIndex: Int?,
    val quantity: String,
    val price: String,
    val amount: String,
    val fee: String,
    val profitLoss: String?,
    val balanceAfter: String,
    val leaderTradeId: String?
)

