package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.UserActivityResponse
import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

data class LeaderTradesBatchResult(
    val trades: List<TradeData>,
    val nextCursorSeconds: Long?
)

@Service
class BacktestDataService(
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory
) {
    private val logger = LoggerFactory.getLogger(BacktestDataService::class.java)

    /**
     *
     * @param leaderId Leader ID
     */
    suspend fun getLeaderHistoricalTradesBatch(
        leaderId: Long,
        startTime: Long,
        endTime: Long,
        cursorStartSeconds: Long,
        limit: Int
    ): LeaderTradesBatchResult {
        logger.info("获取 Leader 历史交易批次: leaderId=$leaderId, cursorStart=$cursorStartSeconds, limit=$limit")

        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: throw IllegalArgumentException("Leader 不存在: $leaderId")

        val dataApi = retrofitFactory.createDataApi()
        val endSeconds = endTime / 1000
        val maxRetries = 5
        val retryDelay = 1000L

        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val response = dataApi.getUserActivity(
                    user = leader.leaderAddress,
                    type = listOf("TRADE"),
                    start = cursorStartSeconds,
                    end = endSeconds,
                    limit = limit,
                    offset = null,
                    sortBy = "TIMESTAMP",
                    sortDirection = "ASC"
                )

                if (!response.isSuccessful || response.body() == null) {
                    throw Exception("从 Data API 获取用户活动失败: code=${response.code()}, message=${response.message()}")
                }

                val activities = response.body()!!
                logger.info("本批获取 ${activities.size} 条活动（第 $attempt 次尝试）")

                val trades = activities.mapNotNull { activity ->
                    try {
                        if (activity.type != "TRADE") return@mapNotNull null
                        if (activity.side == null || activity.price == null || activity.size == null || activity.usdcSize == null) {
                            logger.warn("活动数据缺少必要字段，跳过: activity=$activity")
                            return@mapNotNull null
                        }
                        val tradeTimestamp = activity.timestamp * 1000
                        if (tradeTimestamp < startTime || tradeTimestamp > endTime) {
                            logger.debug("交易时间超出范围，跳过: timestamp=$tradeTimestamp")
                            return@mapNotNull null
                        }
                        TradeData(
                            tradeId = activity.transactionHash ?: "${activity.timestamp}_${activity.conditionId}_${activity.side}",
                            marketId = activity.conditionId,
                            marketTitle = activity.title,
                            marketSlug = activity.slug,
                            side = activity.side.uppercase(),
                            outcome = activity.outcome ?: activity.outcomeIndex?.toString() ?: "",
                            outcomeIndex = activity.outcomeIndex,
                            price = activity.price.toSafeBigDecimal(),
                            size = activity.size.toSafeBigDecimal(),
                            amount = activity.usdcSize.toSafeBigDecimal(),
                            timestamp = tradeTimestamp
                        )
                    } catch (e: Exception) {
                        logger.warn("转换活动数据失败: activity=$activity, error=${e.message}", e)
                        null
                    }
                }
                val nextCursorSeconds: Long? = if (trades.size < limit) {
                    null
                } else {
                    val maxTs = trades.maxOf { it.timestamp }
                    maxTs / 1000
                }
                return LeaderTradesBatchResult(trades = trades, nextCursorSeconds = nextCursorSeconds)
            } catch (e: Exception) {
                lastException = e
                logger.warn("第 $attempt/$maxRetries 次获取批次失败: ${e.message}")
                if (attempt < maxRetries) {
                    logger.info("等待 $retryDelay 毫秒后重试...")
                    delay(retryDelay)
                }
            }
        }
        val errorMsg = "重试 $maxRetries 次后仍然失败，cursorStart=$cursorStartSeconds"
        logger.error(errorMsg, lastException)
        throw Exception(errorMsg, lastException)
    }
}
