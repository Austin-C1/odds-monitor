package com.wrbug.polymarketbot.controller.copytrading.statistics

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyTradingStatisticsService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.MessageSource
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/copy-trading/statistics")
class CopyTradingStatisticsController(
    private val statisticsService: CopyTradingStatisticsService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingStatisticsController::class.java)
    
    /**
     * POST /api/copy-trading/statistics/detail
     */
    @PostMapping("/detail")
    fun getStatisticsDetail(@RequestBody request: StatisticsDetailRequest): ResponseEntity<ApiResponse<CopyTradingStatisticsResponse>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            val result = runBlocking { statisticsService.getStatistics(request.copyTradingId) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取统计信息失败: copyTradingId=${request.copyTradingId}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取统计信息异常: copyTradingId=${request.copyTradingId}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * POST /api/copy-trading/statistics/global
     */
    @PostMapping("/batch-detail")
    fun getStatisticsBatchDetail(@RequestBody request: StatisticsBatchDetailRequest): ResponseEntity<ApiResponse<CopyTradingStatisticsBatchResponse>> {
        return try {
            val normalizedIds = request.copyTradingIds.filter { it > 0 }.distinct()
            val result = runBlocking { statisticsService.getStatisticsBatch(normalizedIds) }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("Failed to fetch statistics batch: ids=${normalizedIds.joinToString(",")}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
                }
            )
        } catch (e: Exception) {
            logger.error("Unexpected error while fetching statistics batch", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
        }
    }

    @PostMapping("/global")
    fun getGlobalStatistics(@RequestBody request: GlobalStatisticsRequest): ResponseEntity<ApiResponse<StatisticsResponse>> {
        return try {
            val result = runBlocking { 
                statisticsService.getGlobalStatistics(request.startTime, request.endTime) 
            }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取全局统计失败", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取全局统计异常", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * POST /api/copy-trading/statistics/leader
     */
    @PostMapping("/leader")
    fun getLeaderStatistics(@RequestBody request: LeaderStatisticsRequest): ResponseEntity<ApiResponse<StatisticsResponse>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID, messageSource = messageSource))
            }
            
            val result = runBlocking { 
                statisticsService.getLeaderStatistics(request.leaderId, request.startTime, request.endTime) 
            }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取 Leader 统计失败: leaderId=${request.leaderId}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取 Leader 统计异常: leaderId=${request.leaderId}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * POST /api/copy-trading/statistics/category
     */
    @PostMapping("/category")
    fun getCategoryStatistics(@RequestBody request: CategoryStatisticsRequest): ResponseEntity<ApiResponse<StatisticsResponse>> {
        return try {
            if (request.category.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "分类不能为空", messageSource))
            }
            
            if (request.category != "sports" && request.category != "crypto") {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "分类必须是 sports 或 crypto", messageSource))
            }
            
            val result = runBlocking { 
                statisticsService.getCategoryStatistics(request.category, request.startTime, request.endTime) 
            }
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("获取分类统计失败: category=${request.category}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("获取分类统计异常: category=${request.category}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_STATISTICS_FETCH_FAILED, e.message, messageSource))
        }
    }
}

@RestController
@RequestMapping("/api/copy-trading/orders")
class CopyOrderTrackingController(
    private val statisticsService: CopyTradingStatisticsService,
    private val messageSource: MessageSource
) {
    
    private val logger = LoggerFactory.getLogger(CopyOrderTrackingController::class.java)
    
    /**
     * POST /api/copy-trading/orders/tracking
     */
    @PostMapping("/tracking")
    fun getOrderList(@RequestBody request: OrderTrackingRequest): ResponseEntity<ApiResponse<OrderListResponse>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            if (request.type.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, "订单类型不能为空", messageSource))
            }
            
            val validTypes = listOf("buy", "sell", "matched")
            if (!validTypes.contains(request.type.lowercase())) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ORDER_TYPE_INVALID_FOR_TRACKING, messageSource = messageSource))
            }
            
            val result = statisticsService.getOrderList(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询订单列表失败: copyTradingId=${request.copyTradingId}, type=${request.type}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ORDER_TRACKING_LIST_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询订单列表异常: copyTradingId=${request.copyTradingId}, type=${request.type}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ORDER_TRACKING_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }
    
    /**
     * POST /api/copy-trading/orders/grouped-by-market
     */
    @PostMapping("/grouped-by-market")
    fun getOrderListGroupedByMarket(@RequestBody request: MarketGroupedOrdersRequest): ResponseEntity<ApiResponse<MarketGroupedOrdersResponse>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID, messageSource = messageSource))
            }
            
            if (request.type.isBlank()) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_EMPTY, "订单类型不能为空", messageSource))
            }
            
            val validTypes = listOf("buy", "sell")
            if (!validTypes.contains(request.type.lowercase())) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, "订单类型必须是 buy 或 sell", messageSource))
            }
            
            val result: Result<MarketGroupedOrdersResponse> = runBlocking {
                when (request.type.lowercase()) {
                    "buy" -> statisticsService.getBuyOrderListGroupedByMarket(request)
                    "sell" -> statisticsService.getSellOrderListGroupedByMarket(request)
                    else -> Result.failure(IllegalArgumentException("不支持的订单类型: ${request.type}"))
                }
            }
            
            result.fold(
                onSuccess = { response: MarketGroupedOrdersResponse ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e: Throwable ->
                    logger.error("查询按市场分组的订单列表失败: copyTradingId=${request.copyTradingId}, type=${request.type}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message, messageSource))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ORDER_TRACKING_LIST_FETCH_FAILED, e.message, messageSource))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询按市场分组的订单列表异常: copyTradingId=${request.copyTradingId}, type=${request.type}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_ORDER_TRACKING_LIST_FETCH_FAILED, e.message, messageSource))
        }
    }
}

