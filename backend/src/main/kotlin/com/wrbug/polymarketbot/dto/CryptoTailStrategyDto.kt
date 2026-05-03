package com.wrbug.polymarketbot.dto

data class CryptoTailStrategyCreateRequest(
    val accountId: Long = 0L,
    val name: String? = null,
    val marketSlugPrefix: String = "",
    val intervalSeconds: Int = 300,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String? = null,
    val amountMode: String = "RATIO",
    val amountValue: String = "0",
    val spreadMode: String = "NONE",
    val spreadValue: String? = null,
    val spreadDirection: String = "MIN",
    val enabled: Boolean = true
)

data class CryptoTailStrategyUpdateRequest(
    val strategyId: Long = 0L,
    val name: String? = null,
    val windowStartSeconds: Int? = null,
    val windowEndSeconds: Int? = null,
    val minPrice: String? = null,
    val maxPrice: String? = null,
    val amountMode: String? = null,
    val amountValue: String? = null,
    val spreadMode: String? = null,
    val spreadValue: String? = null,
    val spreadDirection: String? = null,
    val enabled: Boolean? = null
)

data class CryptoTailStrategyListRequest(
    val accountId: Long? = null,
    val enabled: Boolean? = null
)

data class CryptoTailStrategyDto(
    val id: Long = 0L,
    val accountId: Long = 0L,
    val name: String? = null,
    val marketSlugPrefix: String = "",
    val marketTitle: String? = null,
    val intervalSeconds: Int = 0,
    val windowStartSeconds: Int = 0,
    val windowEndSeconds: Int = 0,
    val minPrice: String = "0",
    val maxPrice: String = "1",
    val amountMode: String = "RATIO",
    val amountValue: String = "0",
    val spreadMode: String = "NONE",
    val spreadValue: String? = null,
    val spreadDirection: String = "MIN",
    val enabled: Boolean = true,
    val lastTriggerAt: Long? = null,
    val totalRealizedPnl: String? = null,
    val settledCount: Long = 0L,
    val winCount: Long = 0L,
    val winRate: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

data class CryptoTailStrategyListResponse(
    val list: List<CryptoTailStrategyDto> = emptyList()
)

data class CryptoTailStrategyDeleteRequest(
    val strategyId: Long = 0L
)

data class CryptoTailStrategyTriggerListRequest(
    val strategyId: Long = 0L,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: String? = null,
    val startDate: Long? = null,
    val endDate: Long? = null
)

data class CryptoTailStrategyTriggerDto(
    val id: Long = 0L,
    val strategyId: Long = 0L,
    val periodStartUnix: Long = 0L,
    val marketTitle: String? = null,
    val outcomeIndex: Int = 0,
    val triggerPrice: String = "0",
    val amountUsdc: String = "0",
    val orderId: String? = null,
    val status: String = "success",
    val failReason: String? = null,
    val resolved: Boolean = false,
    val realizedPnl: String? = null,
    val winnerOutcomeIndex: Int? = null,
    val settledAt: Long? = null,
    val createdAt: Long = 0L
)

data class CryptoTailStrategyTriggerListResponse(
    val list: List<CryptoTailStrategyTriggerDto> = emptyList(),
    val total: Long = 0L
)

data class CryptoTailAutoMinSpreadResponse(
    val minSpreadUp: String = "0",
    val minSpreadDown: String = "0"
)

data class CryptoTailMarketOptionDto(
    val slug: String = "",
    val title: String = "",
    val intervalSeconds: Int = 0,
    val periodStartUnix: Long = 0L,
    val endDate: String? = null
)

data class CryptoTailPnlCurveRequest(
    val strategyId: Long = 0L,
    val startDate: Long? = null,
    val endDate: Long? = null
)

data class CryptoTailPnlCurvePoint(
    val timestamp: Long = 0L,
    val cumulativePnl: String = "0",
    val pointPnl: String = "0",
    val settledCount: Long = 0L
)

data class CryptoTailPnlCurveResponse(
    val strategyId: Long = 0L,
    val strategyName: String = "",
    val totalRealizedPnl: String = "0",
    val settledCount: Long = 0L,
    val winCount: Long = 0L,
    val winRate: String? = null,
    val maxDrawdown: String? = null,
    val curveData: List<CryptoTailPnlCurvePoint> = emptyList()
)
