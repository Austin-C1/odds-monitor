package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.PositionListResponse
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Service
class PositionPollingService(
    private val accountService: AccountService
) {

    private val logger = LoggerFactory.getLogger(PositionPollingService::class.java)

    @Value("\${position.polling.interval:2000}")
    private var pollingInterval: Long = 2000
    private val subscribers = CopyOnWriteArrayList<(PositionListResponse) -> Unit>()
    @Volatile
    private var latestPositions: PositionListResponse? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var pollingJob: Job? = null
    private val eventDispatcherScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    @PostConstruct
    fun init() {
        logger.info("PositionPollingService 初始化，启动仓位轮训任务，轮训间隔: ${pollingInterval}ms")
        startPolling()
    }

    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = null
        }
        subscribers.clear()
        scope.cancel()
        eventDispatcherScope.cancel()
    }

    fun subscribe(callback: (PositionListResponse) -> Unit) {
        synchronized(lock) {
            subscribers.add(callback)
            latestPositions?.let { callback(it) }
        }
    }

    fun unsubscribe(callback: (PositionListResponse) -> Unit) {
        synchronized(lock) {
            subscribers.remove(callback)
        }
    }

    private fun startPolling() {
        synchronized(lock) {
            pollingJob?.cancel()
            pollingJob = scope.launch {
                while (isActive) {
                    try {
                        pollPositions()
                    } catch (e: Exception) {
                        logger.error("轮训仓位数据失败: ${e.message}", e)
                    }
                    delay(pollingInterval)
                }
            }
        }
    }

    private suspend fun pollPositions() {
        try {
            val result = accountService.getAllPositions()
            if (result.isSuccess) {
                val positions = result.getOrNull()
                if (positions != null) {
                    latestPositions = positions
                    eventDispatcherScope.launch {
                        try {
                            val currentSubscribers = synchronized(lock) {
                                subscribers.toList()
                            }

                            currentSubscribers.forEach { callback ->
                                try {
                                    callback(positions)
                                } catch (e: Exception) {
                                    logger.error("通知订阅者失败: ${e.message}", e)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("分发仓位数据事件失败: ${e.message}", e)
                        }
                    }
                }
            } else {
                logger.warn("获取仓位数据失败: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("轮训仓位数据异常: ${e.message}", e)
        }
    }
}
