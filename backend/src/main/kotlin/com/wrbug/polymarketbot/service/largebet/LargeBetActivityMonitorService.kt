package com.wrbug.polymarketbot.service.largebet

import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.ActivityTradeMessage
import com.wrbug.polymarketbot.dto.ActivityTradePayload
import com.wrbug.polymarketbot.dto.LargeBetMonitorStatusDto
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.websocket.PolymarketWebSocketClient
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class LargeBetActivityMonitorService(
    private val configService: LargeBetMonitorConfigService,
    private val aggregator: LargeBetRollingAggregator,
    private val watchRecordService: LargeBetWatchRecordService,
    private val telegramAlertService: LargeBetTelegramAlertService
) {

    private val logger = LoggerFactory.getLogger(LargeBetActivityMonitorService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val seenTradeIds = ConcurrentHashMap<String, Long>()
    private var wsClient: PolymarketWebSocketClient? = null
    private var configWatchJob: Job? = null
    @Volatile
    private var connected = false
    @Volatile
    private var subscribed = false

    @PostConstruct
    fun init() {
        configWatchJob = scope.launch {
            while (isActive) {
                reconcileConnection()
                val interval = runCatching { configService.getConfigEntity().checkIntervalSeconds }
                    .getOrDefault(30)
                    .coerceIn(5, 3600)
                delay(interval * 1000L)
            }
        }
    }

    suspend fun reconcileConnection() {
        val config = configService.getConfigEntity()
        if (config.enabled) {
            start()
        } else {
            stop()
        }
    }

    fun start() {
        val existing = wsClient
        if (existing != null && existing.isConnected()) {
            if (!subscribed) subscribe()
            connected = true
            return
        }

        val client = PolymarketWebSocketClient(
            url = PolymarketConstants.ACTIVITY_WS_URL,
            sessionId = "large-bet-monitor-activity",
            onMessage = ::handleMessage,
            onOpen = {
                connected = true
                subscribe()
            },
            onReconnect = {
                connected = true
                subscribe()
            }
        )
        wsClient = client
        scope.launch {
            runCatching { client.connect() }
                .onFailure { e ->
                    connected = false
                    subscribed = false
                    logger.error("Failed to connect large bet Activity WebSocket", e)
                }
        }
    }

    fun stop() {
        wsClient?.closeConnection()
        wsClient = null
        connected = false
        subscribed = false
    }

    private fun subscribe() {
        val client = wsClient
        if (client == null || !client.isConnected()) {
            connected = false
            subscribed = false
            return
        }

        val message = """
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
        runCatching {
            client.sendMessage(message)
            subscribed = true
        }.onFailure { e ->
            connected = false
            subscribed = false
            logger.error("Failed to subscribe large bet Activity WebSocket", e)
        }
    }

    private fun handleMessage(message: String) {
        if (message.trim().equals("pong", ignoreCase = true)) {
            return
        }
        val tradeMessage = message.fromJson<ActivityTradeMessage>() ?: return
        scope.launch {
            handleActivityTradeMessage(tradeMessage)
        }
    }

    suspend fun handleActivityTradeMessage(message: ActivityTradeMessage): Boolean {
        if (message.topic != "activity" || (message.type != "trades" && message.type != "orders_matched")) {
            return false
        }
        val event = normalize(message.payload) ?: return false
        return handleFilledTrade(event)
    }

    fun normalize(payload: ActivityTradePayload): LargeBetTradeEvent? {
        val traderAddress = payload.trader?.address ?: payload.proxyWallet ?: return null
        val conditionId = payload.conditionId.takeIf { it.isNotBlank() } ?: return null
        val price = toBigDecimal(payload.price)?.takeIf { it > BigDecimal.ZERO } ?: return null
        val size = toBigDecimal(payload.size)?.takeIf { it > BigDecimal.ZERO } ?: return null
        val timestampMillis = toTimestampMillis(payload.timestamp)
        val title = payload.name?.takeIf { it.isNotBlank() } ?: payload.slug ?: conditionId
        val sportType = LargeBetMarketClassifier().classify(
            title = listOfNotNull(title, payload.eventSlug, payload.slug).joinToString(" ")
        ) ?: return null
        val outcome = payload.outcome ?: payload.outcomeIndex?.toString() ?: return null
        val tradeId = payload.transactionHash
            ?: "${traderAddress.lowercase()}_${conditionId}_${payload.asset}_${payload.side}_${price}_${size}_$timestampMillis"

        return LargeBetTradeEvent(
            tradeId = tradeId,
            traderAddress = traderAddress,
            traderName = payload.trader?.name,
            marketId = conditionId,
            marketSlug = payload.eventSlug ?: payload.slug,
            marketTitle = title,
            sportType = sportType,
            outcome = outcome,
            price = price,
            size = size,
            timestampMillis = timestampMillis
        )
    }

    suspend fun handleFilledTrade(event: LargeBetTradeEvent): Boolean {
        return withContext(Dispatchers.Default) {
            val config = configService.getConfigEntity()
            if (!config.enabled) {
                return@withContext false
            }
            if (event.sportType == "FOOTBALL" && !config.footballEnabled) {
                return@withContext false
            }
            if (event.sportType == "BASKETBALL" && !config.basketballEnabled) {
                return@withContext false
            }
            if (event.sportType != "FOOTBALL" && event.sportType != "BASKETBALL") {
                return@withContext false
            }
            if (seenTradeIds.putIfAbsent(event.tradeId, event.timestampMillis) != null) {
                return@withContext false
            }

            val result = aggregator.record(
                event = event,
                singleTradeThreshold = config.singleTradeThreshold,
                cumulativeTradeThreshold = config.cumulativeTradeThreshold,
                rollingWindowMinutes = config.rollingWindowMinutes
            )
            if (!result.triggered) {
                return@withContext false
            }

            val reason = when {
                result.singleTriggered && result.cumulativeTriggered -> "BOTH"
                result.singleTriggered -> "SINGLE"
                else -> "CUMULATIVE"
            }
            watchRecordService.upsert(event, reason, result.singleAmount, result.cumulativeAmount)
            val sent = telegramAlertService.sendAlert(
                event = event,
                triggerReason = reason,
                singleAmount = result.singleAmount,
                cumulativeAmount = result.cumulativeAmount,
                telegramConfigId = config.telegramConfigId
            )
            if (!sent) {
                logger.warn("Large bet Telegram alert was not sent for tradeId={}", event.tradeId)
            }
            true
        }
    }

    suspend fun getStatus(): LargeBetMonitorStatusDto {
        val config = configService.getConfigEntity()
        return LargeBetMonitorStatusDto(
            enabled = config.enabled,
            connected = connected && (wsClient?.isConnected() ?: false),
            trackedBuckets = aggregator.trackedBucketCount()
        )
    }

    fun markConnected(value: Boolean) {
        connected = value
    }

    private fun toBigDecimal(value: Any?): BigDecimal? {
        return when (value) {
            null -> null
            is BigDecimal -> value
            is Number -> value.toString().toBigDecimalOrNull()
            is String -> value.trim().toBigDecimalOrNull()
            else -> value.toString().trim().toBigDecimalOrNull()
        }
    }

    private fun toTimestampMillis(value: Any?): Long {
        val raw = when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        } ?: System.currentTimeMillis()
        return if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }

    @PreDestroy
    fun destroy() {
        configWatchJob?.cancel()
        stop()
        scope.cancel()
    }
}
