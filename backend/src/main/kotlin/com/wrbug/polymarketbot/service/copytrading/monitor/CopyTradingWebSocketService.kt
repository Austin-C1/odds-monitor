package com.wrbug.polymarketbot.service.copytrading.monitor

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import com.wrbug.polymarketbot.constants.PolymarketConstants
import org.slf4j.LoggerFactory
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class CopyTradingWebSocketService(
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val templateRepository: CopyTradingTemplateRepository,
    private val gson: Gson
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingWebSocketService::class.java)
    
    private val websocketUrl: String = PolymarketConstants.USER_WS_URL
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val leaderClients = ConcurrentHashMap<Long, PolymarketWebSocketClient>()
    private val leaderAddresses = ConcurrentHashMap<Long, String>()
    
    fun start(leaders: List<Leader>) {
        
        leaders.forEach { leader ->
            try {
                addLeader(leader)
            } catch (e: Exception) {
                logger.error("添加Leader监听失败: leaderId=${leader.id}, address=${leader.leaderAddress}", e)
            }
        }
    }
    
    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID为空，跳过: ${leader.leaderAddress}")
            return
        }
        
        if (leaderClients.containsKey(leader.id)) {
            return
        }
        
        val leaderId = leader.id
        val leaderAddress = leader.leaderAddress.lowercase()
        leaderAddresses[leaderId] = leaderAddress
        val client = PolymarketWebSocketClient(
            url = websocketUrl,
            sessionId = "copy-trading-$leaderId",
            onMessage = { message -> handleMessage(leaderId, message) },
            onOpen = {
                val wsClient = leaderClients[leaderId]
                if (wsClient != null) {
                    subscribeUserTrades(wsClient, leaderAddress)
                }
            },
            onReconnect = {
                val wsClient = leaderClients[leaderId]
                if (wsClient != null) {
                    subscribeUserTrades(wsClient, leaderAddress)
                }
            }
        )
        
        leaderClients[leaderId] = client
        scope.launch {
            try {
                client.connect()
            } catch (e: Exception) {
                logger.error("连接WebSocket失败: leaderId=$leaderId", e)
                leaderClients.remove(leaderId)
                leaderAddresses.remove(leaderId)
            }
        }
    }
    
    fun removeLeader(leaderId: Long) {
        val client = leaderClients.remove(leaderId)
        leaderAddresses.remove(leaderId)
        
        if (client != null) {
            try {
                client.closeConnection()
            } catch (e: Exception) {
                logger.error("关闭WebSocket连接失败: leaderId=$leaderId", e)
            }
        }
    }
    
    fun stop() {
        val leaderIds = leaderClients.keys.toList()
        leaderIds.forEach { leaderId ->
            removeLeader(leaderId)
        }
    }
    
    fun reconnectAll() {
        logger.info("重连所有 Leader 的 WebSocket 连接（代理配置已更新）")
        val leaderIds = leaderClients.keys.toList()
        val leaderAddressesMap = leaderAddresses.toMap()
        
        leaderIds.forEach { leaderId ->
            try {
                val oldClient = leaderClients.remove(leaderId)
                oldClient?.closeConnection()
                val leaderAddress = leaderAddressesMap[leaderId]
                if (leaderAddress != null) {
                    val leader = Leader(
                        id = leaderId,
                        leaderAddress = leaderAddress,
                        leaderName = null,
                        category = null
                    )
                    addLeader(leader)
                }
            } catch (e: Exception) {
                logger.error("重连 Leader $leaderId 失败", e)
            }
        }
    }
    
    fun getConnectionStatuses(): Map<Long, Boolean> {
        return leaderClients.mapValues { (_, client) -> client.isConnected() }
    }
    
    private fun subscribeUserTrades(client: PolymarketWebSocketClient, userAddress: String) {
        try {
            val subscribeMessage = """
                {
                    "type": "subscribe",
                    "channel": "user",
                    "user": "$userAddress"
                }
            """.trimIndent()
            
            client.sendMessage(subscribeMessage)
        } catch (e: Exception) {
            logger.error("订阅用户交易频道失败: $userAddress", e)
        }
    }
    
    private fun handleMessage(leaderId: Long, message: String) {
        try {
            if (message.trim() == "PONG") {
                return
            }
            val json = JsonParser.parseString(message).asJsonObject
            val eventType = json.get("event_type")?.asString
            if (eventType != "trade") {
                return
            }
            val trade = parseTradeMessage(json)
            if (trade != null) {
                scope.launch {
                    try {
                        copyOrderTrackingService.processTrade(leaderId, trade, "activity-ws")
                    } catch (e: Exception) {
                        logger.error("处理交易失败: leaderId=$leaderId, tradeId=${trade.id}", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("处理WebSocket消息失败: leaderId=$leaderId, message=$message", e)
        }
    }
    
    private fun parseTradeMessage(json: JsonObject): TradeResponse? {
        return try {
            val id = json.get("id")?.asString ?: json.get("trade_id")?.asString
            val market = json.get("market")?.asString
            val side = json.get("side")?.asString
            val price = json.get("price")?.asString
            val size = json.get("size")?.asString
            val timestamp = json.get("timestamp")?.asString ?: System.currentTimeMillis().toString()
            val user = json.get("user")?.asJsonObject?.get("address")?.asString
            
            if (id == null || market == null || side == null || price == null || size == null) {
                logger.warn("交易消息缺少必需字段: $json")
                return null
            }
            
            TradeResponse(
                id = id,
                market = market,
                side = side,
                price = price,
                size = size,
                timestamp = timestamp,
                user = user
            )
        } catch (e: Exception) {
            logger.error("解析交易消息失败: $json", e)
            null
        }
    }
}

