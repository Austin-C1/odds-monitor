package com.wrbug.polymarketbot.service.common

import com.wrbug.polymarketbot.dto.CryptoTailMonitorPushData
import com.wrbug.polymarketbot.dto.OrderPushMessage
import com.wrbug.polymarketbot.dto.PositionPushMessage
import com.wrbug.polymarketbot.dto.WebSocketMessage as WsMessage
import com.wrbug.polymarketbot.dto.WebSocketMessageType
import com.wrbug.polymarketbot.service.accounts.PositionPushService
import com.wrbug.polymarketbot.service.copytrading.orders.OrderPushService
import com.wrbug.polymarketbot.service.cryptotail.CryptoTailMonitorService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class WebSocketSubscriptionService(
    private val positionPushService: PositionPushService,
    private val orderPushService: OrderPushService
) {
    
    private val logger = LoggerFactory.getLogger(WebSocketSubscriptionService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val sessionCallbacks = ConcurrentHashMap<String, (WsMessage) -> Unit>()
    private val sessionSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    private val channelSubscriptions = ConcurrentHashMap<String, MutableSet<String>>()
    private val orderChannelCallbacks = ConcurrentHashMap<String, (OrderPushMessage) -> Unit>()
    private val monitorChannelCallbacks = ConcurrentHashMap<String, MutableMap<Long, (CryptoTailMonitorPushData) -> Unit>>()
    private var cryptoTailMonitorService: CryptoTailMonitorService? = null
    
    fun setCryptoTailMonitorService(service: CryptoTailMonitorService) {
        cryptoTailMonitorService = service
    }
    
    fun registerSession(sessionId: String, callback: (WsMessage) -> Unit) {
        sessionCallbacks[sessionId] = callback
        sessionSubscriptions[sessionId] = mutableSetOf()
        monitorChannelCallbacks[sessionId] = mutableMapOf()
    }
    
    fun unregisterSession(sessionId: String) {
        val channels = sessionSubscriptions.remove(sessionId) ?: emptySet()
        channels.forEach { channel ->
            unsubscribe(sessionId, channel)
        }
        orderChannelCallbacks.remove(sessionId)
        val monitorCallbacks = monitorChannelCallbacks.remove(sessionId)
        monitorCallbacks?.keys?.forEach { strategyId ->
            cryptoTailMonitorService?.unsubscribe(sessionId, strategyId)
        }

        sessionCallbacks.remove(sessionId)
    }
    
    fun subscribe(sessionId: String, channel: String, payload: Map<*, *>?) {
        val sessionChannels = sessionSubscriptions.getOrPut(sessionId) { mutableSetOf() }
        if (sessionChannels.contains(channel)) {
            sendSubscribeAck(sessionId, channel, true)
            return
        }
        sessionChannels.add(channel)
        channelSubscriptions.getOrPut(channel) { mutableSetOf() }.add(sessionId)
        sendSubscribeAck(sessionId, channel, true)
        when {
            channel == "position" -> {
                positionPushService.subscribe(sessionId) { message ->
                    pushData(sessionId, channel, message)
                }
                scope.launch {
                    try {
                        positionPushService.sendFullData(sessionId)
                    } catch (e: Exception) {
                        logger.error("发送仓位首推数据失败: $sessionId, ${e.message}", e)
                    }
                }
            }
            channel == "order" -> {
                val callback: (OrderPushMessage) -> Unit = { message ->
                    pushData(sessionId, channel, message)
                }
                orderChannelCallbacks[sessionId] = callback
                orderPushService.subscribeAllEnabled(callback)
            }
            channel.startsWith("crypto_tail_monitor_") -> {
                val strategyId = channel.removePrefix("crypto_tail_monitor_").toLongOrNull()
                if (strategyId != null && cryptoTailMonitorService != null) {
                    val callback: (CryptoTailMonitorPushData) -> Unit = { message ->
                        pushData(sessionId, channel, message)
                    }
                    monitorChannelCallbacks.getOrPut(sessionId) { mutableMapOf() }[strategyId] = callback
                    cryptoTailMonitorService!!.subscribe(sessionId, strategyId, callback)
                } else {
                    logger.warn("无效的加密价差策略监控频道或服务未初始化: $channel")
                    sendSubscribeAck(sessionId, channel, false, "无效的策略ID")
                }
            }
            else -> {
                logger.warn("未知的频道: $channel")
                sendSubscribeAck(sessionId, channel, false, "未知的频道")
            }
        }
    }
    
    fun unsubscribe(sessionId: String, channel: String) {
        sessionSubscriptions[sessionId]?.remove(channel)
        channelSubscriptions[channel]?.remove(sessionId)
        when {
            channel == "position" -> positionPushService.unsubscribe(sessionId)
            channel == "order" -> {
                val callback = orderChannelCallbacks.remove(sessionId)
                if (callback != null) {
                    orderPushService.unsubscribeAll(callback)
                }
            }
            channel.startsWith("crypto_tail_monitor_") -> {
                val strategyId = channel.removePrefix("crypto_tail_monitor_").toLongOrNull()
                if (strategyId != null) {
                    monitorChannelCallbacks[sessionId]?.remove(strategyId)
                    cryptoTailMonitorService?.unsubscribe(sessionId, strategyId)
                }
            }
        }
    }
    
    fun registerMonitorCallback(sessionId: String, strategyId: Long, callback: (CryptoTailMonitorPushData) -> Unit) {
        monitorChannelCallbacks.getOrPut(sessionId) { mutableMapOf() }[strategyId] = callback
    }
    
    fun unregisterMonitorCallback(sessionId: String, strategyId: Long) {
        monitorChannelCallbacks[sessionId]?.remove(strategyId)
    }
    
    fun pushMonitorData(strategyId: Long, data: CryptoTailMonitorPushData) {
        val channel = "crypto_tail_monitor_$strategyId"
        val sessionIds = channelSubscriptions[channel] ?: return
        
        for (sessionId in sessionIds) {
            val callback = sessionCallbacks[sessionId]
            if (callback != null) {
                val message = WsMessage(
                    type = WebSocketMessageType.DATA.value,
                    channel = channel,
                    payload = data,
                    timestamp = System.currentTimeMillis()
                )
                callback(message)
            }
        }
    }
    
    private fun pushData(sessionId: String, channel: String, payload: Any) {
        val callback = sessionCallbacks[sessionId]
        if (callback != null) {
            val message = WsMessage(
                type = WebSocketMessageType.DATA.value,
                channel = channel,
                payload = payload,
                timestamp = System.currentTimeMillis()
            )
            callback(message)
        } else {
            logger.warn("会话 $sessionId 的回调不存在，无法推送数据")
        }
    }
    
    private fun sendSubscribeAck(sessionId: String, channel: String, success: Boolean, errorMessage: String? = null) {
        val callback = sessionCallbacks[sessionId]
        if (callback != null) {
            val message = WsMessage(
                type = WebSocketMessageType.SUB_ACK.value,
                channel = channel,
                status = if (success) 0 else 1,
                message = errorMessage
            )
            callback(message)
        }
    }
}
