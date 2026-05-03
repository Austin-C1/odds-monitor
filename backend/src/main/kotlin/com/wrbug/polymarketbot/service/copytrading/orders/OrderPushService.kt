package com.wrbug.polymarketbot.service.copytrading.orders

import com.fasterxml.jackson.databind.ObjectMapper
import com.wrbug.polymarketbot.dto.OrderDetailDto
import com.wrbug.polymarketbot.dto.OrderMessageDto
import com.wrbug.polymarketbot.dto.OrderPushMessage
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class OrderPushService(
    private val accountRepository: AccountRepository,
    private val objectMapper: ObjectMapper,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository? = null,
    private val copyTradingRepository: CopyTradingRepository? = null,
    private val leaderRepository: LeaderRepository? = null,
    private val marketService: MarketService
) {

    private val logger = LoggerFactory.getLogger(OrderPushService::class.java)

    private val polymarketWsUrl: String = PolymarketConstants.RTDS_WS_URL
    private val accountConnections = ConcurrentHashMap<Long, PolymarketWebSocketClient>()
    private val accountCallbacks = ConcurrentHashMap<Long, MutableSet<(OrderPushMessage) -> Unit>>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @PostConstruct
    fun init() {
        scope.launch {
            connectAllAccounts()
        }
    }

    @PreDestroy
    fun destroy() {
        accountConnections.values.forEach { client ->
            try {
                if (client.isConnected()) {
                    client.closeConnection()
                }
            } catch (e: Exception) {
                logger.error("关闭账户连接失败: ${e.message}", e)
            }
        }
        accountConnections.clear()
        accountCallbacks.clear()
        scope.cancel()
    }

    private suspend fun connectAllAccounts() {
        val accounts = accountRepository.findAll()
        accounts.forEach { account ->
            if (hasApiCredentials(account) && account.isEnabled) {
                connectAccount(account)
            }
        }
    }

    fun subscribeAllEnabled(callback: (OrderPushMessage) -> Unit) {
        val accounts = accountRepository.findAll()
        accounts.forEach { account ->
            if (hasApiCredentials(account) && account.isEnabled) {
                val accountId = account.id!!
                accountCallbacks.getOrPut(accountId) { mutableSetOf() }.add(callback)
                if (!accountConnections.containsKey(accountId)) {
                    connectAccount(account)
                }
            }
        }
    }

    fun unsubscribeAll(callback: (OrderPushMessage) -> Unit) {
        logger.info("取消订阅所有账户的订单推送")
        accountCallbacks.values.forEach { callbacks ->
            callbacks.remove(callback)
        }
    }

    fun refreshSubscriptions() {
        logger.info("刷新所有账户的订阅状态")
        val accounts = accountRepository.findAll()
        val enabledAccountIds = accounts
            .filter { hasApiCredentials(it) && it.isEnabled }
            .map { it.id!! }
            .toSet()
        accountConnections.keys.forEach { accountId ->
            if (!enabledAccountIds.contains(accountId)) {
                disconnectAccount(accountId)
            }
        }
        accounts.forEach { account ->
            if (hasApiCredentials(account) && account.isEnabled) {
                val accountId = account.id!!
                if (!accountConnections.containsKey(accountId)) {
                    connectAccount(account)
                }
            }
        }
    }

    private fun hasApiCredentials(account: Account): Boolean {
        return account.apiKey != null &&
                account.apiSecret != null &&
                account.apiPassphrase != null &&
                account.apiKey.isNotBlank() &&
                account.apiSecret.isNotBlank() &&
                account.apiPassphrase.isNotBlank()
    }
    
    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("解密 API Secret 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Secret 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Secret")
    }
    
    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("解密 API Passphrase 失败: accountId=${account.id}", e)
                throw RuntimeException("解密 API Passphrase 失败: ${e.message}", e)
            }
        } ?: throw IllegalStateException("账户未配置 API Passphrase")
    }

    fun connectAccount(account: Account) {
        if (!hasApiCredentials(account)) {
            logger.warn("账户 ${account.id} 没有 API 凭证，无法建立连接")
            return
        }

        if (!account.isEnabled) {
            return
        }

        if (accountConnections.containsKey(account.id)) {
            return
        }

        scope.launch {
            try {
                val wsUrl = "$polymarketWsUrl/ws/user"
                val client = PolymarketWebSocketClient(
                    url = wsUrl,
                    sessionId = "account-${account.id}",
                    onMessage = { message -> handleMessage(account, message) },
                    onOpen = {
                        val currentClient = accountConnections[account.id!!]
                        if (currentClient != null) {
                            try {
                                sendSubscribeMessage(currentClient, account)
                            } catch (e: Exception) {
                                logger.error("发送订阅消息失败: account=${account.id}, ${e.message}", e)
                                currentClient.closeConnection()
                                accountConnections.remove(account.id)
                            }
                        } else {
                            logger.warn("账户 ${account.id} 的连接不存在，无法发送订阅消息")
                        }
                    },
                    onReconnect = {
                        val currentClient = accountConnections[account.id!!]
                        if (currentClient != null) {
                            try {
                                sendSubscribeMessage(currentClient, account)
                            } catch (e: Exception) {
                                logger.error("重连后发送订阅消息失败: account=${account.id}, ${e.message}", e)
                            }
                        }
                    }
                )

                accountConnections[account.id!!] = client
                client.connect()
            } catch (e: Exception) {
                logger.error("为账户 ${account.id} 建立连接失败: ${e.message}", e)
                accountConnections.remove(account.id)
            }
        }
    }

    /**
     *
     * {
     *   "auth": { "apiKey": "...", "secret": "...", "passphrase": "..." },
     *   "type": "user",
     *   "markets": [],
     *   "assets_ids": [],
     *   "initial_dump": true
     * }
     */
    private fun sendSubscribeMessage(client: PolymarketWebSocketClient, account: Account) {
        try {
            val apiSecret = try {
                decryptApiSecret(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败，无法发送订阅消息: accountId=${account.id}, error=${e.message}")
                return
            }
            val apiPassphrase = try {
                decryptApiPassphrase(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败，无法发送订阅消息: accountId=${account.id}, error=${e.message}")
                return
            }
            
            val subscribeMessage = mapOf(
                "auth" to mapOf(
                    "apiKey" to account.apiKey,
                    "secret" to apiSecret,
                    "passphrase" to apiPassphrase
                ),
                "type" to "user",
                "markets" to emptyList<String>(),
                "assets_ids" to emptyList<String>(),
                "initial_dump" to true
            )

            val json = objectMapper.writeValueAsString(subscribeMessage)
            client.sendMessage(json)
        } catch (e: Exception) {
            logger.error("发送订阅消息失败: account=${account.id}, ${e.message}", e)
        }
    }

    private fun handleMessage(account: Account, message: String) {
        try {
            if (message.trim() == "PONG" || message.trim() == "pong") {
                return
            }
            val messageMap = objectMapper.readValue(message, Map::class.java) as Map<*, *>
            val eventType = messageMap["event_type"] as? String
            if (eventType == "order") {
                val orderMessage = objectMapper.readValue(message, OrderMessageDto::class.java)
                scope.launch {
                    val orderDetail = fetchOrderDetail(account, orderMessage.id, orderMessage.market)
                    var leaderName: String? = null
                    var configName: String? = null
                    
                    if (copyOrderTrackingRepository != null && copyTradingRepository != null && leaderRepository != null) {
                        try {
                            val trackingList = copyOrderTrackingRepository.findByBuyOrderId(orderMessage.id)
                            val tracking = trackingList.firstOrNull()
                            if (tracking != null) {
                                val copyTrading = copyTradingRepository.findById(tracking.copyTradingId).orElse(null)
                                if (copyTrading != null) {
                                    configName = copyTrading.configName
                                    val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
                                    if (leader != null) {
                                        leaderName = leader.leaderName
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logger.warn("查询跟单信息失败: orderId=${orderMessage.id}, ${e.message}", e)
                        }
                    }

                    val pushMessage = OrderPushMessage(
                        accountId = account.id!!,
                        accountName = account.accountName ?: account.walletAddress,
                        order = orderMessage,
                        orderDetail = orderDetail,
                        leaderName = leaderName,
                        configName = configName
                    )
                    accountCallbacks[account.id]?.forEach { callback ->
                        try {
                            callback(pushMessage)
                        } catch (e: Exception) {
                            logger.error("推送订单消息失败: account=${account.id}, ${e.message}", e)
                        }
                    }
                }
            } else {
            }
        } catch (e: Exception) {
            if (message.trim() == "PONG" || message.trim() == "pong") {
            } else {
                logger.error("处理订单消息失败: account=${account.id}, message=${message.take(100)}, ${e.message}", e)
            }
        }
    }

    private suspend fun fetchOrderDetail(
        account: Account,
        orderId: String,
        conditionId: String? = null
    ): OrderDetailDto? {
        return try {
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return null
            }
            val apiSecret = try {
                decryptApiSecret(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败，无法获取订单详情: accountId=${account.id}, error=${e.message}")
                return null
            }
            val apiPassphrase = try {
                decryptApiPassphrase(account)
            } catch (e: Exception) {
                logger.error("解密 API 凭证失败，无法获取订单详情: accountId=${account.id}, error=${e.message}")
                return null
            }
            
            val result = clobService.getOrder(
                orderId = orderId,
                apiKey = account.apiKey!!,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddress = account.walletAddress
            )
            
            result.fold(
                onSuccess = { openOrder ->
                    val market = marketService.getMarket(conditionId ?: openOrder.market)
                    val sizeMatched = openOrder.sizeMatched.toSafeBigDecimal()
                    val avgFilledPrice = if (sizeMatched.gt(BigDecimal.ZERO)) {
                        openOrder.originalSize.toSafeBigDecimal()
                            .multi(openOrder.price)
                            .div(sizeMatched, 18)
                    } else null
                    OrderDetailDto(
                        id = openOrder.id,
                        market = openOrder.market,
                        side = openOrder.side,
                        price = openOrder.price,
                        size = openOrder.originalSize,
                        filled = openOrder.sizeMatched,
                        status = openOrder.status,
                        createdAt = openOrder.createdAt.toString(),
                        marketName = market?.title,
                        marketSlug = market?.slug,
                        marketIcon = market?.icon,
                        avgFilledPrice = avgFilledPrice?.toPlainString()
                    )
                },
                onFailure = { e ->
                    logger.warn("获取订单详情失败: account=${account.id}, orderId=$orderId, ${e.message}")
                    null
                }
            )
        } catch (e: Exception) {
            logger.error("获取订单详情异常: account=${account.id}, orderId=$orderId, ${e.message}", e)
            null
        }
    }

    fun subscribe(accountId: Long, callback: (OrderPushMessage) -> Unit) {
        accountCallbacks.getOrPut(accountId) { mutableSetOf() }.add(callback)
        if (!accountConnections.containsKey(accountId)) {
            val account = accountRepository.findById(accountId).orElse(null)
            if (account != null && hasApiCredentials(account) && account.isEnabled) {
                connectAccount(account)
            }
        }
    }

    fun unsubscribe(accountId: Long, callback: (OrderPushMessage) -> Unit) {
        accountCallbacks[accountId]?.remove(callback)
    }

    fun reconnectAllAccounts() {
        logger.info("重连所有账户的 WebSocket 连接（代理配置已更新）")
        val accountIds = accountConnections.keys.toList()
        accountIds.forEach { accountId ->
            try {
                val oldClient = accountConnections.remove(accountId)
                oldClient?.closeConnection()
                val account = accountRepository.findById(accountId).orElse(null)
                if (account != null && hasApiCredentials(account) && account.isEnabled) {
                    connectAccount(account)
                }
            } catch (e: Exception) {
                logger.error("重连账户 $accountId 失败", e)
            }
        }
    }
    
    fun getConnectionStatuses(): Map<Long, Boolean> {
        return accountConnections.mapValues { (_, client) -> client.isConnected() }
    }
    
    fun disconnectAccount(accountId: Long) {
        val client = accountConnections.remove(accountId)
        client?.let {
            try {
                if (it.isConnected()) {
                    it.closeConnection()
                }
            } catch (e: Exception) {
                logger.error("关闭账户连接失败: $accountId, ${e.message}", e)
            }
        }
        accountCallbacks.remove(accountId)
    }
}

