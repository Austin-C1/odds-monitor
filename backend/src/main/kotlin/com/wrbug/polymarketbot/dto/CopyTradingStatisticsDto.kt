package com.wrbug.polymarketbot.dto

data class CopyTradingStatisticsResponse(
    val copyTradingId: Long,
    val accountId: Long,
    val accountName: String?,
    val leaderId: Long,
    val leaderName: String?,
    val enabled: Boolean,
    val totalBuyQuantity: String,
    val totalBuyOrders: Long,
    val totalBuyAmount: String,
    val avgBuyPrice: String,
    val totalSellQuantity: String,
    val totalSellOrders: Long,
    val totalSellAmount: String,
    val currentPositionQuantity: String,
    val currentPositionValue: String,
    val totalRealizedPnl: String,
    val totalUnrealizedPnl: String,
    val totalPnl: String,
    val totalPnlPercent: String
)

data class BuyOrderInfo(
    val orderId: String,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String? = null,
    val marketSlug: String? = null,
    val eventSlug: String? = null,
    val marketCategory: String? = null,
    val side: String,
    val quantity: String,
    val price: String,
    val amount: String,
    val matchedQuantity: String,
    val remainingQuantity: String,
    val status: String,
    val createdAt: Long
)

data class SellOrderInfo(
    val orderId: String,
    val leaderTradeId: String,
    val marketId: String,
    val marketTitle: String? = null,
    val marketSlug: String? = null,
    val eventSlug: String? = null,
    val marketCategory: String? = null,
    val side: String,
    val quantity: String,
    val price: String,
    val amount: String,
    val realizedPnl: String,
    val createdAt: Long
)

data class MatchedOrderInfo(
    val sellOrderId: String,
    val buyOrderId: String,
    val marketId: String? = null,
    val marketTitle: String? = null,
    val marketSlug: String? = null,
    val eventSlug: String? = null,
    val marketCategory: String? = null,
    val matchedQuantity: String,
    val buyPrice: String,
    val sellPrice: String,
    val realizedPnl: String,
    val matchedAt: Long
)

data class OrderListResponse(
    val list: List<Any>,
    val total: Long,
    val page: Int,
    val limit: Int
)

data class OrderTrackingRequest(
    val copyTradingId: Long,
    val type: String,
    val page: Int? = 1,
    val limit: Int? = 20,
    val marketId: String? = null,
    val marketTitle: String? = null,
    val status: String? = null,
    val sellOrderId: String? = null,
    val buyOrderId: String? = null
)

data class MarketGroupedOrdersRequest(
    val copyTradingId: Long,
    val type: String,
    val page: Int? = 1,
    val limit: Int? = 20,
    val marketId: String? = null,
    val marketTitle: String? = null
)

data class MarketOrderStats(
    val count: Long,
    val totalAmount: String,
    val totalPnl: String?,
    val fullyMatched: Boolean,
    val fullyMatchedCount: Long,
    val partiallyMatchedCount: Long,
    val filledCount: Long
)

data class MarketOrderGroup(
    val marketId: String,
    val marketTitle: String?,
    val marketSlug: String?,
    val eventSlug: String? = null,
    val marketCategory: String?,
    val stats: MarketOrderStats,
    val orders: List<Any>
)

data class MarketGroupedOrdersResponse(
    val list: List<MarketOrderGroup>,
    val total: Long,
    val page: Int,
    val limit: Int
)

data class StatisticsDetailRequest(
    val copyTradingId: Long
)

data class StatisticsBatchDetailRequest(
    val copyTradingIds: List<Long>
)

data class CopyTradingStatisticsBatchResponse(
    val list: List<CopyTradingStatisticsResponse>
)

data class GlobalStatisticsRequest(
    val startTime: Long? = null,
    val endTime: Long? = null
)

data class LeaderStatisticsRequest(
    val leaderId: Long,
    val startTime: Long? = null,
    val endTime: Long? = null
)

data class CategoryStatisticsRequest(
    val category: String,
    val startTime: Long? = null,
    val endTime: Long? = null
)

data class StatisticsCurvePoint(
    val timestamp: Long,
    val cumulativePnl: String,
    val pointPnl: String
)

data class StatisticsResponse(
    val totalOrders: Long,
    val totalPnl: String,
    val winRate: String,
    val avgPnl: String,
    val maxProfit: String,
    val maxLoss: String,
    val curveData: List<StatisticsCurvePoint> = emptyList()
)
