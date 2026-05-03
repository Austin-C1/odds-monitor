package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.dto.PositionListResponse
import com.wrbug.polymarketbot.dto.PositionPushMessage
import com.wrbug.polymarketbot.dto.PositionPushMessageType
import com.wrbug.polymarketbot.dto.getPositionKey
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class PositionPushService(
    private val positionPollingService: PositionPollingService,
    private val accountService: AccountService
) {
    
    private val logger = LoggerFactory.getLogger(PositionPushService::class.java)
    private val clientCallbacks = ConcurrentHashMap<String, (PositionPushMessage) -> Unit>()
    private var lastCurrentPositions: Map<String, AccountPositionDto> = emptyMap()
    private var lastHistoryPositions: Map<String, AccountPositionDto> = emptyMap()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var subscriptionJob: Job? = null
    private val lock = Any()
    
    @PostConstruct
    fun init() {
        logger.info("PositionPushService 初始化，订阅仓位轮训事件")
        startSubscription()
    }
    
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = null
        }
        scope.cancel()
    }
    
    fun subscribe(sessionId: String, callback: (PositionPushMessage) -> Unit) {
        registerSession(sessionId, callback)
    }
    
    fun unsubscribe(sessionId: String) {
        unregisterSession(sessionId)
    }
    
    fun registerSession(sessionId: String, callback: (PositionPushMessage) -> Unit) {
        logger.info("注册仓位推送客户端会话: $sessionId")
        
        synchronized(lock) {
            clientCallbacks[sessionId] = callback
        }
    }
    
    fun unregisterSession(sessionId: String) {
        logger.info("注销仓位推送客户端会话: $sessionId")
        
        synchronized(lock) {
            clientCallbacks.remove(sessionId)
        }
    }
    
    suspend fun sendFullData(sessionId: String) {
        try {
            val result = accountService.getAllPositions()
            if (result.isSuccess) {
                val positions = result.getOrNull()
                if (positions != null) {
                    val message = PositionPushMessage(
                        type = PositionPushMessageType.FULL,
                        timestamp = System.currentTimeMillis(),
                        currentPositions = positions.currentPositions,
                        historyPositions = positions.historyPositions
                    )
                    lastCurrentPositions = positions.currentPositions.associateBy { it.getPositionKey() }
                    lastHistoryPositions = positions.historyPositions.associateBy { it.getPositionKey() }
                    clientCallbacks[sessionId]?.invoke(message)
                }
            } else {
                logger.warn("获取仓位数据失败，无法发送全量数据: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            logger.error("发送全量仓位数据失败: $sessionId, ${e.message}", e)
        }
    }
    
    private fun startSubscription() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = scope.launch(Dispatchers.IO) {
                try {
                    positionPollingService.subscribe { positions ->
                        try {
                            handlePositionUpdate(positions)
                        } catch (e: Exception) {
                            logger.error("处理仓位更新事件失败: ${e.message}", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("订阅仓位轮训事件失败: ${e.message}", e)
                }
            }
        }
    }
    
    private fun handlePositionUpdate(positions: PositionListResponse) {
        lastCurrentPositions = positions.currentPositions.associateBy { it.getPositionKey() }
        lastHistoryPositions = positions.historyPositions.associateBy { it.getPositionKey() }
        if (clientCallbacks.isNotEmpty()) {
            val message = PositionPushMessage(
                type = PositionPushMessageType.FULL,
                timestamp = System.currentTimeMillis(),
                currentPositions = positions.currentPositions,
                historyPositions = positions.historyPositions
            )
            scope.launch(Dispatchers.IO) {
                clientCallbacks.values.forEach { callback ->
                    try {
                        callback(message)
                    } catch (e: Exception) {
                        logger.error("推送全量数据失败: ${e.message}", e)
                    }
                }
            }
        }
    }
}
