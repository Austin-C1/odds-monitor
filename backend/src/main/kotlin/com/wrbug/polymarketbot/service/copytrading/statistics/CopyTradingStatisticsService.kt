package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.eq
import com.wrbug.polymarketbot.util.lte
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import com.wrbug.polymarketbot.service.accounts.AccountService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class CopyTradingStatisticsService(
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val accountRepository: AccountRepository,
    private val leaderRepository: LeaderRepository,
    private val marketService: com.wrbug.polymarketbot.service.common.MarketService,
    private val accountService: AccountService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingStatisticsService::class.java)
    
    suspend fun getStatistics(copyTradingId: Long): Result<CopyTradingStatisticsResponse> {
        return try {
            val copyTrading = copyTradingRepository.findById(copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: $copyTradingId"))
            val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
            val buyOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTradingId)
            val sellRecords = sellMatchRecordRepository.findByCopyTradingId(copyTradingId)
            val matchDetails = sellMatchDetailRepository.findByCopyTradingId(copyTradingId)
            val currentMetricsByCopyTradingId = loadCurrentMetrics(
                copyTradings = listOf(copyTrading),
                activeOrdersByCopyTradingId = mapOf(copyTradingId to buyOrders)
            )
            Result.success(
                buildStatisticsResponse(
                    copyTrading = copyTrading,
                    account = account,
                    leader = leader,
                    buyOrders = buyOrders,
                    sellRecords = sellRecords,
                    matchDetails = matchDetails,
                    currentMetrics = currentMetricsByCopyTradingId[copyTradingId] ?: CopyTradingCurrentMetrics()
                )
            )
        } catch (e: Exception) {
            logger.error("获取统计信息失败: copyTradingId=$copyTradingId", e)
            Result.failure(e)
        }
    }
    
    suspend fun getStatisticsBatch(copyTradingIds: List<Long>): Result<CopyTradingStatisticsBatchResponse> {
        return try {
            val normalizedIds = copyTradingIds
                .distinct()
                .filter { it > 0 }

            if (normalizedIds.isEmpty()) {
                return Result.success(CopyTradingStatisticsBatchResponse(emptyList()))
            }

            val copyTradings = copyTradingRepository.findAllById(normalizedIds)
            val copyTradingById = copyTradings.associateBy { it.id!! }

            val accountById = accountRepository.findAllById(copyTradings.map { it.accountId }.distinct())
                .associateBy { it.id!! }
            val leaderById = leaderRepository.findAllById(copyTradings.map { it.leaderId }.distinct())
                .associateBy { it.id!! }
            val buyOrdersByCopyTradingId = copyOrderTrackingRepository.findByCopyTradingIdIn(normalizedIds)
                .groupBy { it.copyTradingId }
            val sellRecords = sellMatchRecordRepository.findByCopyTradingIdIn(normalizedIds)
            val sellRecordsByCopyTradingId = sellRecords
                .groupBy { it.copyTradingId }
            val copyTradingIdByMatchRecordId = sellRecords
                .mapNotNull { record -> record.id?.let { it to record.copyTradingId } }
                .toMap()
            val matchDetailsByCopyTradingId = sellMatchDetailRepository.findByCopyTradingIdIn(normalizedIds)
                .groupBy { detail ->
                    copyTradingIdByMatchRecordId[detail.matchRecordId] ?: -1L
                }
            val currentMetricsByCopyTradingId = loadCurrentMetrics(
                copyTradings = copyTradings,
                activeOrdersByCopyTradingId = buyOrdersByCopyTradingId
            )

            val list = normalizedIds.mapNotNull { copyTradingId ->
                val copyTrading = copyTradingById[copyTradingId]
                if (copyTrading == null) {
                    logger.warn("Skipping copy trading statistics in batch request: copyTradingId=$copyTradingId, error=跟单关系不存在")
                    return@mapNotNull null
                }

                buildStatisticsResponse(
                    copyTrading = copyTrading,
                    account = accountById[copyTrading.accountId],
                    leader = leaderById[copyTrading.leaderId],
                    buyOrders = buyOrdersByCopyTradingId[copyTradingId].orEmpty(),
                    sellRecords = sellRecordsByCopyTradingId[copyTradingId].orEmpty(),
                    matchDetails = matchDetailsByCopyTradingId[copyTradingId].orEmpty(),
                    currentMetrics = currentMetricsByCopyTradingId[copyTradingId] ?: CopyTradingCurrentMetrics()
                )
            }

            Result.success(CopyTradingStatisticsBatchResponse(list))
        } catch (e: Exception) {
            logger.error("Failed to fetch copy trading statistics batch", e)
            Result.failure(e)
        }
    }

    fun getOrderList(request: OrderTrackingRequest): Result<OrderListResponse> {
        return try {
            copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: ${request.copyTradingId}"))
            val (list, total) = when (request.type.lowercase()) {
                "buy" -> getBuyOrderList(request)
                "sell" -> getSellOrderList(request)
                "matched" -> getMatchedOrderList(request)
                else -> return Result.failure(IllegalArgumentException("不支持的订单类型: ${request.type}"))
            }
            val response = OrderListResponse(
                list = list,
                total = total,
                page = request.page ?: 1,
                limit = request.limit ?: 20
            )
            
            Result.success(response)
        } catch (e: Exception) {
            logger.error("查询订单列表失败: ${request.copyTradingId}, type=${request.type}", e)
            Result.failure(e)
        }
    }
    
    private fun getBuyOrderList(request: OrderTrackingRequest): Pair<List<BuyOrderInfo>, Long> {
        var orders = copyOrderTrackingRepository.findByCopyTradingId(request.copyTradingId)
        val allMarketIds = orders.map { it.marketId }.distinct()
        val markets = marketService.getMarkets(allMarketIds)
        if (!request.marketId.isNullOrBlank()) {
            orders = orders.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
        }
        if (!request.marketTitle.isNullOrBlank()) {
            orders = orders.filter { order ->
                val market = markets[order.marketId]
                market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
            }
        }
        if (!request.status.isNullOrBlank()) {
            orders = orders.filter { it.status == request.status }
        }
        
        val total = orders.size.toLong()
        orders = orders.sortedByDescending { it.createdAt }
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, orders.size)
        val pagedOrders = if (start < orders.size) orders.subList(start, end) else emptyList()
        val list = pagedOrders.map { order ->
            val amount = order.quantity.toSafeBigDecimal().multi(order.price)
            val market = markets[order.marketId]
            BuyOrderInfo(
                orderId = order.buyOrderId,
                leaderTradeId = order.leaderBuyTradeId,
                marketId = order.marketId,
                marketTitle = market?.title,
                marketSlug = market?.slug,
                eventSlug = market?.eventSlug,
                marketCategory = market?.category,
                side = order.side,
                quantity = order.quantity.toString(),
                price = order.price.toString(),
                amount = amount.toString(),
                matchedQuantity = order.matchedQuantity.toString(),
                remainingQuantity = order.remainingQuantity.toString(),
                status = order.status,
                createdAt = order.createdAt
            )
        }
        
        return Pair(list, total)
    }
    
    private fun getSellOrderList(request: OrderTrackingRequest): Pair<List<SellOrderInfo>, Long> {
        var records = sellMatchRecordRepository.findByCopyTradingId(request.copyTradingId)
        val allMarketIds = records.map { it.marketId }.distinct()
        val markets = marketService.getMarkets(allMarketIds)
        if (!request.marketId.isNullOrBlank()) {
            records = records.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
        }
        if (!request.marketTitle.isNullOrBlank()) {
            records = records.filter { record ->
                val market = markets[record.marketId]
                market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
            }
        }
        
        val total = records.size.toLong()
        records = records.sortedByDescending { it.createdAt }
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, records.size)
        val pagedRecords = if (start < records.size) records.subList(start, end) else emptyList()
        val list = pagedRecords.map { record ->
            val amount = record.totalMatchedQuantity.toSafeBigDecimal().multi(record.sellPrice)
            val market = markets[record.marketId]
            SellOrderInfo(
                orderId = record.sellOrderId,
                leaderTradeId = record.leaderSellTradeId,
                marketId = record.marketId,
                marketTitle = market?.title,
                marketSlug = market?.slug,
                eventSlug = market?.eventSlug,
                marketCategory = market?.category,
                side = record.side,
                quantity = record.totalMatchedQuantity.toString(),
                price = record.sellPrice.toString(),
                amount = amount.toString(),
                realizedPnl = record.totalRealizedPnl.toString(),
                createdAt = record.createdAt
            )
        }
        
        return Pair(list, total)
    }
    
    private fun getMatchedOrderList(request: OrderTrackingRequest): Pair<List<MatchedOrderInfo>, Long> {
        val matchDetails = sellMatchDetailRepository.findByCopyTradingId(request.copyTradingId)
        val matchRecordIds = matchDetails.map { it.matchRecordId }.distinct()
        val matchRecords = sellMatchRecordRepository.findAllById(matchRecordIds)
        val matchRecordById = matchRecords.associateBy { it.id }
        val marketIds = matchRecords.map { it.marketId }.distinct()
        val markets = marketService.getMarkets(marketIds)
        var filtered = matchDetails
        if (!request.sellOrderId.isNullOrBlank()) {
            val sellRecord = sellMatchRecordRepository.findBySellOrderId(request.sellOrderId)
            if (sellRecord != null) {
                filtered = filtered.filter { it.matchRecordId == sellRecord.id }
            } else {
                filtered = emptyList()
            }
        }
        if (!request.buyOrderId.isNullOrBlank()) {
            filtered = filtered.filter { it.buyOrderId == request.buyOrderId }
        }
        if (!request.marketId.isNullOrBlank()) {
            filtered = filtered.filter { detail ->
                val matchRecord = matchRecordById[detail.matchRecordId]
                matchRecord?.marketId?.contains(request.marketId!!, ignoreCase = true) == true
            }
        }
        if (!request.marketTitle.isNullOrBlank()) {
            filtered = filtered.filter { detail ->
                val matchRecord = matchRecordById[detail.matchRecordId]
                val market = matchRecord?.let { markets[it.marketId] }
                market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
            }
        }
        
        val total = filtered.size.toLong()
        filtered = filtered.sortedByDescending { it.createdAt }
        val page = (request.page ?: 1) - 1
        val limit = request.limit ?: 20
        val start = page * limit
        val end = minOf(start + limit, filtered.size)
        val pagedDetails = if (start < filtered.size) filtered.subList(start, end) else emptyList()
        val list = pagedDetails.map { detail ->
            val matchRecord = matchRecordById[detail.matchRecordId]
            val market = matchRecord?.let { markets[it.marketId] }
            MatchedOrderInfo(
                sellOrderId = matchRecord?.sellOrderId ?: "",
                buyOrderId = detail.buyOrderId,
                marketId = matchRecord?.marketId,
                marketTitle = market?.title,
                marketSlug = market?.slug,
                eventSlug = market?.eventSlug,
                marketCategory = market?.category,
                matchedQuantity = detail.matchedQuantity.toString(),
                buyPrice = detail.buyPrice.toString(),
                sellPrice = detail.sellPrice.toString(),
                realizedPnl = detail.realizedPnl.toString(),
                matchedAt = detail.createdAt
            )
        }
        
        return Pair(list, total)
    }

    private fun buildStatisticsResponse(
        copyTrading: CopyTrading,
        account: Account?,
        leader: Leader?,
        buyOrders: List<CopyOrderTracking>,
        sellRecords: List<SellMatchRecord>,
        matchDetails: List<SellMatchDetail>,
        currentMetrics: CopyTradingCurrentMetrics
    ): CopyTradingStatisticsResponse {
        val statistics = calculateStatistics(buyOrders, sellRecords, matchDetails)
        val totalPnl = statistics.totalRealizedPnl.toSafeBigDecimal().add(currentMetrics.unrealizedPnl).toPlainString()
        return CopyTradingStatisticsResponse(
            copyTradingId = copyTrading.id!!,
            accountId = copyTrading.accountId,
            accountName = account?.accountName,
            leaderId = copyTrading.leaderId,
            leaderName = leader?.leaderName,
            enabled = copyTrading.enabled,
            totalBuyQuantity = statistics.totalBuyQuantity,
            totalBuyOrders = statistics.totalBuyOrders,
            totalBuyAmount = statistics.totalBuyAmount,
            avgBuyPrice = statistics.avgBuyPrice,
            totalSellQuantity = statistics.totalSellQuantity,
            totalSellOrders = statistics.totalSellOrders,
            totalSellAmount = statistics.totalSellAmount,
            currentPositionQuantity = statistics.currentPositionQuantity,
            currentPositionValue = currentMetrics.currentPositionValue.toPlainString(),
            totalRealizedPnl = statistics.totalRealizedPnl,
            totalUnrealizedPnl = currentMetrics.unrealizedPnl.toPlainString(),
            totalPnl = totalPnl,
            totalPnlPercent = calculatePnlPercent(statistics.totalBuyAmount, totalPnl)
        )
    }
    
    private fun calculateStatistics(
        buyOrders: List<CopyOrderTracking>,
        sellRecords: List<SellMatchRecord>,
        matchDetails: List<SellMatchDetail>
    ): StatisticsData {
        val totalBuyQuantity = buyOrders.sumOf { it.quantity.toSafeBigDecimal() }
        val totalBuyAmount = buyOrders.sumOf { it.quantity.toSafeBigDecimal().multi(it.price) }
        val totalBuyOrders = buyOrders.size.toLong()
        val avgBuyPrice = if (totalBuyQuantity.gt(BigDecimal.ZERO)) {
            totalBuyAmount.div(totalBuyQuantity)
        } else {
            BigDecimal.ZERO
        }
        val totalSellQuantity = sellRecords.sumOf { it.totalMatchedQuantity.toSafeBigDecimal() }
        val totalSellAmount = matchDetails.sumOf { it.matchedQuantity.toSafeBigDecimal().multi(it.sellPrice) }
        val totalSellOrders = sellRecords.size.toLong()
        val currentPositionQuantity = buyOrders.sumOf { it.remainingQuantity.toSafeBigDecimal() }
        val totalRealizedPnl = matchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }
        
        return StatisticsData(
            totalBuyQuantity = totalBuyQuantity.toString(),
            totalBuyOrders = totalBuyOrders,
            totalBuyAmount = totalBuyAmount.toString(),
            avgBuyPrice = avgBuyPrice.toString(),
            totalSellQuantity = totalSellQuantity.toString(),
            totalSellOrders = totalSellOrders,
            totalSellAmount = totalSellAmount.toString(),
            currentPositionQuantity = currentPositionQuantity.toString(),
            totalRealizedPnl = totalRealizedPnl.toString()
        )
    }
    
    private fun calculatePnlPercent(
        totalBuyAmount: String,
        totalPnl: String
    ): String {
        val buyAmount = totalBuyAmount.toSafeBigDecimal()
        if (buyAmount.lte(BigDecimal.ZERO)) return "0"
        
        val percent = totalPnl.toSafeBigDecimal().div(buyAmount).multi(100)
        
        return percent.setScale(2, RoundingMode.HALF_UP).toString()
    }

    private suspend fun loadCurrentMetrics(
        copyTradings: List<CopyTrading>,
        activeOrdersByCopyTradingId: Map<Long, List<CopyOrderTracking>>
    ): Map<Long, CopyTradingCurrentMetrics> {
        if (copyTradings.isEmpty()) {
            return emptyMap()
        }

        val currentPositionsByAccount = coroutineScope {
            copyTradings.map { it.accountId }.distinct().map { accountId ->
                async {
                    accountId to accountService.getCurrentPositionsForAccount(accountId).getOrNull().orEmpty()
                }
            }.awaitAll().toMap()
        }

        return resolveCopyTradingCurrentMetrics(
            copyTradings = copyTradings,
            activeOrdersByCopyTradingId = activeOrdersByCopyTradingId,
            currentPositionsByAccount = currentPositionsByAccount
        )
    }
    
    suspend fun getGlobalStatistics(startTime: Long? = null, endTime: Long? = null): Result<StatisticsResponse> {
        return try {
            val allCopyTradings = copyTradingRepository.findAll()
            val statistics = calculateAggregateStatistics(allCopyTradings.map { it.id!! }, startTime, endTime)
            
            Result.success(statistics)
        } catch (e: Exception) {
            logger.error("获取全局统计失败", e)
            Result.failure(e)
        }
    }
    
    suspend fun getLeaderStatistics(leaderId: Long, startTime: Long? = null, endTime: Long? = null): Result<StatisticsResponse> {
        return try {
            val copyTradings = copyTradingRepository.findByLeaderId(leaderId)
            
            if (copyTradings.isEmpty()) {
                return Result.failure(IllegalArgumentException("Leader $leaderId 没有跟单关系"))
            }
            val statistics = calculateAggregateStatistics(copyTradings.map { it.id!! }, startTime, endTime)
            
            Result.success(statistics)
        } catch (e: Exception) {
            logger.error("获取 Leader 统计失败: leaderId=$leaderId", e)
            Result.failure(e)
        }
    }
    
    suspend fun getCategoryStatistics(category: String, startTime: Long? = null, endTime: Long? = null): Result<StatisticsResponse> {
        return try {
            if (category != "sports" && category != "crypto") {
                return Result.failure(IllegalArgumentException("分类必须是 sports 或 crypto"))
            }
            val leaders = leaderRepository.findAll().filter { it.category == category }
            
            if (leaders.isEmpty()) {
                return Result.failure(IllegalArgumentException("分类 $category 没有 Leader"))
            }
            val leaderIds = leaders.mapNotNull { it.id }
            val copyTradings = copyTradingRepository.findAll().filter { it.leaderId in leaderIds }
            
            if (copyTradings.isEmpty()) {
                return Result.failure(IllegalArgumentException("分类 $category 没有跟单关系"))
            }
            val statistics = calculateAggregateStatistics(copyTradings.map { it.id!! }, startTime, endTime)
            
            Result.success(statistics)
        } catch (e: Exception) {
            logger.error("获取分类统计失败: category=$category", e)
            Result.failure(e)
        }
    }
    
    private suspend fun calculateAggregateStatistics(
        copyTradingIds: List<Long>,
        startTime: Long?,
        endTime: Long?
    ): StatisticsResponse {
        val allBuyOrders = copyTradingIds.flatMap { copyOrderTrackingRepository.findByCopyTradingId(it) }
            .filter { order ->
                when {
                    startTime != null && endTime != null -> order.createdAt >= startTime && order.createdAt <= endTime
                    startTime != null -> order.createdAt >= startTime
                    endTime != null -> order.createdAt <= endTime
                    else -> true
                }
            }
        val allMatchDetails = copyTradingIds.flatMap { sellMatchDetailRepository.findByCopyTradingId(it) }
            .filter { detail ->
                when {
                    startTime != null && endTime != null -> detail.createdAt >= startTime && detail.createdAt <= endTime
                    startTime != null -> detail.createdAt >= startTime
                    endTime != null -> detail.createdAt <= endTime
                    else -> true
                }
            }
        val totalOrders = allBuyOrders.size.toLong()
        val totalPnl = allMatchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() }
        val curveData = buildCurveData(allMatchDetails)
        val profitableOrders = allBuyOrders.count { buyOrder ->
            val orderPnl = allMatchDetails
                .filter { it.buyOrderId == buyOrder.buyOrderId }
                .sumOf { it.realizedPnl.toSafeBigDecimal() }
            orderPnl.gt(BigDecimal.ZERO)
        }
        val winRate = if (totalOrders > 0) {
            (BigDecimal(profitableOrders).divide(BigDecimal(totalOrders), 4, RoundingMode.HALF_UP) * BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        val avgPnl = if (totalOrders > 0) {
            totalPnl.divide(BigDecimal(totalOrders), 8, RoundingMode.HALF_UP)
        } else {
            BigDecimal.ZERO
        }
        var maxProfit = BigDecimal.ZERO
        var maxLoss = BigDecimal.ZERO
        
        allBuyOrders.forEach { buyOrder ->
            val orderPnl = allMatchDetails
                .filter { it.buyOrderId == buyOrder.buyOrderId }
                .sumOf { it.realizedPnl.toSafeBigDecimal() }
            
            if (orderPnl.gt(maxProfit)) {
                maxProfit = orderPnl
            }
            if (orderPnl < maxLoss) {
                maxLoss = orderPnl
            }
        }
        
        return StatisticsResponse(
            totalOrders = totalOrders,
            totalPnl = totalPnl.toString(),
            winRate = winRate.toString(),
            avgPnl = avgPnl.toString(),
            maxProfit = maxProfit.toString(),
            maxLoss = maxLoss.toString(),
            curveData = curveData
        )
    }

    private fun buildCurveData(matchDetails: List<SellMatchDetail>): List<StatisticsCurvePoint> {
        if (matchDetails.isEmpty()) {
            return emptyList()
        }

        var cumulativePnl = BigDecimal.ZERO
        return matchDetails
            .sortedBy { it.createdAt }
            .map { detail ->
                cumulativePnl = cumulativePnl.add(detail.realizedPnl.toSafeBigDecimal())
                StatisticsCurvePoint(
                    timestamp = detail.createdAt,
                    cumulativePnl = cumulativePnl.toPlainString(),
                    pointPnl = detail.realizedPnl.toPlainString()
                )
            }
    }
    
    private data class StatisticsData(
        val totalBuyQuantity: String,
        val totalBuyOrders: Long,
        val totalBuyAmount: String,
        val avgBuyPrice: String,
        val totalSellQuantity: String,
        val totalSellOrders: Long,
        val totalSellAmount: String,
        val currentPositionQuantity: String,
        val totalRealizedPnl: String
    )
    
    fun getBuyOrderListGroupedByMarket(request: MarketGroupedOrdersRequest): Result<MarketGroupedOrdersResponse> {
        return try {
            copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: ${request.copyTradingId}"))
            var orders = copyOrderTrackingRepository.findByCopyTradingId(request.copyTradingId)
            val allMarketIds = orders.map { it.marketId }.distinct()
            val markets = marketService.getMarkets(allMarketIds)
            if (!request.marketId.isNullOrBlank()) {
                orders = orders.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
            }
            if (!request.marketTitle.isNullOrBlank()) {
                orders = orders.filter { order ->
                    val market = markets[order.marketId]
                    market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
                }
            }
            val groups = mutableMapOf<String, MutableList<CopyOrderTracking>>()
            orders.forEach { order ->
                val marketId = order.marketId
                if (!groups.containsKey(marketId)) {
                    groups[marketId] = mutableListOf()
                }
                groups[marketId]!!.add(order)
            }
            val marketIds = groups.keys.toList()

            val list = marketIds.map { marketId ->
                val marketOrders = groups[marketId] ?: mutableListOf()
                val count = marketOrders.size.toLong()
                val totalAmount = marketOrders.sumOf { order ->
                    order.quantity.toSafeBigDecimal().multi(order.price)
                }
                val fullyMatchedCount = marketOrders.count { it.status == "fully_matched" }
                val partiallyMatchedCount = marketOrders.count { it.status == "partially_matched" }
                val filledCount = marketOrders.count { it.status == "filled" }
                val fullyMatched = fullyMatchedCount == marketOrders.size

                val stats = MarketOrderStats(
                    count = count,
                    totalAmount = totalAmount.toString(),
                    totalPnl = null,
                    fullyMatched = fullyMatched,
                    fullyMatchedCount = fullyMatchedCount.toLong(),
                    partiallyMatchedCount = partiallyMatchedCount.toLong(),
                    filledCount = filledCount.toLong()
                )
                marketOrders.sortByDescending { it.createdAt }
                val orderDtos = marketOrders.map { order ->
                    val amount = order.quantity.toSafeBigDecimal().multi(order.price)
                    val market = markets[order.marketId]
                    BuyOrderInfo(
                        orderId = order.buyOrderId,
                        leaderTradeId = order.leaderBuyTradeId,
                        marketId = order.marketId,
                        marketTitle = market?.title,
                        marketSlug = market?.slug,
                        eventSlug = market?.eventSlug,
                        marketCategory = market?.category,
                        side = order.side,
                        quantity = order.quantity.toString(),
                        price = order.price.toString(),
                        amount = amount.toString(),
                        matchedQuantity = order.matchedQuantity.toString(),
                        remainingQuantity = order.remainingQuantity.toString(),
                        status = order.status,
                        createdAt = order.createdAt
                    )
                }

                MarketOrderGroup(
                    marketId = marketId,
                    marketTitle = markets[marketId]?.title,
                    marketSlug = markets[marketId]?.slug,
                    eventSlug = markets[marketId]?.eventSlug,
                    marketCategory = markets[marketId]?.category,
                    stats = stats,
                    orders = orderDtos as List<Any>
                )
            }.sortedByDescending { group ->
                group.orders.mapNotNull { order ->
                    when (order) {
                        is BuyOrderInfo -> order.createdAt
                        else -> null
                    }
                }.maxOrNull() ?: 0L
            }
            val page = (request.page ?: 1)
            val limit = request.limit ?: 20
            val total = list.size.toLong()

            val start = (page - 1) * limit
            val end = minOf(start + limit, list.size)
            val pagedList = if (start < list.size) list.subList(start, end) else emptyList()

            val response = MarketGroupedOrdersResponse(
                list = pagedList,
                total = total,
                page = page,
                limit = limit
            )

            Result.success(response)
        } catch (e: Exception) {
            logger.error("获取按市场分组的卖出订单列表失败: copyTradingId=${request.copyTradingId}", e)
            Result.failure(e)
        }
    }
    
    fun getSellOrderListGroupedByMarket(request: MarketGroupedOrdersRequest): Result<MarketGroupedOrdersResponse> {
        return try {
            copyTradingRepository.findById(request.copyTradingId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("跟单关系不存在: ${request.copyTradingId}"))
            var sellRecords = sellMatchRecordRepository.findByCopyTradingId(request.copyTradingId)
            val allMarketIds = sellRecords.map { it.marketId }.distinct()
            val markets = marketService.getMarkets(allMarketIds)
            if (!request.marketId.isNullOrBlank()) {
                sellRecords = sellRecords.filter { it.marketId.contains(request.marketId!!, ignoreCase = true) }
            }
            if (!request.marketTitle.isNullOrBlank()) {
                sellRecords = sellRecords.filter { record ->
                    val market = markets[record.marketId]
                    market?.title?.contains(request.marketTitle!!, ignoreCase = true) == true
                }
            }
            val groups = mutableMapOf<String, MutableList<SellMatchRecord>>()
            sellRecords.forEach { record ->
                val marketId = record.marketId
                if (!groups.containsKey(marketId)) {
                    groups[marketId] = mutableListOf()
                }
                groups[marketId]!!.add(record)
            }
            val marketIds = groups.keys.toList()
            
            val list = marketIds.map { marketId ->
                val marketRecords = groups[marketId] ?: mutableListOf()
                val count = marketRecords.size.toLong()
                val totalAmount = marketRecords.sumOf { record ->
                    record.totalMatchedQuantity.toSafeBigDecimal().multi(record.sellPrice)
                }
                val totalPnl = marketRecords.sumOf { it.totalRealizedPnl.toSafeBigDecimal() }
                
                val stats = MarketOrderStats(
                    count = count,
                    totalAmount = totalAmount.toString(),
                    totalPnl = totalPnl.toString(),
                    fullyMatched = true,
                    fullyMatchedCount = count,
                    partiallyMatchedCount = 0L,
                    filledCount = 0L
                )
                marketRecords.sortByDescending { it.createdAt }
                val orderDtos = marketRecords.map { record ->
                    val amount = record.totalMatchedQuantity.toSafeBigDecimal().multi(record.sellPrice)
                    val market = markets[record.marketId]
                    SellOrderInfo(
                        orderId = record.sellOrderId,
                        leaderTradeId = record.leaderSellTradeId,
                        marketId = record.marketId,
                        marketTitle = market?.title,
                        marketSlug = market?.slug,
                        eventSlug = market?.eventSlug,
                        marketCategory = market?.category,
                        side = record.side,
                        quantity = record.totalMatchedQuantity.toString(),
                        price = record.sellPrice.toString(),
                        amount = amount.toString(),
                        realizedPnl = record.totalRealizedPnl.toString(),
                        createdAt = record.createdAt
                    )
                }
                
                MarketOrderGroup(
                    marketId = marketId,
                    marketTitle = markets[marketId]?.title,
                    marketSlug = markets[marketId]?.slug,
                    eventSlug = markets[marketId]?.eventSlug,
                    marketCategory = markets[marketId]?.category,
                    stats = stats,
                    orders = orderDtos as List<Any>
                )
            }.sortedByDescending { group ->
                group.orders.mapNotNull { order ->
                    when (order) {
                        is SellOrderInfo -> order.createdAt
                        else -> null
                    }
                }.maxOrNull() ?: 0L
            }
            val page = (request.page ?: 1)
            val limit = request.limit ?: 20
            val total = list.size.toLong()
            
            val start = (page - 1) * limit
            val end = minOf(start + limit, list.size)
            val pagedList = if (start < list.size) list.subList(start, end) else emptyList()
            
            val response = MarketGroupedOrdersResponse(
                list = pagedList,
                total = total,
                page = page,
                limit = limit
            )
            
            Result.success(response)
        } catch (e: Exception) {
            logger.error("获取按市场分组的卖出订单列表失败: copyTradingId=${request.copyTradingId}", e)
            Result.failure(e)
        }
    }
    
}
