package com.wrbug.polymarketbot.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.WebSocketMessage as WsMessage
import com.wrbug.polymarketbot.dto.WebSocketMessageType
import com.wrbug.polymarketbot.service.common.WebSocketSubscriptionService
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.*
import java.util.concurrent.ConcurrentHashMap

@Component
class UnifiedWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val subscriptionService: WebSocketSubscriptionService
) : WebSocketHandler {
    
    private val logger = LoggerFactory.getLogger(UnifiedWebSocketHandler::class.java)
    
    @Value("\${websocket.heartbeat-timeout:60000}")
    private var heartbeatTimeout: Long = 60000
    private val clientSessions = ConcurrentHashMap<String, WebSocketSession>()
    private val lastActivityTime = ConcurrentHashMap<String, Long>()
    private val sessionLocks = ConcurrentHashMap<String, Any>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cleanupJob: Job? = null
    
    @PostConstruct
    fun init() {
        startCleanupTask()
    }
    
    @PreDestroy
    fun destroy() {
        cleanupJob?.cancel()
        scope.cancel()
    }
    
    override fun afterConnectionEstablished(session: WebSocketSession) {
        clientSessions[session.id] = session
        lastActivityTime[session.id] = System.currentTimeMillis()
        sessionLocks[session.id] = Any()
        subscriptionService.registerSession(session.id) { wsMessage ->
            sendMessageToClient(session.id, wsMessage)
        }
    }
    
    override fun handleMessage(session: WebSocketSession, message: WebSocketMessage<*>) {
        val payload = message.payload.toString()
        if (payload == "PING" || payload == "ping") {
            lastActivityTime[session.id] = System.currentTimeMillis()
            val lock = sessionLocks[session.id]
            if (lock != null && session.isOpen) {
                synchronized(lock) {
                    try {
                        if (session.isOpen) {
                            session.sendMessage(TextMessage("PONG"))
                        }
                    } catch (e: IllegalStateException) {
                        logger.warn("发送心跳响应时 WebSocket 状态异常: ${session.id}, ${e.message}")
                    } catch (e: Exception) {
                        logger.error("发送心跳响应失败: ${session.id}, ${e.message}", e)
                    }
                }
            }
            return
        }
        lastActivityTime[session.id] = System.currentTimeMillis()
        try {
            val wsMessage: WsMessage = objectMapper.readValue(payload, WsMessage::class.java)
            handleWebSocketMessage(session.id, wsMessage)
        } catch (e: Exception) {
            logger.error("解析 WebSocket 消息失败: ${session.id}, ${e.message}", e)
        }
    }
    
    private fun handleWebSocketMessage(sessionId: String, message: WsMessage) {
        val messageType = WebSocketMessageType.fromValue(message.type)
        when (messageType) {
            WebSocketMessageType.SUB -> {
                val channel = message.channel
                if (channel != null) {
                    val payload = message.payload as? Map<*, *>
                    subscriptionService.subscribe(sessionId, channel, payload)
                } else {
                    logger.warn("订阅消息缺少 channel 字段: $sessionId")
                }
            }
            WebSocketMessageType.UNSUB -> {
                val channel = message.channel
                if (channel != null) {
                    subscriptionService.unsubscribe(sessionId, channel)
                } else {
                    logger.warn("取消订阅消息缺少 channel 字段: $sessionId")
                }
            }
            null -> {
                logger.warn("未知的消息类型: ${message.type}")
            }
            else -> {
                logger.warn("不支持的消息类型: ${messageType}")
            }
        }
    }
    
    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        logger.error("WebSocket 传输错误: ${session.id}, ${exception.message}", exception)
        cleanup(session.id)
    }
    
    override fun afterConnectionClosed(session: WebSocketSession, closeStatus: CloseStatus) {
        cleanup(session.id)
    }
    
    override fun supportsPartialMessages(): Boolean = false
    
    private fun sendMessageToClient(sessionId: String, message: WsMessage) {
        val session = clientSessions[sessionId]
        if (session == null || !session.isOpen) {
            logger.warn("客户端会话不存在或已关闭: $sessionId")
            cleanup(sessionId)
            return
        }
        val lock = sessionLocks[sessionId] ?: return
        synchronized(lock) {
            val currentSession = clientSessions[sessionId]
            if (currentSession == null || !currentSession.isOpen) {
                logger.warn("客户端会话在发送消息前已关闭: $sessionId")
                cleanup(sessionId)
                return
            }
            
            try {
                val json = objectMapper.writeValueAsString(message)
                currentSession.sendMessage(TextMessage(json))
                lastActivityTime[sessionId] = System.currentTimeMillis()
            } catch (e: IllegalStateException) {
                logger.warn("发送消息时 WebSocket 状态异常: $sessionId, ${e.message}")
            } catch (e: Exception) {
                logger.error("发送消息失败: $sessionId, ${e.message}", e)
                cleanup(sessionId)
            }
        }
    }
    
    private fun cleanup(sessionId: String) {
        try {
            val session = clientSessions.remove(sessionId)
            lastActivityTime.remove(sessionId)
            sessionLocks.remove(sessionId)
            subscriptionService.unregisterSession(sessionId)

            if (session != null && session.isOpen) {
                try {
                    session.close(CloseStatus.NORMAL)
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            logger.error("清理 WebSocket 资源时发生错误: $sessionId, ${e.message}", e)
        }
    }
    
    private fun startCleanupTask() {
        cleanupJob = scope.launch {
            while (isActive) {
                try {
                    cleanupInactiveConnections()
                } catch (e: Exception) {
                    logger.error("清理不活跃连接失败: ${e.message}", e)
                }
                delay(30000)
            }
        }
    }
    
    private fun cleanupInactiveConnections() {
        val now = System.currentTimeMillis()
        val inactiveSessions = mutableListOf<String>()
        
        lastActivityTime.forEach { (sessionId, lastActivity) ->
            val inactiveTime = now - lastActivity
            if (inactiveTime > heartbeatTimeout) {
                inactiveSessions.add(sessionId)
            }
        }
        
        inactiveSessions.forEach { sessionId ->
            logger.warn("检测到不活跃连接，准备清理: $sessionId, 不活跃时间: ${now - (lastActivityTime[sessionId] ?: 0)}ms")
            cleanup(sessionId)
        }
        
        if (inactiveSessions.isNotEmpty()) {
        }
    }
}

