package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class MarketPollingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val marketService: MarketService
) {
    
    private val logger = LoggerFactory.getLogger(MarketPollingService::class.java)
    
    @Value("\${market.polling.interval:30000}")
    private var pollingInterval: Long = 30000
    
    @Value("\${market.polling.batch.size:50}")
    private var batchSize: Int = 50
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null
    private val lock = Any()
    
    @PostConstruct
    fun init() {
        logger.info("MarketPollingService 初始化，启动市场信息轮询任务，轮询间隔: ${pollingInterval}ms (${pollingInterval / 1000 / 60}分钟)")
        startPolling()
    }
    
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = null
        }
        scope.cancel()
    }
    
    private fun startPolling() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = scope.launch(Dispatchers.IO) {
                try {
                    checkAndUpdateMissingMarkets()
                } catch (e: Exception) {
                    logger.error("初始检查市场信息失败: ${e.message}", e)
                }
                while (isActive) {
                    try {
                        delay(pollingInterval)
                        checkAndUpdateMissingMarkets()
                    } catch (e: Exception) {
                        logger.error("轮询市场信息失败: ${e.message}", e)
                    }
                }
            }
        }
    }
    
    private suspend fun checkAndUpdateMissingMarkets() {
        try {
            val allOrders = copyOrderTrackingRepository.findAll()
            val marketIds = allOrders.map { it.marketId }.distinct()
            
            if (marketIds.isEmpty()) {
                logger.debug("没有找到任何订单，跳过市场信息检查")
                return
            }
            val existingMarkets = marketService.marketRepository.findByMarketIdIn(marketIds)
            val existingMarketIds = existingMarkets.map { it.marketId }.toSet()
            val missingMarketIds = marketIds.filter { it !in existingMarketIds }
            val validMissingMarketIds = missingMarketIds.filter { 
                it.isNotBlank() && it.startsWith("0x") 
            }
            
            if (validMissingMarketIds.isEmpty()) {
                return
            }
            
            logger.info("发现 ${validMissingMarketIds.size} 个缺失的市场信息，开始批量更新...")
            val batches = validMissingMarketIds.chunked(batchSize)
            var successCount = 0
            var failCount = 0
            
            for ((index, batch) in batches.withIndex()) {
                try {
                    logger.debug("处理第 ${index + 1}/${batches.size} 批，包含 ${batch.size} 个市场: ${batch.take(3).joinToString(", ")}${if (batch.size > 3) "..." else ""}")
                    val markets = marketService.getMarkets(batch)
                    val batchSuccessCount = markets.size
                    val batchFailCount = batch.size - batchSuccessCount
                    
                    successCount += batchSuccessCount
                    failCount += batchFailCount
                    
                    if (batchFailCount > 0) {
                        logger.warn("第 ${index + 1} 批中有 ${batchFailCount} 个市场信息获取失败")
                    }
                    if (index < batches.size - 1) {
                        delay(1000)
                    }
                } catch (e: Exception) {
                    logger.error("批量获取市场信息失败: batch=${batch.take(5).joinToString(", ")}..., error=${e.message}", e)
                    failCount += batch.size
                }
            }
            
            logger.info("市场信息更新完成: 成功=${successCount}, 失败=${failCount}, 总计=${validMissingMarketIds.size}")
        } catch (e: Exception) {
            logger.error("检查并更新市场信息异常: ${e.message}", e)
        }
    }
    
    fun triggerCheck() {
        scope.launch(Dispatchers.IO) {
            try {
                checkAndUpdateMissingMarkets()
            } catch (e: Exception) {
                logger.error("手动触发检查市场信息失败: ${e.message}", e)
            }
        }
    }
}

