package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.div
import com.wrbug.polymarketbot.util.gt
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.SingleFlightGate
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.event.EventListener
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
@Service
class OrderStatusUpdateService(
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val accountRepository: AccountRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val trackingService: CopyOrderTrackingService,
    private val marketService: MarketService,
    private val telegramNotificationService: TelegramNotificationService?,
    private val blockchainService: com.wrbug.polymarketbot.service.common.BlockchainService
) : ApplicationContextAware {

    private val logger = LoggerFactory.getLogger(OrderStatusUpdateService::class.java)

    private val updateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val updateGate = SingleFlightGate()
    @Volatile
    private var updateJob: Job? = null
    
    private var applicationContext: ApplicationContext? = null
    
    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }
    private fun getSelf(): OrderStatusUpdateService {
        return applicationContext?.getBean(OrderStatusUpdateService::class.java)
            ?: throw IllegalStateException("ApplicationContext not initialized")
    }
    private val orderNullDetectionTime = ConcurrentHashMap<String, Long>()
    private val ORDER_NULL_RETRY_WINDOW_MS = 60000L

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        logger.info("Order status update service started, polling every 5 seconds")
    }
    @Scheduled(fixedDelay = 5000)
    fun updateOrderStatus() {
        if (!updateGate.tryEnter()) {
            logger.debug("Skipped updateOrderStatus because another run is still in progress")
            return
        }
        updateJob = updateScope.launch {
            try {
                val self = getSelf()
                self.checkAndDeleteUnfilledOrders()
                self.updatePendingSellOrderPrices()
                self.updatePendingBuyOrders()
            } catch (e: Exception) {
                logger.error("Failed to update order statuses: ${e.message}", e)
            } finally {
                updateJob = null
                updateGate.leave()
            }
        }
    }
    @Scheduled(fixedDelay = 3600000)
    fun cleanupOrphanedOrders() {
        updateScope.launch {
            try {
                getSelf().cleanupDeletedAccountOrders()
            } catch (e: Exception) {
                logger.error("Cleanup deleted account orders failed: ${e.message}", e)
            }
        }
    }

    private fun isValidOrderId(orderId: String): Boolean {
        if (!orderId.startsWith("0x", ignoreCase = true)) {
            return false
        }
        val hexPart = orderId.substring(2)
        if (hexPart.isEmpty()) {
            return false
        }
        return hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun createPollingClobApi(
        account: Account,
        apiSecret: String,
        apiPassphrase: String
    ): PolymarketClobApi {
        return retrofitFactory.createFastTradingClobApi(
            account.apiKey!!,
            apiSecret,
            apiPassphrase,
            account.walletAddress
        )
    }

    @Transactional
    fun cleanupDeletedAccountOrders() {
        val validAccountIds = accountRepository.findAll().mapNotNull { it.id }.toSet()
        val validCopyTradingIds = copyTradingRepository.findAll()
            .asSequence()
            .filter { it.accountId in validAccountIds }
            .mapNotNull { it.id }
            .toSet()
        val recordsToDelete = sellMatchRecordRepository.findAll()
            .filter { it.copyTradingId !in validCopyTradingIds }

        if (recordsToDelete.isNotEmpty()) {
            logger.info("Cleaning order records for deleted accounts: ${recordsToDelete.size}")
            for (record in recordsToDelete) {
                val details = sellMatchDetailRepository.findByMatchRecordId(record.id!!)
                sellMatchDetailRepository.deleteAll(details)
            }
            sellMatchRecordRepository.deleteAll(recordsToDelete)

            logger.info("Deleted stale order records for removed accounts: ${recordsToDelete.size}")
        }
    }
    suspend fun checkAndDeleteUnfilledOrders() {
        try {
            val thirtySecondsAgo = System.currentTimeMillis() - 30000
            val ordersToCheck = copyOrderTrackingRepository.findByCreatedAtBeforeAndStatus(
                thirtySecondsAgo,
                "pending_fill"
            )

            if (ordersToCheck.isEmpty()) {
                return
            }
            val ordersByAccount = ordersToCheck.groupBy { it.accountId }

            for ((accountId, orders) in ordersByAccount) {
                try {
                    val account = accountRepository.findById(accountId).orElse(null)
                    if (account == null) {
                        logger.warn("Account not found, skipping status check: accountId={}", accountId)
                        continue
                    }
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.debug("Missing account API credentials, skipping status check: accountId=${account.id}")
                        continue
                    }
                    val apiSecret = try {
                        cryptoUtils.decrypt(account.apiSecret!!)
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt API Secret: accountId=${account.id}, error=${e.message}")
                        continue
                    }

                    val apiPassphrase = try {
                        cryptoUtils.decrypt(account.apiPassphrase!!)
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt API Passphrase: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    val clobApi = createPollingClobApi(account, apiSecret, apiPassphrase)
                    for (order in orders) {
                        try {
                            val orderResponse = clobApi.getOrder(order.buyOrderId)
                            if (orderResponse.code() != 200) {
                                val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "No error details"
                                logger.debug("Order detail request returned non-200 response: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, code=${orderResponse.code()}, errorBody=$errorBody")
                                continue
                            }
                            val orderDetail = orderResponse.body()
                            if (orderDetail == null) {
                                val firstDetectionTime =
                                    orderNullDetectionTime.getOrPut(order.buyOrderId) { System.currentTimeMillis() }
                                val currentTime = System.currentTimeMillis()
                                if (order.notificationSent) {
                                    if (currentTime - firstDetectionTime >= ORDER_NULL_RETRY_WINDOW_MS) {
                                        logger.info("Order detail stayed null after the retry window with matched quantity present, marking as fully_matched: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                        try {
                                            val updatedOrder = CopyOrderTracking(
                                                id = order.id,
                                                copyTradingId = order.copyTradingId,
                                                accountId = order.accountId,
                                                leaderId = order.leaderId,
                                                marketId = order.marketId,
                                                side = order.side,
                                                outcomeIndex = order.outcomeIndex,
                                                buyOrderId = order.buyOrderId,
                                                leaderBuyTradeId = order.leaderBuyTradeId,
                                                quantity = order.quantity,
                                                price = order.price,
                                                matchedQuantity = order.matchedQuantity,
                                                remainingQuantity = order.remainingQuantity,
                                                status = "fully_matched",
                                                notificationSent = order.notificationSent,
                                                source = order.source,
                                                createdAt = order.createdAt,
                                                updatedAt = System.currentTimeMillis()
                                            )
                                            copyOrderTrackingRepository.save(updatedOrder)
                                            orderNullDetectionTime.remove(order.buyOrderId)
                                        } catch (e: Exception) {
                                            logger.error("Failed to persist sold order update: orderId=${order.buyOrderId}, error=${e.message}", e)
                                        }
                                    }
                                    continue
                                }
                                if (currentTime - firstDetectionTime < ORDER_NULL_RETRY_WINDOW_MS) {
                                    val elapsedSeconds = ((currentTime - firstDetectionTime) / 1000).toInt()
                                    val hasMatchedDetails = sellMatchDetailRepository.findByTrackingId(order.id!!).isNotEmpty()
                                    val hasPartialSold = hasMatchedDetails || order.matchedQuantity > BigDecimal.ZERO
                                    if (hasPartialSold) {
                                        logger.debug("Order detail is null within the retry window while matched quantity exists, waiting for retry: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, matchedQuantity=${order.matchedQuantity}, elapsed=${elapsedSeconds}s, retryWindow=${ORDER_NULL_RETRY_WINDOW_MS / 1000}s")
                                    } else {
                                        logger.debug("Order detail is null within retry window, waiting for retry: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, elapsed=${elapsedSeconds}s, retryWindow=${ORDER_NULL_RETRY_WINDOW_MS / 1000}s")
                                    }
                                    continue
                                }
                                val hasMatchedDetails = sellMatchDetailRepository.findByTrackingId(order.id!!).isNotEmpty()
                                val hasPartialSold = hasMatchedDetails || order.matchedQuantity > BigDecimal.ZERO
                                if (hasPartialSold) {
                                    logger.warn("Order detail stayed null after the retry window while matched quantity exists, deleting stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, matchedQuantity=${order.matchedQuantity}, elapsed=${(currentTime - firstDetectionTime) / 1000}s")
                                } else {
                                    logger.warn("Order detail stayed null after retry window, deleting stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, elapsed=${(currentTime - firstDetectionTime) / 1000}s")
                                }
                                try {
                                    copyOrderTrackingRepository.deleteById(order.id!!)
                                    logger.info("Deleted stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                    orderNullDetectionTime.remove(order.buyOrderId)
                                } catch (e: Exception) {
                                    logger.error(
                                        "Failed to delete stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, error=${e.message}",
                                        e
                                    )
                                }
                                continue
                            }
                            orderNullDetectionTime.remove(order.buyOrderId)
                            val sizeMatched = orderDetail.sizeMatched?.toSafeBigDecimal() ?: BigDecimal.ZERO
                            if (sizeMatched > BigDecimal.ZERO) {
                                copyOrderTrackingRepository.save(
                                    buildConfirmedBuyOrder(
                                        order = order,
                                        actualPrice = orderDetail.price?.toSafeBigDecimal() ?: order.price,
                                        filledQuantity = sizeMatched,
                                        notificationSent = order.notificationSent
                                    )
                                )
                                continue
                            }
                            if (orderDetail.status != "FILLED" && sizeMatched <= BigDecimal.ZERO) {
                                logger.info("Deleting stale zero-filled order: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, status=${orderDetail.status}, sizeMatched=$sizeMatched")
                                try {
                                    copyOrderTrackingRepository.deleteById(order.id!!)
                                    logger.info("瀹告彃鍨归梽銈嗘弓閹存劒姘︾拋銏犲礋: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                } catch (e: Exception) {
                                    logger.error(
                                        "Failed to delete stale zero-filled order: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, error=${e.message}",
                                        e
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Failed to check order status: orderId={}, error={}", order.buyOrderId, e.message, e)
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Failed to check account orders: accountId={}, error={}", accountId, e.message, e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to check unfilled orders: {}", e.message, e)
        }
    }
    suspend fun updatePendingSellOrderPrices() {
        try {
            val pendingRecords = sellMatchRecordRepository.findByPriceUpdatedFalse()

            if (pendingRecords.isEmpty()) {
                return
            }

            logger.debug("Found ${pendingRecords.size} sell records waiting for settlement refresh")

            for (record in pendingRecords) {
                try {
                    val copyTrading = copyTradingRepository.findById(record.copyTradingId).orElse(null)
                    if (copyTrading == null) {
                        logger.warn("Copy trading configuration not found: copyTradingId=${record.copyTradingId}")
                        continue
                    }
                    val account = accountRepository.findById(copyTrading.accountId).orElse(null)
                    if (account == null) {
                        logger.warn("Account not found while updating sell settlement: accountId=${copyTrading.accountId}")
                        continue
                    }
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.debug("Missing account API credentials, skipping sell order status check: accountId=${account.id}")
                        continue
                    }
                    val apiSecret = try {
                        cryptoUtils.decrypt(account.apiSecret!!)
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt API Secret: accountId=${account.id}, error=${e.message}")
                        continue
                    }

                    val apiPassphrase = try {
                        cryptoUtils.decrypt(account.apiPassphrase!!)
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt API Passphrase: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    val clobApi = createPollingClobApi(account, apiSecret, apiPassphrase)
                    if (!record.sellOrderId.startsWith("0x", ignoreCase = true)) {
                        logger.debug("Sell order id is not a hex hash, checking auto-order fallback: orderId=${record.sellOrderId}")
                        val isAutoOrder = record.sellOrderId.startsWith("AUTO_", ignoreCase = true) ||
                                record.sellOrderId.startsWith("AUTO_FIFO_", ignoreCase = true) ||
                                record.sellOrderId.startsWith("AUTO_WS_", ignoreCase = true)

                        if (!isAutoOrder) {
                            sendSellOrderNotification(
                                record = record,
                                useTemporaryData = true,
                                account = account,
                                copyTrading = copyTrading,
                                clobApi = clobApi,
                                apiSecret = apiSecret,
                                apiPassphrase = apiPassphrase,
                                orderCreatedAt = record.createdAt
                            )
                        } else {
                            logger.debug("No sell-match update condition met, skipping record: orderId=${record.sellOrderId}")
                        }
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = record.sellPrice,
                            totalRealizedPnl = record.totalRealizedPnl,
                            priceUpdated = true,
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        continue
                    }
                    val isAutoOrder = record.sellOrderId.startsWith("AUTO_", ignoreCase = true) ||
                            record.sellOrderId.startsWith("AUTO_FIFO_", ignoreCase = true) ||
                            record.sellOrderId.startsWith("AUTO_WS_", ignoreCase = true)

                    if (isAutoOrder) {
                            logger.debug("No sell-match update condition met after auto-order fallback: orderId=${record.sellOrderId}")
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = record.sellPrice,
                            totalRealizedPnl = record.totalRealizedPnl,
                            priceUpdated = true,
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        continue
                    }
                    val actualSellPrice = trackingService.getActualExecutionPrice(
                        orderId = record.sellOrderId,
                        clobApi = clobApi,
                        fallbackPrice = record.sellPrice
                    )
                    if (actualSellPrice != record.sellPrice) {
                        val details = sellMatchDetailRepository.findByMatchRecordId(record.id!!)
                        var totalRealizedPnl = BigDecimal.ZERO

                        for (detail in details) {
                            val updatedRealizedPnl =
                                actualSellPrice.subtract(detail.buyPrice).multi(detail.matchedQuantity)
                            val updatedDetail = SellMatchDetail(
                                id = detail.id,
                                matchRecordId = detail.matchRecordId,
                                trackingId = detail.trackingId,
                                buyOrderId = detail.buyOrderId,
                                matchedQuantity = detail.matchedQuantity,
                                buyPrice = detail.buyPrice,
                                sellPrice = actualSellPrice,
                                realizedPnl = updatedRealizedPnl,
                                createdAt = detail.createdAt
                            )
                            sellMatchDetailRepository.save(updatedDetail)

                            totalRealizedPnl = totalRealizedPnl.add(updatedRealizedPnl)
                        }
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = actualSellPrice,
                            totalRealizedPnl = totalRealizedPnl,
                            priceUpdated = true,
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)

                        logger.info("Updated sell order price before notification: orderId=${record.sellOrderId}, previousPrice=${record.sellPrice}, actualPrice=$actualSellPrice")
                        sendSellOrderNotification(
                            record = updatedRecord,
                            actualPrice = actualSellPrice.toString(),
                            actualSize = record.totalMatchedQuantity.toString(),
                            avgFilledPrice = actualSellPrice.toString(),
                            filled = record.totalMatchedQuantity.toString(),
                            account = account,
                            copyTrading = copyTrading,
                            clobApi = clobApi,
                            apiSecret = apiSecret,
                            apiPassphrase = apiPassphrase,
                            orderCreatedAt = record.createdAt
                        )
                        logger.info("Sell order success notification sent: orderId=${record.sellOrderId}")
                    } else {
                        val updatedRecord = SellMatchRecord(
                            id = record.id,
                            copyTradingId = record.copyTradingId,
                            sellOrderId = record.sellOrderId,
                            leaderSellTradeId = record.leaderSellTradeId,
                            marketId = record.marketId,
                            side = record.side,
                            outcomeIndex = record.outcomeIndex,
                            totalMatchedQuantity = record.totalMatchedQuantity,
                            sellPrice = record.sellPrice,
                            totalRealizedPnl = record.totalRealizedPnl,
                            priceUpdated = true,
                            createdAt = record.createdAt
                        )
                        sellMatchRecordRepository.save(updatedRecord)
                        logger.debug("Sell order price already matches actual execution price: orderId=${record.sellOrderId}, price=$actualSellPrice")
                        sendSellOrderNotification(
                            record = updatedRecord,
                            actualPrice = actualSellPrice.toString(),
                            actualSize = record.totalMatchedQuantity.toString(),
                            avgFilledPrice = actualSellPrice.toString(),
                            filled = record.totalMatchedQuantity.toString(),
                            account = account,
                            copyTrading = copyTrading,
                            clobApi = clobApi,
                            apiSecret = apiSecret,
                            apiPassphrase = apiPassphrase,
                            orderCreatedAt = record.createdAt
                        )
                        logger.info("Sell order success notification sent: orderId=${record.sellOrderId}")
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to update sell order before notification: orderId=${record.sellOrderId}, error=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed while updating completed sell orders: ${e.message}", e)
        }
    }
    suspend fun updatePendingBuyOrders() {
        try {
            val pendingOrders = copyOrderTrackingRepository.findByNotificationSentFalse()

            if (pendingOrders.isEmpty()) {
                return
            }

            logger.debug("Found ${pendingOrders.size} buy orders waiting for notification update")

            for (order in pendingOrders) {
                try {
                    if (!isValidOrderId(order.buyOrderId)) {
                        logger.warn("Buy order ID is invalid, skipping remote query and using temporary notification data: orderId=${order.buyOrderId}")
                        val notificationDelivered = sendBuyOrderNotification(
                            order,
                            useTemporaryData = true,
                            orderCreatedAt = order.createdAt
                        )
                        if (notificationDelivered) {
                            copyOrderTrackingRepository.save(markBuyOrderNotificationSent(order))
                        }
                        continue
                    }
                    val copyTrading = copyTradingRepository.findById(order.copyTradingId).orElse(null)
                    if (copyTrading == null) {
                        logger.warn("Copy trading configuration not found: copyTradingId=${order.copyTradingId}")
                        continue
                    }
                    val account = accountRepository.findById(order.accountId).orElse(null)
                    if (account == null) {
                        logger.warn("Account not found while updating pending buy order notification: accountId=${order.accountId}")
                        continue
                    }
                    if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                        logger.debug("Missing account API credentials, skipping pending buy order check: accountId=${account.id}")
                        continue
                    }
                    val apiSecret = try {
                        cryptoUtils.decrypt(account.apiSecret!!)
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt API Secret: accountId=${account.id}, error=${e.message}")
                        continue
                    }

                    val apiPassphrase = try {
                        cryptoUtils.decrypt(account.apiPassphrase!!)
                    } catch (e: Exception) {
                        logger.warn("Failed to decrypt API Passphrase: accountId=${account.id}, error=${e.message}")
                        continue
                    }
                    val clobApi = createPollingClobApi(account, apiSecret, apiPassphrase)
                    val orderResponse = clobApi.getOrder(order.buyOrderId)
                    if (orderResponse.code() != 200) {
                        val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "No error details"
                        logger.debug("Order detail request returned non-200 response: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, code=${orderResponse.code()}, errorBody=$errorBody")
                        continue
                    }
                    val orderDetail = orderResponse.body()
                    if (orderDetail == null) {
                        val firstDetectionTime =
                            orderNullDetectionTime.getOrPut(order.buyOrderId) { System.currentTimeMillis() }
                        val currentTime = System.currentTimeMillis()
                        if (order.notificationSent) {
                            if (currentTime - firstDetectionTime >= ORDER_NULL_RETRY_WINDOW_MS) {
                                logger.info("Order detail stayed null after the retry window with matched quantity present, marking as fully_matched: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                                try {
                                    val updatedOrder = CopyOrderTracking(
                                        id = order.id,
                                        copyTradingId = order.copyTradingId,
                                        accountId = order.accountId,
                                        leaderId = order.leaderId,
                                        marketId = order.marketId,
                                        side = order.side,
                                        outcomeIndex = order.outcomeIndex,
                                        buyOrderId = order.buyOrderId,
                                        leaderBuyTradeId = order.leaderBuyTradeId,
                                        quantity = order.quantity,
                                        price = order.price,
                                        matchedQuantity = order.matchedQuantity,
                                        remainingQuantity = order.remainingQuantity,
                                        status = "fully_matched",
                                        notificationSent = order.notificationSent,
                                        source = order.source,
                                        createdAt = order.createdAt,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                    copyOrderTrackingRepository.save(updatedOrder)
                                    orderNullDetectionTime.remove(order.buyOrderId)
                                } catch (e: Exception) {
                                    logger.error("Failed to persist sold order update: orderId=${order.buyOrderId}, error=${e.message}", e)
                                }
                            }
                            continue
                        }
                        if (currentTime - firstDetectionTime < ORDER_NULL_RETRY_WINDOW_MS) {
                            val elapsedSeconds = ((currentTime - firstDetectionTime) / 1000).toInt()
                            val hasMatchedDetails = sellMatchDetailRepository.findByTrackingId(order.id!!).isNotEmpty()
                            val hasPartialSold = hasMatchedDetails || order.matchedQuantity > BigDecimal.ZERO
                            if (hasPartialSold) {
                                logger.debug("Order detail is null within the retry window while matched quantity exists, waiting for retry: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, matchedQuantity=${order.matchedQuantity}, elapsed=${elapsedSeconds}s, retryWindow=${ORDER_NULL_RETRY_WINDOW_MS / 1000}s")
                            } else {
                                logger.debug("Order detail is null within retry window, waiting for retry: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, elapsed=${elapsedSeconds}s, retryWindow=${ORDER_NULL_RETRY_WINDOW_MS / 1000}s")
                            }
                            continue
                        }
                        val hasMatchedDetails = sellMatchDetailRepository.findByTrackingId(order.id!!).isNotEmpty()
                        val hasPartialSold = hasMatchedDetails || order.matchedQuantity > BigDecimal.ZERO
                        if (hasPartialSold) {
                            logger.warn("Order detail stayed null after the retry window while matched quantity exists, deleting stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, matchedQuantity=${order.matchedQuantity}, elapsed=${(currentTime - firstDetectionTime) / 1000}s")
                        } else {
                            logger.warn("Order detail stayed null after retry window, deleting stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, elapsed=${(currentTime - firstDetectionTime) / 1000}s")
                        }
                        try {
                            copyOrderTrackingRepository.deleteById(order.id!!)
                            logger.info("Deleted stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}")
                            orderNullDetectionTime.remove(order.buyOrderId)
                        } catch (e: Exception) {
                            logger.error(
                                "Failed to delete stale order record: orderId=${order.buyOrderId}, copyOrderTrackingId=${order.id}, error=${e.message}",
                                e
                            )
                        }
                        continue
                    }
                    orderNullDetectionTime.remove(order.buyOrderId)
                    val sizeMatchedDec = orderDetail.sizeMatched.toSafeBigDecimal()
                    if (sizeMatchedDec <= BigDecimal.ZERO) {
                        logger.debug("Buy order is still not filled, keeping pending notification state: orderId=${order.buyOrderId}, status=${orderDetail.status}")
                        continue
                    }
                    val actualPrice = orderDetail.price?.toSafeBigDecimal() ?: order.price
                    val actualSize = sizeMatchedDec
                    val actualOutcome = orderDetail.outcome
                    val updatedOrder = buildConfirmedBuyOrder(
                        order = order,
                        actualPrice = actualPrice,
                        filledQuantity = actualSize,
                        notificationSent = false
                    )
                    val needUpdate =
                        actualPrice != order.price ||
                            actualSize != order.quantity ||
                            updatedOrder.status != order.status ||
                            updatedOrder.remainingQuantity != order.remainingQuantity
                    copyOrderTrackingRepository.save(updatedOrder)

                    if (needUpdate) {
                        logger.info("Updated buy order execution details before notification: orderId=${order.buyOrderId}, previousPrice=${order.price}, actualPrice=$actualPrice, previousSize=${order.quantity}, actualSize=$actualSize")
                    } else {
                        logger.debug("Buy order execution details already match the stored values: orderId=${order.buyOrderId}")
                    }
                    val avgFilledPriceStr = actualPrice.toPlainString()
                    val filledSize = actualSize.toPlainString()
                    val notificationDelivered = sendBuyOrderNotification(
                        order = updatedOrder,
                        actualPrice = actualPrice.toString(),
                        actualSize = actualSize.toString(),
                        actualOutcome = actualOutcome,
                        avgFilledPrice = avgFilledPriceStr,
                        filled = filledSize,
                        account = account,
                        copyTrading = copyTrading,
                        clobApi = clobApi,
                        apiSecret = apiSecret,
                        apiPassphrase = apiPassphrase,
                        orderCreatedAt = order.createdAt
                    )
                    if (notificationDelivered) {
                        copyOrderTrackingRepository.save(markBuyOrderNotificationSent(updatedOrder))
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to update buy order execution details before notification: orderId=${order.buyOrderId}, error=${e.message}", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed while updating pending buy order notifications: ${e.message}", e)
        }
    }

    private fun buildConfirmedBuyOrder(
        order: CopyOrderTracking,
        actualPrice: BigDecimal,
        filledQuantity: BigDecimal,
        notificationSent: Boolean
    ): CopyOrderTracking {
        val normalizedFilledQuantity = filledQuantity.max(BigDecimal.ZERO)
        val remainingQuantity = normalizedFilledQuantity.subtract(order.matchedQuantity).max(BigDecimal.ZERO)
        val status = when {
            remainingQuantity <= BigDecimal.ZERO -> "fully_matched"
            order.matchedQuantity > BigDecimal.ZERO -> "partially_matched"
            else -> "filled"
        }
        return CopyOrderTracking(
            id = order.id,
            copyTradingId = order.copyTradingId,
            accountId = order.accountId,
            leaderId = order.leaderId,
            marketId = order.marketId,
            side = order.side,
            outcomeIndex = order.outcomeIndex,
            buyOrderId = order.buyOrderId,
            leaderBuyTradeId = order.leaderBuyTradeId,
            quantity = normalizedFilledQuantity,
            price = actualPrice,
            matchedQuantity = order.matchedQuantity,
            remainingQuantity = remainingQuantity,
            status = status,
            notificationSent = notificationSent,
            source = order.source,
            createdAt = order.createdAt,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun markBuyOrderNotificationSent(order: CopyOrderTracking): CopyOrderTracking {
        return order.copy(
            notificationSent = true,
            updatedAt = System.currentTimeMillis()
        )
    }

    private suspend fun sendBuyOrderNotification(
        order: CopyOrderTracking,
        useTemporaryData: Boolean = false,
        actualPrice: String? = null,
        actualSize: String? = null,
        actualOutcome: String? = null,
        avgFilledPrice: String? = null,
        filled: String? = null,
        account: Account? = null,
        copyTrading: CopyTrading? = null,
        clobApi: PolymarketClobApi? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        orderCreatedAt: Long? = null
    ): Boolean {
        if (telegramNotificationService == null) {
            return false
        }

        return try {
            val finalCopyTrading = copyTrading ?: copyTradingRepository.findById(order.copyTradingId).orElse(null)
            if (finalCopyTrading == null) {
                logger.warn("Copy trading configuration not found while sending buy success notification: copyTradingId=${order.copyTradingId}")
                return false
            }

            val finalAccount = account ?: accountRepository.findById(order.accountId).orElse(null)
            if (finalAccount == null) {
                logger.warn("Account not found while sending buy success notification: accountId=${order.accountId}")
                return false
            }
            val market = marketService.getMarket(order.marketId)
            val marketTitle = market?.title ?: order.marketId
            val leader = leaderRepository.findById(order.leaderId).orElse(null)
            val leaderName = leader?.leaderName
            val leaderCurrentPositionValue = resolveLeaderCurrentPositionValue(
                blockchainService = blockchainService,
                leaderAddress = leader?.leaderAddress,
                marketId = order.marketId,
                outcomeIndex = order.outcomeIndex,
                outcome = actualOutcome
            )
            val configName = finalCopyTrading.configName
            val locale = try {
                LocaleContextHolder.getLocale()
            } catch (e: Exception) {
                java.util.Locale("zh", "CN")
            }
            val finalClobApi =
                clobApi ?: if (finalAccount.apiKey != null && apiSecret != null && apiPassphrase != null) {
                    createPollingClobApi(finalAccount, apiSecret, apiPassphrase)
                } else {
                    null
                }
            val availableBalance = try {
                blockchainService.getUsdcBalance(finalAccount.walletAddress, finalAccount.proxyAddress).getOrNull()
            } catch (e: Exception) {
                logger.warn("Failed to query available balance for buy success notification: accountId=${finalAccount.id}, ${e.message}")
                null
            }
            telegramNotificationService.sendOrderSuccessNotification(
                orderId = order.buyOrderId,
                marketTitle = marketTitle,
                marketId = order.marketId,
                marketSlug = market?.eventSlug,
                side = "BUY",
                price = actualPrice ?: order.price.toString(),
                avgFilledPrice = avgFilledPrice,
                filled = filled,
                size = actualSize ?: order.quantity.toString(),
                outcome = actualOutcome,
                accountName = finalAccount.accountName,
                walletAddress = finalAccount.walletAddress,
                clobApi = finalClobApi,
                apiKey = finalAccount.apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddressForApi = finalAccount.walletAddress,
                locale = locale,
                leaderName = leaderName,
                configName = configName,
                orderTime = orderCreatedAt,
                availableBalance = availableBalance,
                currentPositionValue = leaderCurrentPositionValue,
                copyTradingId = finalCopyTrading.id,
                messageCategory = leader?.category ?: leader?.customGroup
            )

            logger.info("Buy order success notification sent: orderId=${order.buyOrderId}, copyTradingId=${order.copyTradingId}")
            true
        } catch (e: Exception) {
            logger.warn("Failed to send buy order success notification: orderId=${order.buyOrderId}, error=${e.message}", e)
            false
        }
    }
    private suspend fun sendSellOrderNotification(
        record: SellMatchRecord,
        useTemporaryData: Boolean = false,
        actualPrice: String? = null,
        actualSize: String? = null,
        actualOutcome: String? = null,
        avgFilledPrice: String? = null,
        filled: String? = null,
        account: Account? = null,
        copyTrading: CopyTrading? = null,
        clobApi: PolymarketClobApi? = null,
        apiSecret: String? = null,
        apiPassphrase: String? = null,
        orderCreatedAt: Long? = null
    ) {
        if (telegramNotificationService == null) {
            return
        }

        try {
            val finalCopyTrading = copyTrading ?: copyTradingRepository.findById(record.copyTradingId).orElse(null)
            if (finalCopyTrading == null) {
                logger.warn("Copy trading configuration not found while sending sell success notification: copyTradingId=${record.copyTradingId}")
                return
            }

            val finalAccount = account ?: accountRepository.findById(finalCopyTrading.accountId).orElse(null)
            if (finalAccount == null) {
                logger.warn("Account not found while sending sell success notification: accountId=${finalCopyTrading.accountId}")
                return
            }
            val market = marketService.getMarket(record.marketId)
            val marketTitle = market?.title ?: record.marketId
            val leader = leaderRepository.findById(finalCopyTrading.leaderId).orElse(null)
            val leaderName = leader?.leaderName
            val leaderCurrentPositionValue = resolveLeaderCurrentPositionValue(
                blockchainService = blockchainService,
                leaderAddress = leader?.leaderAddress,
                marketId = record.marketId,
                outcomeIndex = record.outcomeIndex,
                outcome = actualOutcome
            )
            val configName = finalCopyTrading.configName
            val locale = try {
                LocaleContextHolder.getLocale()
            } catch (e: Exception) {
                java.util.Locale("zh", "CN")
            }
            val finalClobApi =
                clobApi ?: if (finalAccount.apiKey != null && apiSecret != null && apiPassphrase != null) {
                    createPollingClobApi(finalAccount, apiSecret, apiPassphrase)
                } else {
                    null
                }
            val availableBalance = try {
                blockchainService.getUsdcBalance(finalAccount.walletAddress, finalAccount.proxyAddress).getOrNull()
            } catch (e: Exception) {
                logger.warn("Failed to query available balance for sell success notification: accountId=${finalAccount.id}, ${e.message}")
                null
            }
            telegramNotificationService.sendOrderSuccessNotification(
                orderId = record.sellOrderId,
                marketTitle = marketTitle,
                marketId = record.marketId,
                marketSlug = market?.eventSlug,
                side = "SELL",
                price = actualPrice ?: record.sellPrice.toString(),
                avgFilledPrice = avgFilledPrice,
                filled = filled,
                size = actualSize ?: record.totalMatchedQuantity.toString(),
                outcome = actualOutcome,
                accountName = finalAccount.accountName,
                walletAddress = finalAccount.walletAddress,
                clobApi = finalClobApi,
                apiKey = finalAccount.apiKey,
                apiSecret = apiSecret,
                apiPassphrase = apiPassphrase,
                walletAddressForApi = finalAccount.walletAddress,
                locale = locale,
                leaderName = leaderName,
                configName = configName,
                orderTime = orderCreatedAt,
                availableBalance = availableBalance,
                currentPositionValue = leaderCurrentPositionValue,
                copyTradingId = finalCopyTrading.id,
                messageCategory = leader?.category ?: leader?.customGroup
            )

            logger.info("Sell order success notification sent: orderId=${record.sellOrderId}, copyTradingId=${record.copyTradingId}")
        } catch (e: Exception) {
            logger.warn("Failed to send sell order success notification: orderId=${record.sellOrderId}, error=${e.message}", e)
        }
    }
}


