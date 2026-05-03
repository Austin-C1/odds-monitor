package com.wrbug.polymarketbot.service.copytrading.monitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Service
class PolymarketActivityWsService(
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository
) {

    private val logger = LoggerFactory.getLogger(PolymarketActivityWsService::class.java)
    private val websocketUrl: String = PolymarketConstants.ACTIVITY_WS_URL
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var wsClient: PolymarketWebSocketClient? = null
    private val monitoredAddresses = ConcurrentHashMap<String, Long>()
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build()

    @Volatile
    private var isSubscribed = false

    private val activityHealthTracker = ActivityStreamHealthTracker(timeoutMillis = 30_000L)
    private var activityTimeoutJob: Job? = null

    private val totalMessagesProcessed = AtomicLong(0L)
    private val addressMatchMessages = AtomicLong(0L)
    private val jsonParseMessages = AtomicLong(0L)
    private val duplicateTxHashMessages = AtomicLong(0L)

    fun start(leaders: List<Leader>) {
        monitoredAddresses.clear()
        leaders.forEach { leader ->
            leader.id?.let { monitoredAddresses[leader.leaderAddress.lowercase()] = it }
        }

        if (monitoredAddresses.isEmpty()) {
            logger.info("No leaders to monitor, stopping Activity WebSocket")
            stop()
            return
        }

        logger.info(
            "Starting Activity WebSocket monitor for {} leaders",
            monitoredAddresses.size
        )
        connectAndSubscribe()
    }

    fun addLeader(leader: Leader) {
        val leaderId = leader.id
        if (leaderId == null) {
            logger.warn("Skip adding leader without id: {}", leader.leaderAddress)
            return
        }

        val address = leader.leaderAddress.lowercase()
        if (monitoredAddresses[address] == leaderId) {
            return
        }

        monitoredAddresses[address] = leaderId
        logger.info("Added leader to Activity WebSocket monitor: {} ({})", leader.leaderName, address)

        val client = wsClient
        if (client == null || !client.isConnected()) {
            connectAndSubscribe()
        }
    }

    fun removeLeader(leaderId: Long) {
        val addressToRemove = monitoredAddresses.entries
            .find { it.value == leaderId }
            ?.key

        if (addressToRemove != null) {
            monitoredAddresses.remove(addressToRemove)
            logger.info("Removed leader from Activity WebSocket monitor: leaderId={}, address={}", leaderId, addressToRemove)
        }

        if (monitoredAddresses.isEmpty()) {
            logger.info("No leaders left to monitor, stopping Activity WebSocket")
            stop()
        }
    }

    private fun connectAndSubscribe() {
        val existingClient = wsClient
        if (existingClient != null && existingClient.isConnected()) {
            if (!isSubscribed) {
                subscribeAllActivity()
            }
            return
        }

        logger.info("Connecting Activity WebSocket: {}", websocketUrl)

        val newClient = PolymarketWebSocketClient(
            url = websocketUrl,
            sessionId = "copy-trading-activity",
            onMessage = ::handleMessage,
            onOpen = {
                logger.info("Activity WebSocket connected")
                subscribeAllActivity()
            },
            onReconnect = {
                logger.info("Activity WebSocket reconnected, resubscribing")
                subscribeAllActivity()
            }
        )

        wsClient = newClient
        scope.launch {
            runCatching { newClient.connect() }
                .onFailure { e ->
                    logger.error("Failed to connect Activity WebSocket", e)
                }
        }
    }

    private fun subscribeAllActivity() {
        val client = wsClient
        if (client == null || !client.isConnected()) {
            logger.warn("Activity WebSocket is not connected, skip subscribe")
            return
        }

        try {
            val subscribeMessage = """
                {
                    "action": "subscribe",
                    "subscriptions": [
                        {
                            "topic": "activity",
                            "type": "trades"
                        },
                        {
                            "topic": "activity",
                            "type": "orders_matched"
                        }
                    ]
                }
            """.trimIndent()

            client.sendMessage(subscribeMessage)
            isSubscribed = true
            activityHealthTracker.markMessage()
            startActivityTimeoutCheck()
            logger.info("Activity WebSocket subscribed to trades and orders_matched")
        } catch (e: Exception) {
            logger.error("Failed to subscribe Activity WebSocket", e)
            isSubscribed = false
        }
    }

    private fun startActivityTimeoutCheck() {
        stopActivityTimeoutCheck()

        activityTimeoutJob = scope.launch {
            while (isActive) {
                delay(30_000L)

                if (!isSubscribed) {
                    connectAndSubscribe()
                    continue
                }

                if (!activityHealthTracker.shouldReconnect()) {
                    continue
                }

                logger.warn("No Activity messages received for 30 seconds, reconnecting Activity WebSocket")
                wsClient?.closeConnection()
                wsClient = null
                isSubscribed = false
                activityHealthTracker.reset()
                connectAndSubscribe()
            }
        }
    }

    private fun stopActivityTimeoutCheck() {
        activityTimeoutJob?.cancel()
        activityTimeoutJob = null
    }

    private fun containsMonitoredAddress(message: String): Boolean {
        if (message.length < 50) {
            return false
        }

        for ((address, _) in monitoredAddresses) {
            if (message.contains("\"proxyWallet\":\"$address\"", ignoreCase = true)) {
                addressMatchMessages.incrementAndGet()
                return true
            }

            if (
                message.contains("\"trader\"", ignoreCase = true) &&
                message.contains("\"address\":\"$address\"", ignoreCase = true)
            ) {
                addressMatchMessages.incrementAndGet()
                return true
            }
        }

        return false
    }

    private fun handleMessage(message: String) {
        try {
            totalMessagesProcessed.incrementAndGet()

            if (message.trim().equals("pong", ignoreCase = true)) {
                return
            }

            activityHealthTracker.markMessage()

            if (!containsMonitoredAddress(message)) {
                return
            }

            val tradeMessage = message.fromJson<ActivityTradeMessage>() ?: run {
                logger.warn("Failed to parse ActivityTradeMessage: {}", message.take(200))
                return
            }
            jsonParseMessages.incrementAndGet()

            if (tradeMessage.topic != "activity" || (tradeMessage.type != "trades" && tradeMessage.type != "orders_matched")) {
                return
            }

            val payload = tradeMessage.payload
            val txHash = payload.transactionHash
            if (!txHash.isNullOrBlank()) {
                val existingTimestamp = processedTxHashes.asMap().putIfAbsent(txHash, System.currentTimeMillis())
                if (existingTimestamp != null) {
                    duplicateTxHashMessages.incrementAndGet()
                    logger.debug(
                        "Skip duplicate trade: txHash={}, firstProcessedAt={}, type={}",
                        txHash,
                        existingTimestamp,
                        tradeMessage.type
                    )
                    return
                }
            }

            val traderAddress = extractTraderAddress(payload) ?: run {
                logger.warn(
                    "Activity message missing trader address: trader={}, proxyWallet={}, asset={}",
                    payload.trader,
                    payload.proxyWallet,
                    payload.asset
                )
                return
            }

            val leaderId = monitoredAddresses[traderAddress.lowercase()] ?: return
            val trade = parseActivityTrade(payload, leaderId) ?: run {
                logger.warn(
                    "Failed to parse trade payload: leaderId={}, address={}, asset={}, side={}",
                    leaderId,
                    traderAddress,
                    payload.asset,
                    payload.side
                )
                return
            }

            logger.info(
                "Detected leader trade: leaderId={}, address={}, side={}, market={}, size={}",
                leaderId,
                traderAddress,
                trade.side,
                trade.market,
                trade.size
            )

            scope.launch {
                try {
                    copyOrderTrackingService.processTrade(
                        leaderId = leaderId,
                        trade = trade,
                        source = "activity-ws"
                    )
                } catch (e: Exception) {
                    logger.error("Failed to process Activity trade: leaderId={}, tradeId={}", leaderId, trade.id, e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to handle Activity WebSocket message: {}", e.message, e)
        }
    }

    private fun extractTraderAddress(payload: ActivityTradePayload): String? {
        return payload.trader?.address ?: payload.proxyWallet
    }

    private fun parseActivityTrade(payload: ActivityTradePayload, leaderId: Long): TradeResponse? {
        return try {
            val asset = payload.asset
            val conditionId = payload.conditionId
            val sideRaw = payload.side

            if (asset.isBlank() || conditionId.isBlank() || sideRaw.isBlank()) {
                logger.warn(
                    "Activity trade missing required fields: asset={}, conditionId={}, side={}",
                    asset,
                    conditionId,
                    sideRaw
                )
                return null
            }

            val side = sideRaw.uppercase()
            if (side != "BUY" && side != "SELL") {
                logger.warn("Activity trade side is invalid: {}", side)
                return null
            }

            val price = convertToString(payload.price) ?: run {
                logger.warn("Activity trade price is invalid: {}", payload.price)
                return null
            }
            val size = convertToString(payload.size) ?: run {
                logger.warn("Activity trade size is invalid: {}", payload.size)
                return null
            }

            val timestamp = when (val value = payload.timestamp) {
                null -> System.currentTimeMillis().toString()
                is Number -> {
                    val timestampValue = value.toLong()
                    if (timestampValue < 1_000_000_000_000L) {
                        (timestampValue * 1000).toString()
                    } else {
                        timestampValue.toString()
                    }
                }
                is String -> {
                    val timestampValue = value.toLongOrNull() ?: System.currentTimeMillis()
                    if (timestampValue < 1_000_000_000_000L) {
                        (timestampValue * 1000).toString()
                    } else {
                        timestampValue.toString()
                    }
                }
                else -> System.currentTimeMillis().toString()
            }

            val outcome = payload.outcome
            val outcomeIndex = payload.outcomeIndex ?: parseOutcomeIndex(outcome)
            val tradeId = payload.transactionHash ?: "${leaderId}_${System.currentTimeMillis()}_${asset.take(10)}"

            TradeResponse(
                id = tradeId,
                market = conditionId,
                side = side,
                price = price,
                size = size,
                timestamp = timestamp,
                user = null,
                outcomeIndex = outcomeIndex,
                outcome = outcome,
                tokenId = asset
            )
        } catch (e: Exception) {
            logger.error("Failed to parse Activity trade payload", e)
            null
        }
    }

    private fun convertToString(value: Any?): String? {
        if (value == null) return null

        return when (value) {
            is String -> value
            is BigDecimal -> value.toPlainString()
            is Number -> {
                runCatching { BigDecimal(value.toString()).toPlainString() }
                    .getOrElse { value.toString() }
            }
            else -> value.toString()
        }
    }

    private fun parseOutcomeIndex(outcome: String?): Int? {
        return when (outcome?.uppercase()) {
            "YES", "UP", "TRUE" -> 0
            "NO", "DOWN", "FALSE" -> 1
            else -> null
        }
    }

    fun stop() {
        logger.info("Stopping Activity WebSocket monitor")
        stopActivityTimeoutCheck()
        wsClient?.closeConnection()
        wsClient = null
        isSubscribed = false
        monitoredAddresses.clear()
        processedTxHashes.invalidateAll()
        activityHealthTracker.reset()
    }

    fun isConnected(): Boolean = wsClient?.isConnected() ?: false

    fun getMonitoredCount(): Int = monitoredAddresses.size

    fun getPerformanceStats(): Map<String, Any> {
        val totalMessageCount = totalMessagesProcessed.get()
        val addressMatchCount = addressMatchMessages.get()
        val jsonParseCount = jsonParseMessages.get()
        val duplicateTxHashCount = duplicateTxHashMessages.get()
        val jsonParseRate = if (totalMessageCount > 0) {
            (jsonParseCount.toDouble() / totalMessageCount * 100).toInt()
        } else {
            0
        }

        return mapOf(
            "totalMessages" to totalMessageCount,
            "addressMatches" to addressMatchCount,
            "jsonParses" to jsonParseCount,
            "duplicateTxHashes" to duplicateTxHashCount,
            "jsonParseRate" to "$jsonParseRate%",
            "filteringEfficiency" to if (totalMessageCount > 0) {
                ((1.0 - jsonParseCount.toDouble() / totalMessageCount) * 100).toInt()
            } else {
                0
            }
        )
    }

    @PreDestroy
    fun destroy() {
        logger.info("Activity WebSocket stats: {}", getPerformanceStats())
        stop()
        scope.cancel()
    }
}
