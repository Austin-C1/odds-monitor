package com.wrbug.polymarketbot.service.cryptotail

import com.wrbug.polymarketbot.api.GammaEventBySlugResponse
import com.wrbug.polymarketbot.constants.PolymarketConstants
import com.wrbug.polymarketbot.dto.CryptoTailMonitorInitRequest
import com.wrbug.polymarketbot.dto.CryptoTailMonitorInitResponse
import com.wrbug.polymarketbot.dto.CryptoTailMonitorPushData
import com.wrbug.polymarketbot.entity.CryptoTailStrategy
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CryptoTailStrategyRepository
import com.wrbug.polymarketbot.service.binance.BinanceKlineAutoSpreadService
import com.wrbug.polymarketbot.service.binance.BinanceKlineService
import com.wrbug.polymarketbot.service.common.WebSocketSubscriptionService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import com.wrbug.polymarketbot.util.fromJson
import com.wrbug.polymarketbot.util.toJson
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@Service
class CryptoTailMonitorService(
    private val strategyRepository: CryptoTailStrategyRepository,
    private val accountRepository: AccountRepository,
    private val retrofitFactory: RetrofitFactory,
    private val binanceKlineService: BinanceKlineService,
    private val binanceKlineAutoSpreadService: BinanceKlineAutoSpreadService,
    private val webSocketSubscriptionService: WebSocketSubscriptionService
) {

    private val logger = LoggerFactory.getLogger(CryptoTailMonitorService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val currentPeriodTokenToStrategy = AtomicReference<Map<String, List<MonitorEntry>>>(emptyMap())

    private val nextPeriodTokenToStrategy = AtomicReference<Map<String, List<MonitorEntry>>>(emptyMap())

    private val strategyPriceData = ConcurrentHashMap<Long, StrategyPriceData>()

    private val strategySubscribers = ConcurrentHashMap<Long, Int>()

    private var currentPeriodWebSocket: WebSocket? = null
    private var nextPeriodWebSocket: WebSocket? = null
    private val wsUrl = PolymarketConstants.RTDS_WS_URL + "/ws/market"

    private val client by lazy {
        createClient().build()
    }

    private val reconnectDelayMs = 3_000L
    private var reconnectJob: Job? = null
    private val closedForNoSubscribers = AtomicBoolean(false)
    private val connectLock = Any()

    private val refreshSubscriptionMutex = Mutex()

    private var periodEndCountdownJob: Job? = null

    private var periodicPushJob: Job? = null
    private val pushIntervalMs = 1_500L

    private val strategyPushHistory = ConcurrentHashMap<Long, MutableList<CryptoTailMonitorPushData>>()
    private val strategyHistoryPeriod = ConcurrentHashMap<Long, Long>()
    private val maxHistorySize = 300

    private val lastPriceChangePushTime = ConcurrentHashMap<Long, Long>()
    private val priceChangePushThrottleMs = 1_000L

    private val marketTitleByStrategyPeriod = ConcurrentHashMap<String, String>()

    data class MonitorEntry(
        val strategyId: Long,
        val strategy: CryptoTailStrategy,
        val periodStartUnix: Long,
        val outcomeIndex: Int,
        val tokenId: String,
        val isNextPeriod: Boolean = false
    )

    data class StrategyPriceData(
        val currentPriceUp: BigDecimal? = null,
        val currentPriceDown: BigDecimal? = null,
        val openPriceBtc: BigDecimal? = null,
        val spreadUp: BigDecimal? = null,
        val spreadDown: BigDecimal? = null,
        val minSpreadLineUp: BigDecimal? = null,
        val minSpreadLineDown: BigDecimal? = null,
        val triggered: Boolean = false,
        val triggerDirection: String? = null,
        val lastUpdateTime: Long = System.currentTimeMillis(),
        val periodStartUnix: Long? = null
    )

    @PostConstruct
    fun init() {
    }

    fun initMonitor(request: CryptoTailMonitorInitRequest): Result<CryptoTailMonitorInitResponse> {
        return try {
            val strategy = strategyRepository.findById(request.strategyId).orElse(null)
            if (strategy == null) {
                return Result.failure(IllegalArgumentException("策略不存在"))
            }

            val account = accountRepository.findById(strategy.accountId).orElse(null)
            val nowSeconds = System.currentTimeMillis() / 1000
            val periodStartUnix = request.periodStartUnix
                ?: ((nowSeconds / strategy.intervalSeconds) * strategy.intervalSeconds)
            val slug = "${strategy.marketSlugPrefix}-$periodStartUnix"
            val event = fetchEventBySlug(slug).getOrNull()
            val market = event?.markets?.firstOrNull()
            val tokenIds = parseClobTokenIds(market?.clobTokenIds)
            val openClose = binanceKlineService.getCurrentOpenClose(
                strategy.marketSlugPrefix,
                strategy.intervalSeconds,
                periodStartUnix
            )
            val openPriceBtc = openClose?.first
            var autoMinSpreadUp: BigDecimal? = null
            var autoMinSpreadDown: BigDecimal? = null
            if (strategy.spreadMode.name.uppercase() == "AUTO") {
                val autoSpreads = binanceKlineAutoSpreadService.computeAndCache(
                    strategy.marketSlugPrefix,
                    strategy.intervalSeconds,
                    periodStartUnix
                )
                autoMinSpreadUp = autoSpreads?.first
                autoMinSpreadDown = autoSpreads?.second
            }
            val priceData = StrategyPriceData(
                openPriceBtc = openPriceBtc,
                minSpreadLineUp = autoMinSpreadUp ?: strategy.spreadValue?.toSafeBigDecimal(),
                minSpreadLineDown = autoMinSpreadDown ?: strategy.spreadValue?.toSafeBigDecimal(),
                periodStartUnix = periodStartUnix
            )
            strategyPriceData[strategy.id!!] = priceData

            val response = CryptoTailMonitorInitResponse(
                strategyId = strategy.id!!,
                name = strategy.name ?: "",
                accountId = strategy.accountId,
                accountName = account?.accountName ?: "",
                marketSlugPrefix = strategy.marketSlugPrefix,
                marketTitle = event?.title ?: strategy.marketSlugPrefix,
                intervalSeconds = strategy.intervalSeconds,
                periodStartUnix = periodStartUnix,
                windowStartSeconds = strategy.windowStartSeconds,
                windowEndSeconds = strategy.windowEndSeconds,
                minPrice = strategy.minPrice.toPlainString(),
                maxPrice = strategy.maxPrice.toPlainString(),
                minSpreadMode = strategy.spreadMode.name,
                spreadDirection = strategy.spreadDirection.name,
                minSpreadValue = strategy.spreadValue?.toPlainString(),
                autoMinSpreadUp = autoMinSpreadUp?.toPlainString(),
                autoMinSpreadDown = autoMinSpreadDown?.toPlainString(),
                openPriceBtc = openPriceBtc?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
                tokenIdUp = tokenIds.getOrNull(0),
                tokenIdDown = tokenIds.getOrNull(1),
                currentTimestamp = System.currentTimeMillis(),
                enabled = strategy.enabled,
                amountMode = strategy.amountMode,
                amountValue = strategy.amountValue.toPlainString()
            )

            Result.success(response)
        } catch (e: Exception) {
            logger.error("初始化监控失败: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun subscribe(sessionId: String, strategyId: Long, callback: (CryptoTailMonitorPushData) -> Unit) {
        val count = strategySubscribers.merge(strategyId, 1) { old, inc -> old + inc } ?: 1
        webSocketSubscriptionService.registerMonitorCallback(sessionId, strategyId, callback)
        if (count == 1) {
            scope.launch {
                refreshSubscription()
            }
            startPeriodicPush()
        }
        scope.launch {
            try {
                sendCurrentData(sessionId, strategyId, callback)
            } catch (e: Exception) {
                logger.error("发送当前监控数据失败: $sessionId, ${e.message}")
            }
        }
    }

    fun unsubscribe(sessionId: String, strategyId: Long) {
        val currentCount = strategySubscribers[strategyId] ?: 0
        val newCount = (currentCount - 1).coerceAtLeast(0)

        if (newCount == 0) {
            strategySubscribers.remove(strategyId)
        } else {
            strategySubscribers[strategyId] = newCount
        }
        webSocketSubscriptionService.unregisterMonitorCallback(sessionId, strategyId)
        if (newCount == 0) {
            scope.launch {
                refreshSubscription()
            }
            stopPeriodicPush()
        }
    }

    private fun startPeriodicPush() {
        if (periodicPushJob?.isActive == true) return
        periodicPushJob = scope.launch {
            while (strategySubscribers.isNotEmpty() && strategySubscribers.values.any { (it ?: 0) > 0 }) {
                delay(pushIntervalMs)
                if (closedForNoSubscribers.get()) continue
                val ids = strategySubscribers.filter { (it.value ?: 0) > 0 }.keys.toList()
                for (strategyId in ids) {
                    try {
                        val strategy = strategyRepository.findById(strategyId).orElse(null) ?: continue
                        val priceData = strategyPriceData[strategyId] ?: continue
                        val pushData = buildPushData(strategy, priceData)
                        addToHistoryAndPush(strategyId, pushData)
                    } catch (e: Exception) {
                        logger.debug("定时推送失败 strategyId=$strategyId: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopPeriodicPush() {
        if (strategySubscribers.isEmpty() || strategySubscribers.values.all { (it ?: 0) <= 0 }) {
            periodicPushJob?.cancel()
            periodicPushJob = null
        }
    }

    private suspend fun sendCurrentData(
        sessionId: String,
        strategyId: Long,
        callback: (CryptoTailMonitorPushData) -> Unit
    ) {
        val strategy = strategyRepository.findById(strategyId).orElse(null) ?: return
        val priceData = strategyPriceData[strategyId] ?: StrategyPriceData()

        val history = strategyPushHistory[strategyId]?.let { list ->
            synchronized(list) { list.toList() }
        } ?: emptyList()
        for (item in history) {
            callback(item)
        }

        val pushData = buildPushData(strategy, priceData)
        callback(pushData)
    }

    private suspend fun refreshSubscription() {
        if (!refreshSubscriptionMutex.tryLock()) {
            return
        }
        try {
            refreshSubscriptionInternal()
        } finally {
            refreshSubscriptionMutex.unlock()
        }
    }

    private suspend fun refreshSubscriptionInternal() {
        periodEndCountdownJob?.cancel()
        periodEndCountdownJob = null

        val subscribedStrategyIds = strategySubscribers.keys.filter { (strategySubscribers[it] ?: 0) > 0 }
        if (subscribedStrategyIds.isEmpty()) {
            closeAllWebSockets()
            return
        }

        val strategies = strategyRepository.findAllById(subscribedStrategyIds).filter { it.enabled && it.id != null }
        if (strategies.isEmpty()) {
            closeAllWebSockets()
            return
        }

        val nowSeconds = System.currentTimeMillis() / 1000
        val isSwitch = currentPeriodWebSocket != null

        if (isSwitch) {
            closeCurrentPeriodWebSocket()
            currentPeriodWebSocket = nextPeriodWebSocket
            nextPeriodWebSocket = null
            val nextMap = nextPeriodTokenToStrategy.get()
            currentPeriodTokenToStrategy.set(nextMap)
            val nextPeriodByStrategy =
                nextMap.values.flatten().distinctBy { it.strategyId }.associate { it.strategyId to it.periodStartUnix }
            logger.info("周期切换：下一周期连接晋升为当前")
            for ((strategyId, periodStartUnix) in nextPeriodByStrategy) {
                updateStrategyPriceDataForPeriod(listOf(strategyId), periodStartUnix, pushDefault = true)
            }
            val (newNextTokenIds, newNextMap) = buildSubscriptionMapForNextPeriod(subscribedStrategyIds)
            nextPeriodTokenToStrategy.set(newNextMap)
            if (newNextTokenIds.isNotEmpty()) {
                connectNextPeriod(newNextTokenIds, newNextMap)
            } else {
                logger.info("下一周期市场尚未创建，仅建立空连接以便周期切换时复用")
                connectNextPeriod(emptyList(), emptyMap())
            }
            scheduleRefreshAtPeriodEnd(if (newNextMap.isNotEmpty()) newNextMap else nextMap)
        } else {
            val (currentTokenIds, currentMap) = buildSubscriptionMapForCurrentPeriod(subscribedStrategyIds)
            currentPeriodTokenToStrategy.set(currentMap)
            for (entry in currentMap.values.flatten().distinctBy { it.strategyId }) {
                updateStrategyPriceDataForPeriod(listOf(entry.strategyId), entry.periodStartUnix, pushDefault = false)
            }
            if (currentTokenIds.isEmpty()) {
                closeAllWebSockets()
                return
            }
            connectCurrentPeriod(currentTokenIds, currentMap)
            val (nextTokenIds, nextMap) = buildSubscriptionMapForNextPeriod(subscribedStrategyIds)
            nextPeriodTokenToStrategy.set(nextMap)
            if (nextTokenIds.isNotEmpty()) {
                connectNextPeriod(nextTokenIds, nextMap)
            } else {
                logger.info("下一周期市场尚未创建，先建立空连接，周期切换时会重新订阅")
                connectNextPeriod(emptyList(), emptyMap())
            }
            scheduleRefreshAtPeriodEnd(currentMap)
        }
    }

    private suspend fun buildSubscriptionMapForCurrentPeriod(strategyIds: List<Long>): Pair<List<String>, Map<String, List<MonitorEntry>>> {
        val strategies = strategyRepository.findAllById(strategyIds)
        val nowSeconds = System.currentTimeMillis() / 1000
        val tokenIdSet = mutableSetOf<String>()
        val map = mutableMapOf<String, MutableList<MonitorEntry>>()

        for (strategy in strategies) {
            if (!strategy.enabled || strategy.id == null) continue
            val strategyPeriod = (nowSeconds / strategy.intervalSeconds) * strategy.intervalSeconds
            val slug = "${strategy.marketSlugPrefix}-$strategyPeriod"
            val event = fetchEventBySlug(slug).getOrNull() ?: continue
            marketTitleByStrategyPeriod["${strategy.id!!}-$strategyPeriod"] = event.title ?: strategy.marketSlugPrefix
            val market = event.markets?.firstOrNull() ?: continue
            val tokenIds = parseClobTokenIds(market.clobTokenIds)
            if (tokenIds.size < 2) continue
            for (i in tokenIds.indices) {
                tokenIdSet.add(tokenIds[i])
                map.getOrPut(tokenIds[i]) { mutableListOf() }.add(
                    MonitorEntry(strategy.id!!, strategy, strategyPeriod, i, tokenIds[i], false)
                )
            }
        }
        return Pair(tokenIdSet.toList(), map)
    }

    private suspend fun buildSubscriptionMapForNextPeriod(strategyIds: List<Long>): Pair<List<String>, Map<String, List<MonitorEntry>>> {
        val strategies = strategyRepository.findAllById(strategyIds)
        val nowSeconds = System.currentTimeMillis() / 1000
        val tokenIdSet = mutableSetOf<String>()
        val map = mutableMapOf<String, MutableList<MonitorEntry>>()

        for (strategy in strategies) {
            if (!strategy.enabled || strategy.id == null) {
                continue
            }
            val currentPeriod = (nowSeconds / strategy.intervalSeconds) * strategy.intervalSeconds
            val nextPeriod = currentPeriod + strategy.intervalSeconds
            val slug = "${strategy.marketSlugPrefix}-$nextPeriod"
            val event = fetchEventBySlug(slug).getOrNull()
            if (event == null) {
                continue
            }
            marketTitleByStrategyPeriod["${strategy.id!!}-$nextPeriod"] = event.title ?: strategy.marketSlugPrefix
            val market = event.markets?.firstOrNull()
            if (market == null) {
                continue
            }
            val tokenIds = parseClobTokenIds(market.clobTokenIds)
            if (tokenIds.size < 2) {
                continue
            }
            for (i in tokenIds.indices) {
                tokenIdSet.add(tokenIds[i])
                map.getOrPut(tokenIds[i]) { mutableListOf() }.add(
                    MonitorEntry(strategy.id!!, strategy, nextPeriod, i, tokenIds[i], true)
                )
            }
        }
        return Pair(tokenIdSet.toList(), map)
    }

    private suspend fun updateStrategyPriceDataForPeriod(
        strategyIds: List<Long>,
        periodStartUnix: Long,
        pushDefault: Boolean
    ) {
        val strategies = strategyRepository.findAllById(strategyIds)
        for (strategy in strategies) {
            if (strategy.id == null) continue
            val openClose = binanceKlineService.getCurrentOpenClose(
                strategy.marketSlugPrefix,
                strategy.intervalSeconds,
                periodStartUnix
            )
            val openPriceBtc = openClose?.first
            var minSpreadLineUp: BigDecimal? = null
            var minSpreadLineDown: BigDecimal? = null
            when (strategy.spreadMode.name.uppercase()) {
                "FIXED" -> {
                    minSpreadLineUp = strategy.spreadValue?.toSafeBigDecimal()
                    minSpreadLineDown = strategy.spreadValue?.toSafeBigDecimal()
                }

                "AUTO" -> {
                    val autoSpreads = binanceKlineAutoSpreadService.computeAndCache(
                        strategy.marketSlugPrefix,
                        strategy.intervalSeconds,
                        periodStartUnix
                    )
                    minSpreadLineUp = autoSpreads?.first
                    minSpreadLineDown = autoSpreads?.second
                }
            }
            val existingData = strategyPriceData[strategy.id] ?: StrategyPriceData()
            val periodChanged = existingData.periodStartUnix != null && existingData.periodStartUnix != periodStartUnix
            val newData = StrategyPriceData(
                currentPriceUp = if (periodChanged && pushDefault) BigDecimal("0.5") else existingData.currentPriceUp,
                currentPriceDown = if (periodChanged && pushDefault) BigDecimal("0.5") else existingData.currentPriceDown,
                spreadUp = if (periodChanged && pushDefault) BigDecimal("0.5") else existingData.spreadUp,
                spreadDown = if (periodChanged && pushDefault) BigDecimal("0.5") else existingData.spreadDown,
                openPriceBtc = openPriceBtc,
                minSpreadLineUp = minSpreadLineUp,
                minSpreadLineDown = minSpreadLineDown,
                periodStartUnix = periodStartUnix
            )
            strategyPriceData[strategy.id!!] = newData
            if (periodChanged && pushDefault) {
                val pushData = buildPushData(strategy, newData)
                addToHistoryAndPush(strategy.id!!, pushData)
            }
        }
    }

    private fun connectCurrentPeriod(tokenIds: List<String>, map: Map<String, List<MonitorEntry>>) {
        if (currentPeriodWebSocket != null) return
        val request = Request.Builder().url(wsUrl).build()
        currentPeriodWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                closedForNoSubscribers.set(false)
                val msg = """{"type":"MARKET","assets_ids":${tokenIds.toJson()}}"""
                try {
                    webSocket.send(msg)
                    logger.info("加密价差策略监控 WebSocket（当前周期）已连接并订阅: ${tokenIds.size} 个 token")
                } catch (e: Exception) {
                    logger.warn("发送当前周期订阅失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@CryptoTailMonitorService.currentPeriodWebSocket == webSocket) {
                    this@CryptoTailMonitorService.currentPeriodWebSocket = null
                    if (!closedForNoSubscribers.get()) scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (this@CryptoTailMonitorService.currentPeriodWebSocket == webSocket) {
                    this@CryptoTailMonitorService.currentPeriodWebSocket = null
                    scheduleReconnect()
                }
            }
        })
    }

    private fun connectNextPeriod(tokenIds: List<String>, map: Map<String, List<MonitorEntry>>) {
        if (nextPeriodWebSocket != null) {
            return
        }
        val request = Request.Builder().url(wsUrl).build()
        nextPeriodWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                val msg = """{"type":"MARKET","assets_ids":${tokenIds.toJson()}}"""
                try {
                    webSocket.send(msg)
                    if (tokenIds.isEmpty()) {
                        logger.info("加密价差策略监控 WebSocket（下一周期）已连接，暂无 token 订阅，等待周期切换后更新")
                    } else {
                        logger.info("加密价差策略监控 WebSocket（下一周期）已连接并订阅: ${tokenIds.size} 个 token")
                    }
                } catch (e: Exception) {
                    logger.warn("发送下一周期订阅失败: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (this@CryptoTailMonitorService.nextPeriodWebSocket == webSocket) {
                    this@CryptoTailMonitorService.nextPeriodWebSocket = null
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (this@CryptoTailMonitorService.nextPeriodWebSocket == webSocket) {
                    this@CryptoTailMonitorService.nextPeriodWebSocket = null
                }
            }
        })
    }

    private fun closeCurrentPeriodWebSocket() {
        currentPeriodWebSocket?.close(1000, "period_ended")
        currentPeriodWebSocket = null
        logger.info("加密价差策略监控 WebSocket（当前周期）已关闭")
    }

    private fun closeAllWebSockets() {
        reconnectJob?.cancel()
        reconnectJob = null
        closedForNoSubscribers.set(true)
        currentPeriodWebSocket?.close(1000, "no_subscribers")
        currentPeriodWebSocket = null
        nextPeriodWebSocket?.close(1000, "no_subscribers")
        nextPeriodWebSocket = null
        logger.info("加密价差策略监控 WebSocket 已全部关闭（无订阅者）")
    }

    private fun handleMessage(webSocket: WebSocket, text: String) {
        if (text == "pong" || text.isEmpty()) return
        if (closedForNoSubscribers.get()) return

        maybeRefreshSubscriptionIfPeriodChanged()

        val json = text.fromJson<com.google.gson.JsonObject>() ?: return
        val eventType = (json.get("event_type") as? com.google.gson.JsonPrimitive)?.asString ?: return
        val map = currentPeriodTokenToStrategy.get()

        when (eventType) {
            "price_change" -> {
                val priceChanges = json.get("price_changes") as? com.google.gson.JsonArray ?: return
                for (i in 0 until priceChanges.size()) {
                    val pc = priceChanges.get(i) as? com.google.gson.JsonObject ?: continue
                    val assetId = (pc.get("asset_id") as? com.google.gson.JsonPrimitive)?.asString ?: continue
                    val bestBidStr = (pc.get("best_bid") as? com.google.gson.JsonPrimitive)?.asString
                    val bestBid = bestBidStr?.toSafeBigDecimal()
                    if (bestBid != null) onPriceUpdate(assetId, bestBid, map)
                }
            }
        }
    }

    private fun onPriceUpdate(tokenId: String, bestBid: BigDecimal, map: Map<String, List<MonitorEntry>>) {
        if (closedForNoSubscribers.get()) return
        val entries = map[tokenId] ?: return

        for (entry in entries) {
            val strategy = entry.strategy
            val priceData = strategyPriceData[strategy.id!!] ?: StrategyPriceData()
            val newPriceData = if (entry.outcomeIndex == 0) {
                priceData.copy(
                    currentPriceUp = bestBid,
                    currentPriceDown = BigDecimal.ONE.subtract(bestBid),
                    spreadUp = BigDecimal.ONE.subtract(bestBid),
                    spreadDown = bestBid,
                    lastUpdateTime = System.currentTimeMillis()
                )
            } else {
                priceData.copy(
                    currentPriceDown = bestBid,
                    currentPriceUp = BigDecimal.ONE.subtract(bestBid),
                    spreadUp = bestBid,
                    spreadDown = BigDecimal.ONE.subtract(bestBid),
                    lastUpdateTime = System.currentTimeMillis()
                )
            }

            strategyPriceData[strategy.id!!] = newPriceData

            val now = System.currentTimeMillis()
            val last = lastPriceChangePushTime[strategy.id!!] ?: 0L
            if (now - last >= priceChangePushThrottleMs) {
                lastPriceChangePushTime[strategy.id!!] = now
                val pushData = buildPushData(strategy, newPriceData)
                addToHistoryAndPush(strategy.id!!, pushData)
            }
        }
    }

    private fun addToHistoryAndPush(strategyId: Long, pushData: CryptoTailMonitorPushData) {
        addToHistory(strategyId, pushData)
        webSocketSubscriptionService.pushMonitorData(strategyId, pushData)
    }

    private fun addToHistory(strategyId: Long, pushData: CryptoTailMonitorPushData) {
        val list = strategyPushHistory.getOrPut(strategyId) {
            Collections.synchronizedList(mutableListOf<CryptoTailMonitorPushData>())
        }
        synchronized(list) {
            val lastPeriod = strategyHistoryPeriod[strategyId]
            if (lastPeriod != null && lastPeriod != pushData.periodStartUnix) {
                list.clear()
            }
            strategyHistoryPeriod[strategyId] = pushData.periodStartUnix
            list.add(pushData)
            while (list.size > maxHistorySize) {
                list.removeAt(0)
            }
        }
    }

    private fun buildPushData(strategy: CryptoTailStrategy, priceData: StrategyPriceData): CryptoTailMonitorPushData {
        val nowSeconds = System.currentTimeMillis() / 1000
        val periodStartUnix = (nowSeconds / strategy.intervalSeconds) * strategy.intervalSeconds
        val periodEndUnix = periodStartUnix + strategy.intervalSeconds
        val remainingSeconds = (periodEndUnix - nowSeconds).toInt().coerceAtLeast(0)

        val windowStart = periodStartUnix + strategy.windowStartSeconds
        val windowEnd = periodStartUnix + strategy.windowEndSeconds
        val inTimeWindow = nowSeconds >= windowStart && nowSeconds < windowEnd
        val openClose = binanceKlineService.getCurrentOpenClose(
            strategy.marketSlugPrefix,
            strategy.intervalSeconds,
            periodStartUnix
        )
        val openPriceBtc = priceData.openPriceBtc ?: openClose?.first
        val currentPriceBtc = openClose?.second
        if (openPriceBtc != null && priceData.openPriceBtc == null && strategy.id != null) {
            strategyPriceData[strategy.id] = priceData.copy(openPriceBtc = openPriceBtc)
        }
        val spreadBtc = if (openPriceBtc != null && currentPriceBtc != null) {
            currentPriceBtc.subtract(openPriceBtc)
        } else null
        val currentUp = priceData.currentPriceUp
        val currentDown = priceData.currentPriceDown
        val inPriceRangeUp = currentUp != null &&
                currentUp >= strategy.minPrice && currentUp <= strategy.maxPrice
        val inPriceRangeDown = currentDown != null &&
                currentDown >= strategy.minPrice && currentDown <= strategy.maxPrice

        val marketTitle = marketTitleByStrategyPeriod["${strategy.id!!}-$periodStartUnix"] ?: strategy.marketSlugPrefix

        return CryptoTailMonitorPushData(
            strategyId = strategy.id!!,
            timestamp = System.currentTimeMillis(),
            periodStartUnix = periodStartUnix,
            marketTitle = marketTitle,
            currentPriceUp = priceData.currentPriceUp?.setScale(4, RoundingMode.HALF_UP)?.toPlainString(),
            currentPriceDown = priceData.currentPriceDown?.setScale(4, RoundingMode.HALF_UP)?.toPlainString(),
            spreadUp = priceData.spreadUp?.setScale(4, RoundingMode.HALF_UP)?.toPlainString(),
            spreadDown = priceData.spreadDown?.setScale(4, RoundingMode.HALF_UP)?.toPlainString(),
            minSpreadLineUp = priceData.minSpreadLineUp?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            minSpreadLineDown = priceData.minSpreadLineDown?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            openPriceBtc = openPriceBtc?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            currentPriceBtc = currentPriceBtc?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            spreadBtc = spreadBtc?.setScale(2, RoundingMode.HALF_UP)?.toPlainString(),
            remainingSeconds = remainingSeconds,
            inTimeWindow = inTimeWindow,
            inPriceRangeUp = inPriceRangeUp,
            inPriceRangeDown = inPriceRangeDown,
            triggered = priceData.triggered,
            triggerDirection = priceData.triggerDirection,
            periodEnded = remainingSeconds <= 0
        )
    }

    private fun maybeRefreshSubscriptionIfPeriodChanged() {
        val subscribed = currentPeriodTokenToStrategy.get().values.flatten().distinctBy { it.strategyId }
            .associate { it.strategyId to it.periodStartUnix }
        if (subscribed.isEmpty()) return

        val strategies = strategyRepository.findAllById(subscribed.keys)
        val nowSeconds = System.currentTimeMillis() / 1000

        for (s in strategies) {
            if (s.id == null) continue
            val currentPeriod = (nowSeconds / s.intervalSeconds) * s.intervalSeconds
            val subPeriod = subscribed[s.id] ?: continue
            if (currentPeriod != subPeriod) {
                scope.launch { refreshSubscription() }
                return
            }
        }
    }

    private fun scheduleRefreshAtPeriodEnd(newMap: Map<String, List<MonitorEntry>>) {
        val entries = newMap.values.flatten()
        if (entries.isEmpty()) return

        val nextPeriodEndSeconds = entries.minOf { it.periodStartUnix + it.strategy.intervalSeconds }
        val delayMs = (nextPeriodEndSeconds * 1000) - System.currentTimeMillis() + 2000
        if (delayMs <= 0) return

        periodEndCountdownJob = scope.launch {
            delay(delayMs)
            periodEndCountdownJob = null
            refreshSubscription()
        }
    }

    private fun closeWebSocketForNoSubscribers() {
        closeAllWebSockets()
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectJob = null
            if (strategySubscribers.isNotEmpty()) {
                logger.info("加密价差策略监控 WebSocket 尝试重连")
                refreshSubscription()
            }
        }
    }

    private fun fetchEventBySlug(slug: String): Result<GammaEventBySlugResponse> {
        return try {
            val api = retrofitFactory.createGammaApi()
            val response = runBlocking { api.getEventBySlug(slug) }
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseClobTokenIds(clobTokenIds: String?): List<String> {
        if (clobTokenIds.isNullOrBlank()) return emptyList()
        return clobTokenIds.fromJson<List<String>>() ?: emptyList()
    }

    @PreDestroy
    fun destroy() {
        reconnectJob?.cancel()
        periodEndCountdownJob?.cancel()
        periodicPushJob?.cancel()
        currentPeriodWebSocket?.close(1000, "shutdown")
        currentPeriodWebSocket = null
        nextPeriodWebSocket?.close(1000, "shutdown")
        nextPeriodWebSocket = null
    }
}
