package com.wrbug.polymarketbot.service.accounts

import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.dto.PositionSellRequest
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.entity.LeaderCopyTradingControl
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.LeaderCopyTradingControlRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.LeaderProfitTakeEvaluator
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import com.wrbug.polymarketbot.util.multi
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import com.wrbug.polymarketbot.service.system.SystemConfigService
import com.wrbug.polymarketbot.service.system.RelayClientService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.service.common.MarketPriceService
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

@Service
class PositionCheckService(
    private val positionPollingService: PositionPollingService,
    private val accountService: AccountService,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val leaderCopyTradingControlRepository: LeaderCopyTradingControlRepository,
    private val systemConfigService: SystemConfigService,
    private val relayClientService: RelayClientService,
    private val telegramNotificationService: TelegramNotificationService?,
    private val accountRepository: AccountRepository,
    private val messageSource: MessageSource,
    private val marketPriceService: MarketPriceService,
    private val leaderProfitTakeEvaluator: LeaderProfitTakeEvaluator,
    private val positionSettlementWriter: PositionSettlementWriter
) {
    
    private val logger = LoggerFactory.getLogger(PositionCheckService::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var subscriptionJob: Job? = null
    private val notifiedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastNotificationTime
    private val processedRedeemablePositions = ConcurrentHashMap<String, Long>()  // "accountId_marketId_outcomeIndex" -> lastProcessTime
    private val notifiedConfigs = ConcurrentHashMap<Long, Long>()  // accountId/copyTradingId -> lastNotificationTime
    // key: "accountId_marketId_outcomeIndex_copyTradingId"
    private data class PendingPositionCheck(
        val accountId: Long,
        val marketId: String,
        val outcomeIndex: Int,
        val copyTradingId: Long,
        val orders: List<CopyOrderTracking>,
        val firstDetectedTime: Long
    )
    private val pendingPositionChecks = ConcurrentHashMap<String, PendingPositionCheck>()

    private val pendingProfitTakeSellRequests = ConcurrentHashMap<String, Long>()
    private val lock = Any()
    private val redeemCheckInProgress = AtomicBoolean(false)

    @PostConstruct
    fun init() {
        logger.info("PositionCheckService started")
        startSubscription()
        startCacheCleanup()
        startPendingPositionCheckTask()
    }
    
    @PreDestroy
    fun destroy() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = null
        }
        scope.cancel()
    }
    
    private fun startSubscription() {
        synchronized(lock) {
            subscriptionJob?.cancel()
            subscriptionJob = scope.launch(Dispatchers.IO) {
                try {
                    positionPollingService.subscribe { positions ->
                        scope.launch(Dispatchers.IO) {
                            try {
                                checkPositions(positions.currentPositions)
                            } catch (e: Exception) {
                                logger.error("Failed to process position check event: {}", e.message, e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to record position snapshot: ${e.message}", e)
                }
            }
        }
    }
    
    private fun startCacheCleanup() {
        scope.launch {
            while (isActive) {
                try {
                    delay(7200000)
                    cleanupExpiredCache()
                } catch (e: Exception) {
                    logger.error("Failed to clean expired cache: {}", e.message, e)
                }
            }
        }
    }
    
    private fun startPendingPositionCheckTask() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    delay(30000)
                } catch (e: Exception) {
                    logger.error("Failed to check pending positions: {}", e.message, e)
                }
            }
        }
    }

    internal suspend fun loadCurrentPositionsByAccountAndMarket(
        accountIds: Set<Long>
    ): Map<String, List<AccountPositionDto>> {
        if (accountIds.isEmpty()) {
            return emptyMap()
        }

        val currentPositions = supervisorScope {
            accountIds.map { accountId ->
                async {
                    accountService.getCurrentPositionsForAccount(accountId).fold(
                        onSuccess = { positions -> positions },
                        onFailure = { error ->
                            logger.warn("Failed to load current positions for account: accountId=$accountId, ${error.message}")
                            emptyList<AccountPositionDto>()
                        }
                    )
                }
            }.awaitAll().flatten()
        }

        return currentPositions.groupBy {
            "${it.accountId}_${it.marketId}_${it.outcomeIndex ?: 0}"
        }
    }
    
    private suspend fun checkPendingPositions() {
        if (pendingPositionChecks.isEmpty()) {
            return
        }

        try {
            val pendingEntries = pendingPositionChecks.entries.toList()
            val positionsByAccountAndMarket = loadCurrentPositionsByAccountAndMarket(
                pendingEntries.map { it.value.accountId }.toSet()
            )
            val currentOrdersById = copyOrderTrackingRepository
                .findAllById(pendingEntries.flatMap { it.value.orders }.mapNotNull { it.id }.toSet())
                .associateBy { it.id!! }

            val now = System.currentTimeMillis()
            val checkDelay = 180000L
            val toRemove = mutableListOf<String>()
            val toMarkAsSold = mutableListOf<PendingPositionCheck>()
            val currentPriceCache = mutableMapOf<String, BigDecimal>()

            for ((key, pendingCheck) in pendingEntries) {
                val validOrders = pendingCheck.orders.mapNotNull { order ->
                    order.id?.let(currentOrdersById::get)
                }.filter { currentOrder ->
                    currentOrder.remainingQuantity > BigDecimal.ZERO
                }

                if (validOrders.isEmpty()) {
                    toRemove.add(key)
                    logger.info("All orders for the pending position have been processed, removing record: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}")
                    continue
                }

                val positionKey = "${pendingCheck.accountId}_${pendingCheck.marketId}_${pendingCheck.outcomeIndex}"
                val position = positionsByAccountAndMarket[positionKey]?.firstOrNull()

                if (position != null) {
                    toRemove.add(key)
                    logger.info("Pending position has recovered, removing record: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${now - pendingCheck.firstDetectedTime}ms")
                } else {
                    val elapsedTime = now - pendingCheck.firstDetectedTime
                    if (elapsedTime >= checkDelay) {
                        toMarkAsSold.add(pendingCheck.copy(orders = validOrders))
                        toRemove.add(key)
                        logger.info("Pending position is still missing after the delay window, marking as sold: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${elapsedTime}ms, validOrderCount=${validOrders.size}, originalOrderCount=${pendingCheck.orders.size}")
                    } else {
                        if (validOrders.size < pendingCheck.orders.size) {
                            pendingPositionChecks[key] = pendingCheck.copy(orders = validOrders)
                            logger.debug("Updated pending position record after removing processed orders: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, validOrderCount=${validOrders.size}, originalOrderCount=${pendingCheck.orders.size}")
                        }
                        logger.debug("Pending position is still missing, continue waiting: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, accountId=${pendingCheck.accountId}, copyTradingId=${pendingCheck.copyTradingId}, elapsedTime=${elapsedTime}ms, remainingTime=${checkDelay - elapsedTime}ms")
                    }
                }
            }

            toRemove.forEach { key ->
                pendingPositionChecks.remove(key)
            }

            for (pendingCheck in toMarkAsSold) {
                try {
                    val priceCacheKey = "${pendingCheck.marketId}_${pendingCheck.outcomeIndex}"
                    val currentPrice = currentPriceCache[priceCacheKey]
                        ?: getCurrentMarketPrice(pendingCheck.marketId, pendingCheck.outcomeIndex).also {
                            currentPriceCache[priceCacheKey] = it
                        }
                    updateOrdersAsSold(
                        pendingCheck.orders,
                        currentPrice,
                        pendingCheck.copyTradingId,
                        pendingCheck.marketId,
                        pendingCheck.outcomeIndex
                    )
                } catch (e: Exception) {
                    logger.error("Failed to mark pending position as sold: marketId=${pendingCheck.marketId}, outcomeIndex=${pendingCheck.outcomeIndex}, error=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to process pending position checks: ${e.message}", e)
        }
    }
    private fun cleanupExpiredCache() {
    val now = System.currentTimeMillis()
    val expireTime = 7200000  // 2??

    val expiredPositions = notifiedRedeemablePositions.entries.filter { (_, timestamp) ->
        (now - timestamp) > expireTime
    }
    expiredPositions.forEach { (key, _) ->
        notifiedRedeemablePositions.remove(key)
    }

    val expiredProcessed = processedRedeemablePositions.entries.filter { (_, timestamp) ->
        (now - timestamp) > expireTime
    }
    expiredProcessed.forEach { (key, _) ->
        processedRedeemablePositions.remove(key)
    }

    val expiredConfigs = notifiedConfigs.entries.filter { (_, timestamp) ->
        (now - timestamp) > expireTime
    }
    expiredConfigs.forEach { (key, _) ->
        notifiedConfigs.remove(key)
    }

    val expiredPendingChecks = pendingPositionChecks.entries.filter { (_, check) ->
        (now - check.firstDetectedTime) > 3600000
    }
    expiredPendingChecks.forEach { (key, _) ->
        pendingPositionChecks.remove(key)
    }

    val expiredProfitTakeRequests = pendingProfitTakeSellRequests.entries.filter { (_, timestamp) ->
        (now - timestamp) > PROFIT_TAKE_SELL_COOLDOWN_MILLIS
    }
    expiredProfitTakeRequests.forEach { (key, _) ->
        pendingProfitTakeSellRequests.remove(key)
    }

    if (
        expiredPositions.isNotEmpty() ||
        expiredProcessed.isNotEmpty() ||
        expiredConfigs.isNotEmpty() ||
        expiredPendingChecks.isNotEmpty() ||
        expiredProfitTakeRequests.isNotEmpty()
    ) {
        logger.debug(
            "Expired cache cleanup: positions=${expiredPositions.size}, processed=${expiredProcessed.size}, configs=${expiredConfigs.size}, pendingChecks=${expiredPendingChecks.size}, profitTakeRequests=${expiredProfitTakeRequests.size}"
        )
    }
}

    suspend fun checkPositions(currentPositions: List<AccountPositionDto>) {
        try {
            val redeemablePositions = currentPositions.filter { it.redeemable }
            if (redeemablePositions.isNotEmpty()) {
                checkRedeemablePositions(redeemablePositions)
            }
            checkUnmatchedOrders(currentPositions)
        } catch (e: Exception) {
            logger.error("Position check failed: {}", e.message, e)
        }
    }
    
    private suspend fun checkRedeemablePositions(redeemablePositions: List<AccountPositionDto>) {
        if (!redeemCheckInProgress.compareAndSet(false, true)) {
            logger.debug("Redeem check is already in progress, skipping this cycle")
            return
        }
        try {
            if (redeemablePositions.isEmpty()) {
                return
            }

            val positionsByAccount = redeemablePositions.groupBy { it.accountId }
            val quotaBlocked = relayClientService.isBuilderRelayerQuotaBlocked()
            val quotaRemaining = relayClientService.getBuilderRelayerQuotaBlockedRemainingSeconds()

            for ((accountId, positions) in positionsByAccount) {
                val account = accountRepository.findById(accountId).orElse(null)
                if (account == null) {
                    logger.warn("Skipped auto redeem because the account does not exist: accountId=$accountId")
                    continue
                }

                val walletType = com.wrbug.polymarketbot.enums.WalletType.fromStringOrDefault(
                    account.walletType,
                    com.wrbug.polymarketbot.enums.WalletType.SAFE
                )

                if (!account.autoRedeemEnabled) {
                    for (position in positions) {
                        val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                        val lastNotification = notifiedRedeemablePositions[positionKey]
                        val now = System.currentTimeMillis()
                        if (lastNotification == null || (now - lastNotification) >= 7200000) {
                            checkAndNotifyAutoRedeemDisabled(accountId, listOf(position))
                            notifiedRedeemablePositions[positionKey] = now
                        }
                    }
                    continue
                }

                if (walletType == com.wrbug.polymarketbot.enums.WalletType.MAGIC && !accountService.hasBuilderConfig(account)) {
                    for (position in positions) {
                        val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                        val lastNotification = notifiedRedeemablePositions[positionKey]
                        val now = System.currentTimeMillis()
                        if (lastNotification == null || (now - lastNotification) >= 7200000) {
                            val copyTradings = copyTradingRepository.findByAccountId(accountId).filter { it.enabled }
                            for (copyTrading in copyTradings) {
                                checkAndNotifyBuilderApiKeyNotConfigured(copyTrading, listOf(position))
                            }
                            notifiedRedeemablePositions[positionKey] = now
                        }
                    }
                    continue
                }

                if (walletType == com.wrbug.polymarketbot.enums.WalletType.MAGIC && quotaBlocked) {
                    logger.info("Builder Relayer quota is cooling down, skipping auto redeem for Magic account: accountId=$accountId, remaining=${quotaRemaining}s")
                    continue
                }

                val copyTradings = copyTradingRepository.findByAccountId(accountId)
                    .filter { it.enabled }

                val now = System.currentTimeMillis()
                val positionsToRedeem = positions.filter { position ->
                    val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                    val lastProcessed = processedRedeemablePositions[positionKey]
                    if (lastProcessed != null && (now - lastProcessed) < 1800000) {
                        logger.debug("跳过已处理的赎回仓位: $positionKey (上次处理时间: ${lastProcessed})")
                        false
                    } else {
                        true
                    }
                }

                if (positionsToRedeem.isEmpty()) {
                    logger.debug("All positions have already been processed, skipping redeem: accountId=$accountId")
                    continue
                }

                val redeemRequest = com.wrbug.polymarketbot.dto.PositionRedeemRequest(
                    positions = positionsToRedeem.map { position ->
                        com.wrbug.polymarketbot.dto.AccountRedeemPositionItem(
                            accountId = accountId,
                            marketId = position.marketId,
                            outcomeIndex = position.outcomeIndex ?: 0,
                            side = position.side
                        )
                    }
                )

                val redeemResult = accountService.redeemPositions(redeemRequest)
                redeemResult.fold(
                    onSuccess = { response ->
                        logger.info("自动赎回成功: accountId=$accountId, redeemedCount=${positionsToRedeem.size}, totalValue=${response.totalRedeemedValue}")

                        for (position in positionsToRedeem) {
                            val positionKey = "${accountId}_${position.marketId}_${position.outcomeIndex ?: 0}"
                            processedRedeemablePositions[positionKey] = now
                        }

                        for (position in positionsToRedeem) {
                            if (position.outcomeIndex == null) {
                                continue
                            }
                            for (copyTrading in copyTradings) {
                                val copyTradingId = copyTrading.id ?: continue
                                val orders = copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndex(
                                    copyTradingId,
                                    position.marketId,
                                    position.outcomeIndex
                                )
                                if (orders.isNotEmpty()) {
                                    updateOrdersAsSoldAfterRedeem(orders, position, copyTradingId)
                                }
                            }
                        }
                    },
                    onFailure = { e ->
                        logger.error("Auto redeem failed: accountId=$accountId, error=${e.message}", e)
                    }
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error while processing pending redeem positions: ${e.message}", e)
        } finally {
            redeemCheckInProgress.set(false)
        }
    }
    private suspend fun checkUnmatchedOrders(currentPositions: List<AccountPositionDto>) {
        try {
            val activeCopyTradingIds = copyOrderTrackingRepository.findDistinctActiveCopyTradingIds()
            if (activeCopyTradingIds.isEmpty()) {
                return
            }

            val copyTradingsById = copyTradingRepository.findAllById(activeCopyTradingIds)
                .asSequence()
                .filter { it.enabled }
                .mapNotNull { copyTrading -> copyTrading.id?.let { id -> id to copyTrading } }
                .toMap()
            if (copyTradingsById.isEmpty()) {
                return
            }

            val activeOrders = copyOrderTrackingRepository.findActiveOrdersByCopyTradingIdIn(copyTradingsById.keys.toList())
            if (activeOrders.isEmpty()) {
                return
            }

            val activeOrdersByCopyTradingId = activeOrders.groupBy { it.copyTradingId }
            val controlsByLeaderId = leaderCopyTradingControlRepository
                .findByLeaderIdIn(activeOrdersByCopyTradingId.keys.mapNotNull { copyTradingsById[it]?.leaderId }.distinct())
                .associateBy { it.leaderId }
            val positionsByAccountAndMarket = currentPositions.groupBy {
                "${it.accountId}_${it.marketId}_${it.outcomeIndex ?: 0}"
            }
            val currentPriceCache = mutableMapOf<String, BigDecimal>()

            for ((copyTradingId, unmatchedOrders) in activeOrdersByCopyTradingId) {
                val copyTrading = copyTradingsById[copyTradingId] ?: continue
                val ordersByMarket = unmatchedOrders.groupBy {
                    "${it.marketId}_${it.outcomeIndex ?: 0}"
                }

                for ((marketKey, orders) in ordersByMarket) {
                    val firstOrder = orders.firstOrNull() ?: continue
                    val marketId = firstOrder.marketId
                    val outcomeIndex = firstOrder.outcomeIndex ?: 0
                    val positionKey = "${copyTrading.accountId}_$marketKey"
                    val position = positionsByAccountAndMarket[positionKey]?.firstOrNull()
                    val now = System.currentTimeMillis()
                    val thresholdTime = now - 120000
                    val validOrders = orders.filter { it.createdAt < thresholdTime && it.remainingQuantity > BigDecimal.ZERO }

                    if (position == null) {
                        clearProfitTakeCooldown(copyTradingId, marketId, outcomeIndex)
                        if (validOrders.isNotEmpty()) {
                            val checkKey = "${copyTrading.accountId}_${marketId}_${outcomeIndex}_${copyTradingId}"
                            val existingCheck = pendingPositionChecks[checkKey]
                            if (existingCheck == null) {
                                pendingPositionChecks[checkKey] = PendingPositionCheck(
                                    accountId = copyTrading.accountId,
                                    marketId = marketId,
                                    outcomeIndex = outcomeIndex,
                                    copyTradingId = copyTradingId,
                                    orders = validOrders,
                                    firstDetectedTime = now
                                )
                                logger.info("Created pending position record: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=$copyTradingId, orderCount=${validOrders.size}, positionKey=$positionKey")
                            } else {
                                pendingPositionChecks[checkKey] = existingCheck.copy(orders = validOrders)
                                logger.debug("Refreshed pending position record: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=$copyTradingId, orderCount=${validOrders.size}, elapsedTime=${now - existingCheck.firstDetectedTime}ms")
                            }
                        } else {
                            logger.debug("Position is still missing but orders are inside the protection window: marketId=$marketId, outcomeIndex=$outcomeIndex, orderCount=${orders.size}, thresholdTime=$thresholdTime, positionKey=$positionKey")
                        }
                        continue
                    }

                    val checkKey = "${copyTrading.accountId}_${marketId}_${outcomeIndex}_${copyTradingId}"
                    val pendingCheck = pendingPositionChecks.remove(checkKey)
                    if (pendingCheck != null) {
                        logger.info("仓位已恢复，移除待检查记录: marketId=$marketId, outcomeIndex=$outcomeIndex, accountId=${copyTrading.accountId}, copyTradingId=$copyTradingId, elapsedTime=${System.currentTimeMillis() - pendingCheck.firstDetectedTime}ms")
                    }

                    val liveOrders = orders.filter { it.remainingQuantity > BigDecimal.ZERO }
                    if (liveOrders.isEmpty()) {
                        clearProfitTakeCooldown(copyTradingId, marketId, outcomeIndex)
                        continue
                    }

                    val priceCacheKey = "${marketId}_$outcomeIndex"
                    val currentPrice = currentPriceCache[priceCacheKey] ?: try {
                        getCurrentMarketPrice(marketId, outcomeIndex).also { currentPriceCache[priceCacheKey] = it }
                    } catch (e: Exception) {
                        logger.warn("Failed to get latest market price: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
                        continue
                    }

                    triggerProfitTakeIfNeeded(
                        copyTrading = copyTrading,
                        control = controlsByLeaderId[copyTrading.leaderId],
                        copyTradingId = copyTradingId,
                        position = position,
                        marketId = marketId,
                        outcomeIndex = outcomeIndex,
                        orders = liveOrders,
                        currentPrice = currentPrice
                    )

                    if (validOrders.isEmpty()) {
                        logger.debug("Current position is normal, but orders are still inside the protection window, skipping FIFO calculation: marketId=$marketId, outcomeIndex=$outcomeIndex, thresholdTime=$thresholdTime")
                        continue
                    }

                    val positionQuantity = position.quantity.toSafeBigDecimal()
                    val totalOrderQuantity = validOrders.fold(BigDecimal.ZERO) { sum, order ->
                        sum.add(order.remainingQuantity.toSafeBigDecimal())
                    }
                    val soldQuantity = totalOrderQuantity.subtract(positionQuantity)

                    if (soldQuantity <= BigDecimal.ZERO) {
                        continue
                    }

                    updateOrdersAsSoldByFIFO(validOrders, soldQuantity, currentPrice, copyTradingId, marketId, outcomeIndex)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to compare positions with open orders: ${e.message}", e)
        }
    }

    private suspend fun getCurrentMarketPrice(marketId: String, outcomeIndex: Int): BigDecimal {
        return marketPriceService.getCurrentMarketPrice(marketId, outcomeIndex)
    }

    private suspend fun triggerProfitTakeIfNeeded(
        copyTrading: CopyTrading,
        control: LeaderCopyTradingControl?,
        copyTradingId: Long,
        position: AccountPositionDto,
        marketId: String,
        outcomeIndex: Int,
        orders: List<CopyOrderTracking>,
        currentPrice: BigDecimal
    ) {
        val requestKey = buildProfitTakeRequestKey(copyTradingId, marketId, outcomeIndex)
        val remainingOrderQuantity = orders.fold(BigDecimal.ZERO) { sum, order ->
            sum.add(order.remainingQuantity.toSafeBigDecimal())
        }
        val positionQuantity = position.quantity.toSafeBigDecimal()
        val sellableQuantity = minOf(remainingOrderQuantity, positionQuantity)

        if (sellableQuantity <= BigDecimal.ZERO) {
            pendingProfitTakeSellRequests.remove(requestKey)
            return
        }

        val triggerPrice = control?.profitTakePrice ?: DEFAULT_PROFIT_TAKE_PRICE
        val decision = leaderProfitTakeEvaluator.evaluate(
            enabled = control?.profitTakeEnabled ?: true,
            triggerPrice = triggerPrice,
            currentPrice = currentPrice,
            remainingQuantity = sellableQuantity
        )

        if (!decision.shouldSell) {
            pendingProfitTakeSellRequests.remove(requestKey)
            return
        }

        val now = System.currentTimeMillis()
        val lastTriggeredAt = pendingProfitTakeSellRequests[requestKey]
        if (lastTriggeredAt != null && (now - lastTriggeredAt) < PROFIT_TAKE_SELL_COOLDOWN_MILLIS) {
            return
        }

        accountService.sellPosition(
            PositionSellRequest(
                accountId = copyTrading.accountId,
                marketId = marketId,
                side = position.side,
                outcomeIndex = position.outcomeIndex ?: outcomeIndex,
                orderType = "MARKET",
                quantity = decision.sellQuantity.toPlainString()
            )
        ).fold(
            onSuccess = {
                pendingProfitTakeSellRequests[requestKey] = now
                logger.info(
                    "Triggered take-profit sell: copyTradingId=$copyTradingId, leaderId=${copyTrading.leaderId}, marketId=$marketId, outcomeIndex=$outcomeIndex, triggerPrice=$triggerPrice, currentPrice=$currentPrice, quantity=${decision.sellQuantity}"
                )
            },
            onFailure = { error ->
                logger.warn(
                    "Take-profit sell failed: copyTradingId=$copyTradingId, marketId=$marketId, outcomeIndex=$outcomeIndex, error=${error.message}"
                )
            }
        )
    }

    private fun clearProfitTakeCooldown(copyTradingId: Long, marketId: String, outcomeIndex: Int) {
        pendingProfitTakeSellRequests.remove(buildProfitTakeRequestKey(copyTradingId, marketId, outcomeIndex))
    }

    private fun buildProfitTakeRequestKey(copyTradingId: Long, marketId: String, outcomeIndex: Int): String {
        return "${copyTradingId}_${marketId}_${outcomeIndex}"
    }

    private suspend fun updateOrdersAsSoldAfterRedeem(
        orders: List<CopyOrderTracking>,
        position: AccountPositionDto,
        copyTradingId: Long
    ) {
        try {
            val currentPrice = getCurrentMarketPrice(position.marketId, position.outcomeIndex ?: 0)
            updateOrdersAsSold(orders, currentPrice, copyTradingId, position.marketId, position.outcomeIndex ?: 0)
        } catch (e: Exception) {
            logger.error("Failed to mark orders as sold after redeem: ${e.message}", e)
        }
    }
    
    private suspend fun updateOrdersAsSold(
        orders: List<CopyOrderTracking>,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ) {
        if (orders.isEmpty()) {
            return
        }

        try {
            val orderMatches = orders.mapNotNull { order ->
                val remainingQty = order.remainingQuantity.toSafeBigDecimal()
                if (remainingQty <= BigDecimal.ZERO) {
                    return@mapNotNull null
                }

                val buyPrice = order.price.toSafeBigDecimal()
                val realizedPnl = sellPrice.subtract(buyPrice).multi(remainingQty)
                OrderSettlementMatch(
                    trackingId = order.id!!,
                    buyOrderId = order.buyOrderId,
                    matchedQuantity = remainingQty,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    realizedPnl = realizedPnl,
                    newMatchedQuantity = order.matchedQuantity.add(remainingQty),
                    newRemainingQuantity = BigDecimal.ZERO,
                    newStatus = "fully_matched",
                    updatedAt = System.currentTimeMillis()
                )
            }

            if (orderMatches.isNotEmpty()) {
                positionSettlementWriter.persistSettlement(
                    copyTradingId = copyTradingId,
                    marketId = marketId,
                    outcomeIndex = outcomeIndex,
                    sellPrice = sellPrice,
                    orderMatches = orderMatches,
                    sellOrderPrefix = "AUTO",
                    leaderTradePrefix = "AUTO"
                )

                val totalMatchedQuantity = orderMatches.fold(BigDecimal.ZERO) { total, match ->
                    total.add(match.matchedQuantity)
                }
                val totalRealizedPnl = orderMatches.fold(BigDecimal.ZERO) { total, match ->
                    total.add(match.realizedPnl)
                }
                logger.info("Persisted auto settlement: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
            }
        } catch (e: Exception) {
            logger.error("Failed to update orders as sold: ${e.message}", e)
        }
    }
    
    private suspend fun updateOrdersAsSoldByFIFO(
        orders: List<CopyOrderTracking>,
        soldQuantity: BigDecimal,
        sellPrice: BigDecimal,
        copyTradingId: Long,
        marketId: String,
        outcomeIndex: Int
    ) {
        if (orders.isEmpty()) {
            return
        }

        try {
            var remaining = soldQuantity
            val orderMatches = mutableListOf<OrderSettlementMatch>()

            for (order in orders) {
                if (remaining <= BigDecimal.ZERO) {
                    break
                }

                val orderRemaining = order.remainingQuantity.toSafeBigDecimal()
                val toMatch = minOf(orderRemaining, remaining)
                if (toMatch <= BigDecimal.ZERO) {
                    continue
                }

                val buyPrice = order.price.toSafeBigDecimal()
                val realizedPnl = sellPrice.subtract(buyPrice).multi(toMatch)
                val newRemainingQuantity = order.remainingQuantity.subtract(toMatch)
                orderMatches.add(
                    OrderSettlementMatch(
                        trackingId = order.id!!,
                        buyOrderId = order.buyOrderId,
                        matchedQuantity = toMatch,
                        buyPrice = buyPrice,
                        sellPrice = sellPrice,
                        realizedPnl = realizedPnl,
                        newMatchedQuantity = order.matchedQuantity.add(toMatch),
                        newRemainingQuantity = newRemainingQuantity,
                        newStatus = if (newRemainingQuantity <= BigDecimal.ZERO) "fully_matched" else "partially_matched",
                        updatedAt = System.currentTimeMillis()
                    )
                )

                remaining = remaining.subtract(toMatch)
                logger.info("Recorded FIFO sell match: orderId=${order.buyOrderId}, matched=$toMatch, remaining=$newRemainingQuantity")
            }

            if (orderMatches.isNotEmpty()) {
                positionSettlementWriter.persistSettlement(
                    copyTradingId = copyTradingId,
                    marketId = marketId,
                    outcomeIndex = outcomeIndex,
                    sellPrice = sellPrice,
                    orderMatches = orderMatches,
                    sellOrderPrefix = "AUTO_FIFO",
                    leaderTradePrefix = "AUTO_FIFO"
                )

                val totalMatchedQuantity = orderMatches.fold(BigDecimal.ZERO) { total, match ->
                    total.add(match.matchedQuantity)
                }
                val totalRealizedPnl = orderMatches.fold(BigDecimal.ZERO) { total, match ->
                    total.add(match.realizedPnl)
                }
                logger.info("Persisted FIFO auto settlement: copyTradingId=$copyTradingId, marketId=$marketId, totalMatched=$totalMatchedQuantity, totalPnl=$totalRealizedPnl")
            }
        } catch (e: Exception) {
            logger.error("Failed to update orders as sold by FIFO: ${e.message}", e)
        }
    }
    
    private suspend fun checkAndNotifyAutoRedeemDisabled(accountId: Long, positions: List<AccountPositionDto>) {
        if (telegramNotificationService == null) {
            return
        }
        val lastNotification = notifiedConfigs[accountId]
        val now = System.currentTimeMillis()
        if (lastNotification != null && (now - lastNotification) < 7200000) {
            return
        }
        
        try {
            val account = accountRepository.findById(accountId).orElse(null)
            if (account == null) {
                return
            }
            val totalValue = positions.fold(BigDecimal.ZERO) { sum, pos ->
                sum.add(pos.quantity.toSafeBigDecimal())
            }
            
            val message = buildAutoRedeemDisabledMessage(
                accountName = account.accountName,
                walletAddress = account.walletAddress,
                totalValue = totalValue.toPlainString(),
                positionCount = positions.size
            )
            
            telegramNotificationService.sendMessage(message)
            notifiedConfigs[accountId] = now
        } catch (e: Exception) {
            logger.error("Failed to send account notification: accountId=$accountId, ${e.message}", e)
        }
    }
    
    private suspend fun checkAndNotifyBuilderApiKeyNotConfigured(
        copyTrading: CopyTrading,
        positions: List<AccountPositionDto>
    ) {
        if (telegramNotificationService == null) {
            return
        }
        val copyTradingId = copyTrading.id ?: return
        val lastNotification = notifiedConfigs[copyTradingId]
        val now = System.currentTimeMillis()
        if (lastNotification != null && (now - lastNotification) < 7200000) {
            return
        }
        
        try {
            val account = accountRepository.findById(copyTrading.accountId).orElse(null)
            if (account == null) {
                return
            }
            val totalValue = positions.fold(BigDecimal.ZERO) { sum, pos ->
                sum.add(pos.quantity.toSafeBigDecimal())
            }
            
            val message = buildBuilderApiKeyNotConfiguredMessage(
                accountName = account.accountName,
                walletAddress = account.walletAddress,
                configName = copyTrading.configName,
                totalValue = totalValue.toPlainString(),
                positionCount = positions.size
            )
            
            telegramNotificationService.sendMessage(message)
            notifiedConfigs[copyTradingId] = now
        } catch (e: Exception) {
            logger.error("Failed to send Builder API Key notification: copyTradingId=$copyTradingId, ${e.message}", e)
        }
    }
    
    private fun buildAutoRedeemDisabledMessage(
        accountName: String?,
        walletAddress: String?,
        totalValue: String,
        positionCount: Int
    ): String {
        val locale = try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            java.util.Locale("zh", "CN")
        }
        
        val accountInfo = accountName ?: (walletAddress?.let { maskAddress(it) } ?: messageSource.getMessage("common.unknown", null, "Unknown", locale))
        val totalValueDisplay = try {
            val totalValueDecimal = totalValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
            } else {
                totalValueDecimal.stripTrailingZeros().toPlainString()
            }
            formatted
        } catch (e: Exception) {
            totalValue
        }
        val title = messageSource.getMessage("notification.auto_redeem.disabled.title", null, "Auto redeem is disabled", locale)
        val accountLabel = messageSource.getMessage("notification.auto_redeem.disabled.account", null, "Account", locale)
        val positionsLabel = messageSource.getMessage("notification.auto_redeem.disabled.redeemable_positions", null, "Redeemable positions", locale)
        val positionsUnit = messageSource.getMessage("notification.auto_redeem.disabled.positions_unit", null, "positions", locale)
        val totalValueLabel = messageSource.getMessage("notification.auto_redeem.disabled.total_value", null, "Total value", locale)
        val message = messageSource.getMessage("notification.auto_redeem.disabled.message", null, "Enable auto redeem in account settings to process these positions automatically.", locale)
        
        return "[Alert] $title\n\n" +
                "$accountLabel: $accountInfo\n" +
                "$positionsLabel: $positionCount $positionsUnit\n" +
                "$totalValueLabel: $totalValueDisplay USDC\n\n" +
                message
    }
    
    private fun buildBuilderApiKeyNotConfiguredMessage(
        accountName: String?,
        walletAddress: String?,
        configName: String?,
        totalValue: String,
        positionCount: Int
    ): String {
        val locale = try {
            LocaleContextHolder.getLocale()
        } catch (e: Exception) {
            java.util.Locale("zh", "CN")
        }
        
        val accountInfo = accountName ?: (walletAddress?.let { maskAddress(it) } ?: messageSource.getMessage("common.unknown", null, "Unknown", locale))
        val unknownConfig = messageSource.getMessage("notification.builder_api_key.not_configured.unknown_config", null, "Unknown config", locale)
        val configInfo = configName ?: unknownConfig
        val totalValueDisplay = try {
            val totalValueDecimal = totalValue.toSafeBigDecimal()
            val formatted = if (totalValueDecimal.scale() > 4) {
                totalValueDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString()
            } else {
                totalValueDecimal.stripTrailingZeros().toPlainString()
            }
            formatted
        } catch (e: Exception) {
            totalValue
        }
        val title = messageSource.getMessage("notification.builder_api_key.not_configured.title", null, "Builder API key is not configured", locale)
        val accountLabel = messageSource.getMessage("notification.builder_api_key.not_configured.account", null, "Account", locale)
        val configLabel = messageSource.getMessage("notification.builder_api_key.not_configured.copy_trading_config", null, "Copy trading config", locale)
        val positionsLabel = messageSource.getMessage("notification.builder_api_key.not_configured.redeemable_positions", null, "Redeemable positions", locale)
        val positionsUnit = messageSource.getMessage("notification.builder_api_key.not_configured.positions_unit", null, "positions", locale)
        val totalValueLabel = messageSource.getMessage("notification.builder_api_key.not_configured.total_value", null, "Total value", locale)
        val message = messageSource.getMessage("notification.builder_api_key.not_configured.message", null, "Configure the Builder API key in account settings to redeem these positions automatically.", locale)
        
        return "[Alert] $title\n\n" +
                "$accountLabel: $accountInfo\n" +
                "$configLabel: $configInfo\n" +
                "$positionsLabel: $positionCount $positionsUnit\n" +
                "$totalValueLabel: $totalValueDisplay USDC\n\n" +
                message
    }
    
    companion object {
        private const val PROFIT_TAKE_SELL_COOLDOWN_MILLIS = 120000L
        private val DEFAULT_PROFIT_TAKE_PRICE: BigDecimal = BigDecimal("0.99")
    }

    private fun maskAddress(address: String): String {
        if (address.length <= 10) {
            return address
        }
        return "${address.take(6)}...${address.takeLast(4)}"
    }
}



