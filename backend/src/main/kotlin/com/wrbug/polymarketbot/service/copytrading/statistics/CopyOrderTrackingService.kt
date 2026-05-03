package com.wrbug.polymarketbot.service.copytrading.statistics

import com.wrbug.polymarketbot.api.NewOrderRequest
import com.wrbug.polymarketbot.api.OrderbookResponse
import com.wrbug.polymarketbot.api.PolymarketClobApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.dto.AccountPositionDto
import com.wrbug.polymarketbot.entity.*
import com.wrbug.polymarketbot.repository.*
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.*
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.service.copytrading.configs.FilterStatus
import com.wrbug.polymarketbot.service.copytrading.configs.FollowAmountRuleInput
import com.wrbug.polymarketbot.service.copytrading.configs.FollowAmountRuleMatcher
import com.wrbug.polymarketbot.service.copytrading.orders.OrderSigningService
import com.wrbug.polymarketbot.service.common.BlockchainService
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.PolymarketClobService
import com.wrbug.polymarketbot.service.system.TelegramNotificationService
import com.wrbug.polymarketbot.util.CryptoUtils
import org.springframework.stereotype.Service
import java.math.BigDecimal
import kotlin.math.max
@Service
open class CopyOrderTrackingService(
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository,
    private val processedTradeRepository: ProcessedTradeRepository,
    private val filteredOrderRepository: FilteredOrderRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyTradingFollowRuleRepository: CopyTradingFollowRuleRepository,
    private val accountRepository: AccountRepository,
    private val filterService: CopyTradingFilterService,
    private val leaderRepository: LeaderRepository,
    private val orderSigningService: OrderSigningService,
    private val blockchainService: BlockchainService,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val cryptoUtils: CryptoUtils,
    private val marketService: MarketService,
    private val copyTradingDailyMetricsService: CopyTradingDailyMetricsService,
    private val telegramNotificationService: TelegramNotificationService? = null,
    private val leaderMonitorAlertService: LeaderMonitorAlertService? = null
) {

    private val logger = LoggerFactory.getLogger(CopyOrderTrackingService::class.java)
    private val followAmountRuleMatcher = FollowAmountRuleMatcher()
    private val notificationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tradeMutexRegistry = TradeProcessingMutexRegistry()

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 3000L
        private const val EMPTY_UNMATCHED_RETRY_DELAY_MS = 2000L
        private const val COPY_TRADING_ORDER_EXECUTION_ENABLED = false
    }

    @PreDestroy
    fun shutdown() {
        notificationScope.cancel("CopyOrderTrackingService shutting down")
    }

    private fun decryptPrivateKey(account: Account): String {
        return try {
            cryptoUtils.decrypt(account.privateKey)
        } catch (e: Exception) {
            logger.error("Failed to decrypt private key: accountId=${account.id}", e)
            throw RuntimeException("Failed to decrypt private key: ${e.message}", e)
        }
    }

    private fun decryptApiSecret(account: Account): String {
        return account.apiSecret?.let { secret ->
            try {
                cryptoUtils.decrypt(secret)
            } catch (e: Exception) {
                logger.error("Failed to decrypt API Secret: accountId=${account.id}", e)
                throw RuntimeException("Failed to decrypt API Secret: ${e.message}", e)
            }
        } ?: throw IllegalStateException("API Secret is missing")
    }

    private fun decryptApiPassphrase(account: Account): String {
        return account.apiPassphrase?.let { passphrase ->
            try {
                cryptoUtils.decrypt(passphrase)
            } catch (e: Exception) {
                logger.error("Failed to decrypt API Passphrase: accountId=${account.id}", e)
                throw RuntimeException("Failed to decrypt API Passphrase: ${e.message}", e)
            }
        } ?: throw IllegalStateException("API Passphrase is missing")
    }

    suspend fun processTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        val mutexLease = tradeMutexRegistry.acquire(buildTradeProcessingKey(leaderId, trade))
        logger.debug("processTrade: ${trade.id}, $source")
        return try {
            mutexLease.mutex.withLock {
                try {
                    val existingProcessed = processedTradeRepository.findByLeaderIdAndLeaderTradeId(leaderId, trade.id)
                    if (existingProcessed != null) {
                        return@withLock Result.success(Unit)
                    }

                    val monitorModeEnabled = telegramNotificationService?.isMonitorModeEnabled() == true
                    val result = if (monitorModeEnabled) {
                        when (trade.side.uppercase()) {
                            "BUY", "SELL" -> {
                                leaderMonitorAlertService?.processTrade(leaderId, trade)
                                Result.success(Unit)
                            }

                            else -> Result.failure(IllegalArgumentException("Unknown trade side: ${trade.side}"))
                        }
                    } else {
                        when (trade.side.uppercase()) {
                            "BUY" -> processBuyTrade(leaderId, trade, source)
                            "SELL" -> processSellTrade(leaderId, trade)
                            else -> Result.failure(IllegalArgumentException("Unknown trade side: ${trade.side}"))
                        }
                    }

                    if (result.isFailure) {
                        return@withLock result
                    }

                    try {
                        processedTradeRepository.save(
                            ProcessedTrade(
                                leaderId = leaderId,
                                leaderTradeId = trade.id,
                                tradeType = trade.side.uppercase(),
                                source = source,
                                status = "SUCCESS",
                                processedAt = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        if (!isUniqueConstraintViolation(e)) {
                            throw e
                        }
                    }

                    Result.success(Unit)
                } catch (e: Exception) {
                    logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
                    Result.failure(e)
                }
            }
        } finally {
            mutexLease.release()
        }
    }

    suspend fun processBuyTrade(leaderId: Long, trade: TradeResponse, source: String): Result<Unit> {
        if (!COPY_TRADING_ORDER_EXECUTION_ENABLED) {
            return Result.success(Unit)
        }
        return processBuyTradeOptimized(leaderId, trade, source)
    }

    suspend fun processSellTrade(leaderId: Long, trade: TradeResponse): Result<Unit> {
        if (!COPY_TRADING_ORDER_EXECUTION_ENABLED) {
            return Result.success(Unit)
        }
        return processSellTradeOptimized(leaderId, trade)
    }

    private data class TradeMarketContext(
        val tokenId: String,
        val marketId: String,
        val outcomeIndex: Int?,
        val outcome: String?,
        val marketTitle: String?,
        val marketEndDate: Long?,
        val marketSlug: String?,
        val marketEventSlug: String?,
        val orderbook: OrderbookResponse?,
        val negRisk: Boolean,
        val exchangeContract: String
    )

    private data class BuyTradeSharedContext(
        val market: TradeMarketContext,
        val leaderTradePrice: BigDecimal,
        val leaderOrderAmount: BigDecimal
    )

    private data class SellTradeSharedContext(
        val market: TradeMarketContext,
        val marketSellPrice: BigDecimal,
        val publicClobApi: PolymarketClobApi
    )

    private data class SellOrderCandidate(
        val tracking: CopyOrderTracking,
        val leaderBuyQuantity: BigDecimal
    )

    private suspend fun processBuyTradeOptimized(
        leaderId: Long,
        trade: TradeResponse,
        source: String
    ): Result<Unit> {
        return try {
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
            if (copyTradings.isEmpty()) {
                return Result.success(Unit)
            }

            val accountsById = accountRepository.findAllById(copyTradings.map { it.accountId }.distinct())
                .associateBy { it.id!! }
            val followRulesByCopyTrading = copyTradingFollowRuleRepository
                .findByCopyTradingIdIn(copyTradings.mapNotNull { it.id }.distinct())
                .groupBy { it.copyTradingId }
                .mapValues { (_, rules) -> rules.sortedBy { it.sortOrder } }
            val sharedContext = resolveBuyTradeSharedContext(trade, copyTradings)
            val currentPositionsByAccount = preloadCurrentPositionsByAccount(copyTradings, accountsById)

            supervisorScope {
                copyTradings.groupBy { it.accountId }.map { (accountId, accountCopyTradings) ->
                    async {
                        val account = accountsById[accountId]
                        for (copyTrading in accountCopyTradings.sortedBy { it.id ?: Long.MAX_VALUE }) {
                            try {
                                processBuyTradeForCopyTrading(
                                    copyTrading = copyTrading,
                                    account = account,
                                    trade = trade,
                                    source = source,
                                    sharedContext = sharedContext,
                                    followRules = followRulesByCopyTrading[copyTrading.id].orEmpty(),
                                    currentPositions = currentPositionsByAccount[copyTrading.accountId]
                                )
                            } catch (e: Exception) {
                                logger.error(
                                    "Failed to process buy trade: copyTradingId=${copyTrading.id}, tradeId=${trade.id}",
                                    e
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    private suspend fun processBuyTradeForCopyTrading(
        copyTrading: CopyTrading,
        account: Account?,
        trade: TradeResponse,
        source: String,
        sharedContext: BuyTradeSharedContext,
        followRules: List<CopyTradingFollowRule>,
        currentPositions: List<AccountPositionDto>? = null
    ) {
        if (account == null || !account.isEnabled) return
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return
        if (!copyTrading.followSettingsEnabled) return

        val matchedRule = getMatchedFollowRule(copyTrading, sharedContext.leaderOrderAmount, followRules) ?: return
        val desiredOrderAmount = matchedRule.followAmount.max(copyTrading.minOrderSize)
        val maxAllowedAmount = matchedRule.followMaxAmount.min(copyTrading.maxOrderSize)
        if (maxAllowedAmount.lte(BigDecimal.ZERO) || desiredOrderAmount.gt(maxAllowedAmount)) return

        val estimatedQuantity = calculateQuantityByAmount(desiredOrderAmount, sharedContext.leaderTradePrice)
        val filterResult = filterService.checkFilters(
            copyTrading = copyTrading,
            tokenId = sharedContext.market.tokenId,
            tradePrice = sharedContext.leaderTradePrice,
            copyOrderAmount = desiredOrderAmount,
            marketId = sharedContext.market.marketId,
            marketTitle = sharedContext.market.marketTitle,
            marketEndDate = sharedContext.market.marketEndDate,
            outcomeIndex = sharedContext.market.outcomeIndex,
            sharedOrderbook = sharedContext.market.orderbook,
            currentPositions = currentPositions
        )
        if (!filterResult.isPassed) {
            handleFilteredBuyTrade(copyTrading, account, trade, sharedContext, estimatedQuantity, filterResult)
            return
        }

        val riskCheck = checkRiskControlsOptimized(copyTrading)
        if (!riskCheck.first) return

        if (copyTrading.delaySeconds > 0) {
            delay(copyTrading.delaySeconds * 1000L)
        }

        val buyPrice = calculateAdjustedPrice(sharedContext.leaderTradePrice, copyTrading, isBuy = true)
        var finalBuyQuantity = calculateQuantityByAmount(desiredOrderAmount, buyPrice)
        val maxBuyQuantity = calculateQuantityByAmount(maxAllowedAmount, buyPrice)
        if (maxBuyQuantity.gt(BigDecimal.ZERO) && finalBuyQuantity.gt(maxBuyQuantity)) {
            finalBuyQuantity = maxBuyQuantity
        }
        if (finalBuyQuantity.lte(BigDecimal.ZERO)) return
        if (finalBuyQuantity.lt(BigDecimal.ONE)) {
            if (maxAllowedAmount.lt(buyPrice)) return
            finalBuyQuantity = BigDecimal.ONE
        }

        val bestAsk = filterResult.orderbook?.asks
            ?.mapNotNull { it.price.toSafeBigDecimal() }
            ?.minOrNull()
            ?: sharedContext.market.orderbook?.asks
                ?.mapNotNull { it.price.toSafeBigDecimal() }
                ?.minOrNull()
        if (bestAsk != null && buyPrice.lt(bestAsk)) return

        val apiSecret = try { decryptApiSecret(account) } catch (e: Exception) { return }
        val apiPassphrase = try { decryptApiPassphrase(account) } catch (e: Exception) { return }
        val decryptedPrivateKey = try { decryptPrivateKey(account) } catch (e: Exception) { return }

        val clobApi = retrofitFactory.createFastTradingClobApi(
            account.apiKey,
            apiSecret,
            apiPassphrase,
            account.walletAddress
        )
        val createOrderResult = createOrderWithRetry(
            clobApi = clobApi,
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            walletAddress = account.walletAddress,
            exchangeContract = sharedContext.market.exchangeContract,
            tokenId = sharedContext.market.tokenId,
            side = "BUY",
            price = buyPrice.toPlainString(),
            size = finalBuyQuantity.toPlainString(),
            owner = account.apiKey,
            copyTradingId = copyTrading.id!!,
            tradeId = trade.id,
            signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        )
        if (createOrderResult.isFailure) {
            handleFailedBuyTrade(
                copyTrading = copyTrading,
                account = account,
                sharedContext = sharedContext,
                buyPrice = buyPrice,
                buyQuantity = finalBuyQuantity,
                exception = createOrderResult.exceptionOrNull()
            )
            return
        }

        val realOrderId = createOrderResult.getOrNull() ?: return
        if (!isValidOrderId(realOrderId)) return

        copyOrderTrackingRepository.save(
            CopyOrderTracking(
                copyTradingId = copyTrading.id,
                accountId = copyTrading.accountId,
                leaderId = copyTrading.leaderId,
                marketId = sharedContext.market.marketId,
                side = sharedContext.market.outcomeIndex?.toString() ?: "",
                outcomeIndex = sharedContext.market.outcomeIndex,
                buyOrderId = realOrderId,
                leaderBuyTradeId = trade.id,
                leaderBuyQuantity = trade.size.toSafeBigDecimal(),
                quantity = finalBuyQuantity,
                price = buyPrice,
                remainingQuantity = finalBuyQuantity,
                status = "pending_fill",
                notificationSent = false,
                source = source
            )
        )
        copyTradingDailyMetricsService.invalidate(copyTrading.id!!)
    }

    private fun getMatchedFollowRule(
        copyTrading: CopyTrading,
        leaderOrderAmount: BigDecimal,
        preloadedRules: List<CopyTradingFollowRule>? = null
    ): FollowAmountRuleInput? {
        if (!copyTrading.followSettingsEnabled) {
            return null
        }
        val copyTradingId = copyTrading.id ?: return null
        val rules = preloadedRules ?: copyTradingFollowRuleRepository.findByCopyTradingIdOrderBySortOrderAsc(copyTradingId)
        if (rules.isEmpty()) {
            return null
        }
        return followAmountRuleMatcher.match(
            leaderOrderAmount,
            rules.map { rule ->
                FollowAmountRuleInput(
                    minLeaderAmount = rule.minLeaderAmount,
                    maxLeaderAmount = rule.maxLeaderAmount,
                    followAmount = rule.followAmount,
                    followMaxAmount = rule.followMaxAmount,
                    sortOrder = rule.sortOrder
                )
            }
        )
    }

    private fun calculateQuantityByAmount(amount: BigDecimal, price: BigDecimal): BigDecimal {
        if (amount.lte(BigDecimal.ZERO) || price.lte(BigDecimal.ZERO)) {
            return BigDecimal.ZERO
        }
        return amount.divide(price, 8, java.math.RoundingMode.DOWN)
    }

    private suspend fun processSellTradeOptimized(
        leaderId: Long,
        trade: TradeResponse
    ): Result<Unit> {
        return try {
            val copyTradings = copyTradingRepository.findByLeaderIdAndEnabledTrue(leaderId)
                .filter { it.supportSell }
            if (copyTradings.isEmpty()) {
                return Result.success(Unit)
            }

            val accountsById = accountRepository.findAllById(copyTradings.map { it.accountId }.distinct())
                .associateBy { it.id!! }
            val sharedContext = resolveSellTradeSharedContext(trade, copyTradings)
            val outcomeIndex = sharedContext.market.outcomeIndex ?: return Result.success(Unit)
            val copyTradingIds = copyTradings.mapNotNull { it.id }
            var unmatchedOrdersByCopyTradingId = preloadUnmatchedSellOrders(
                copyTradingIds = copyTradingIds,
                marketId = sharedContext.market.marketId,
                outcomeIndex = outcomeIndex
            )
            if (unmatchedOrdersByCopyTradingId.isEmpty()) {
                delay(EMPTY_UNMATCHED_RETRY_DELAY_MS)
                unmatchedOrdersByCopyTradingId = preloadUnmatchedSellOrders(
                    copyTradingIds = copyTradingIds,
                    marketId = sharedContext.market.marketId,
                    outcomeIndex = outcomeIndex
                )
            }

            supervisorScope {
                copyTradings.groupBy { it.accountId }.map { (accountId, accountCopyTradings) ->
                    async {
                        val account = accountsById[accountId]
                        for (copyTrading in accountCopyTradings.sortedBy { it.id ?: Long.MAX_VALUE }) {
                            try {
                                processSellTradeForCopyTrading(
                                    copyTrading = copyTrading,
                                    account = account,
                                    leaderSellTrade = trade,
                                    sharedContext = sharedContext,
                                    unmatchedOrders = unmatchedOrdersByCopyTradingId[copyTrading.id].orEmpty()
                                )
                            } catch (e: Exception) {
                                logger.error(
                                    "Failed to process sell trade: copyTradingId=${copyTrading.id}, tradeId=${trade.id}",
                                    e
                                )
                            }
                        }
                    }
                }.awaitAll()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("处理交易异常: leaderId=$leaderId, tradeId=${trade.id}", e)
            Result.failure(e)
        }
    }

    private suspend fun processSellTradeForCopyTrading(
        copyTrading: CopyTrading,
        account: Account?,
        leaderSellTrade: TradeResponse,
        sharedContext: SellTradeSharedContext,
        unmatchedOrders: List<CopyOrderTracking>
    ) {
        if (account == null || !account.isEnabled) return
        if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) return
        val outcomeIndex = sharedContext.market.outcomeIndex ?: return
        if (unmatchedOrders.isEmpty()) return

        val candidates = unmatchedOrders.mapNotNull { order ->
            fetchAndBackfillLeaderBuyQuantity(order, sharedContext.publicClobApi)?.let { qty ->
                SellOrderCandidate(order, qty)
            }
        }
        if (candidates.isEmpty()) return

        var needMatch = calculateSellQuantityFromMatchedOrdersOptimized(
            candidates,
            leaderSellTrade.size.toSafeBigDecimal()
        )
        if (needMatch.gt(BigDecimal.ZERO) && needMatch.lt(BigDecimal.ONE)) {
            needMatch = BigDecimal.ONE
        }
        if (needMatch.lte(BigDecimal.ZERO)) return

        var totalMatched = BigDecimal.ZERO
        var remaining = needMatch
        val updatedOrders = mutableListOf<CopyOrderTracking>()
        val matchDetails = mutableListOf<SellMatchDetail>()

        for (candidate in candidates) {
            if (remaining.lte(BigDecimal.ZERO)) break
            val matchQuantity = minOf(candidate.tracking.remainingQuantity.toSafeBigDecimal(), remaining)
            if (matchQuantity.lte(BigDecimal.ZERO)) continue

            val updatedOrder = if (candidate.tracking.leaderBuyQuantity == null) {
                candidate.tracking.copy(leaderBuyQuantity = candidate.leaderBuyQuantity)
            } else {
                candidate.tracking
            }
            updatedOrder.matchedQuantity = updatedOrder.matchedQuantity.toSafeBigDecimal().add(matchQuantity)
            updatedOrder.remainingQuantity = updatedOrder.remainingQuantity.toSafeBigDecimal().subtract(matchQuantity)
            updateOrderStatus(updatedOrder)
            updatedOrder.updatedAt = System.currentTimeMillis()
            updatedOrders.add(updatedOrder)

            matchDetails.add(
                SellMatchDetail(
                    matchRecordId = 0,
                    trackingId = candidate.tracking.id!!,
                    buyOrderId = candidate.tracking.buyOrderId,
                    matchedQuantity = matchQuantity,
                    buyPrice = candidate.tracking.price.toSafeBigDecimal(),
                    sellPrice = sharedContext.marketSellPrice,
                    realizedPnl = sharedContext.marketSellPrice
                        .subtract(candidate.tracking.price.toSafeBigDecimal())
                        .multi(matchQuantity)
                )
            )
            totalMatched = totalMatched.add(matchQuantity)
            remaining = remaining.subtract(matchQuantity)
        }

        if (totalMatched.lt(BigDecimal.ONE)) return

        val apiSecret = try { decryptApiSecret(account) } catch (e: Exception) { return }
        val apiPassphrase = try { decryptApiPassphrase(account) } catch (e: Exception) { return }
        val decryptedPrivateKey = try { decryptPrivateKey(account) } catch (e: Exception) { return }
        val clobApi = retrofitFactory.createFastTradingClobApi(account.apiKey, apiSecret, apiPassphrase, account.walletAddress)
        val createOrderResult = createOrderWithRetry(
            clobApi = clobApi,
            privateKey = decryptedPrivateKey,
            makerAddress = account.proxyAddress,
            walletAddress = account.walletAddress,
            exchangeContract = sharedContext.market.exchangeContract,
            tokenId = sharedContext.market.tokenId,
            side = "SELL",
            price = sharedContext.marketSellPrice.toPlainString(),
            size = totalMatched.toPlainString(),
            owner = account.apiKey,
            copyTradingId = copyTrading.id!!,
            tradeId = leaderSellTrade.id,
            signatureType = orderSigningService.getSignatureTypeForWalletType(account.walletType)
        )
        if (createOrderResult.isFailure) return

        val realSellOrderId = createOrderResult.getOrNull() ?: return
        val priceUpdated = !realSellOrderId.startsWith("0x", ignoreCase = true)
        updatedOrders.forEach { copyOrderTrackingRepository.save(it) }

        val matchRecord = sellMatchRecordRepository.save(
            SellMatchRecord(
                copyTradingId = copyTrading.id!!,
                sellOrderId = realSellOrderId,
                leaderSellTradeId = leaderSellTrade.id,
                marketId = sharedContext.market.marketId,
                side = outcomeIndex.toString(),
                outcomeIndex = outcomeIndex,
                totalMatchedQuantity = totalMatched,
                sellPrice = sharedContext.marketSellPrice,
                totalRealizedPnl = matchDetails.sumOf { it.realizedPnl.toSafeBigDecimal() },
                priceUpdated = priceUpdated
            )
        )
        matchDetails.forEach { sellMatchDetailRepository.save(it.copy(matchRecordId = matchRecord.id!!)) }
    }

    private suspend fun resolveBuyTradeSharedContext(
        trade: TradeResponse,
        copyTradings: List<CopyTrading>
    ): BuyTradeSharedContext {
        val leaderTradePrice = trade.price.toSafeBigDecimal()
        val tokenId = resolveTokenId(trade)
        val market = resolveTradeMarketContext(trade, tokenId, copyTradings, alwaysFetchOrderbook = true)
        return BuyTradeSharedContext(
            market = market,
            leaderTradePrice = leaderTradePrice,
            leaderOrderAmount = trade.size.toSafeBigDecimal().multi(leaderTradePrice)
        )
    }

    private suspend fun resolveSellTradeSharedContext(
        trade: TradeResponse,
        copyTradings: List<CopyTrading>
    ): SellTradeSharedContext {
        val tokenId = resolveTokenId(trade)
        val market = resolveTradeMarketContext(trade, tokenId, copyTradings, alwaysFetchOrderbook = true)
        val marketSellPrice = market.orderbook?.let { calculateMarketSellPrice(it) }
            ?: calculateFallbackSellPrice(trade.price.toSafeBigDecimal())
        return SellTradeSharedContext(
            market = market,
            marketSellPrice = marketSellPrice,
            publicClobApi = retrofitFactory.createFastTradingClobApiWithoutAuth()
        )
    }

    private suspend fun preloadCurrentPositionsByAccount(
        copyTradings: List<CopyTrading>,
        accountsById: Map<Long, Account>
    ): Map<Long, List<AccountPositionDto>> {
        val relevantAccounts = copyTradings.asSequence()
            .filter { it.maxPositionValue != null }
            .mapNotNull { accountsById[it.accountId] }
            .distinctBy { it.id }
            .toList()
        if (relevantAccounts.isEmpty()) {
            return emptyMap()
        }

        return supervisorScope {
            relevantAccounts.map { account ->
                async {
                    val accountId = account.id ?: return@async null
                    val positions = blockchainService.getPositions(account.proxyAddress).getOrElse { error ->
                        logger.warn(
                            "Failed to preload positions for hot path: accountId=$accountId, proxy=${account.proxyAddress}, error=${error.message}"
                        )
                        return@async null
                    }
                    accountId to positions.mapNotNull { position ->
                        position.toCurrentAccountPosition(account)
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private fun PositionResponse.toCurrentAccountPosition(account: Account): AccountPositionDto? {
        val accountId = account.id ?: return null
        val currentValueDecimal = currentValue?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO
        val currentPriceDecimal = curPrice?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO
        if (currentValueDecimal.compareTo(BigDecimal.ZERO) == 0 || currentPriceDecimal.compareTo(BigDecimal.ZERO) == 0) {
            return null
        }
        val sizeDecimal = size?.let { BigDecimal.valueOf(it) } ?: BigDecimal.ZERO
        return AccountPositionDto(
            accountId = accountId,
            accountName = account.accountName,
            walletAddress = account.walletAddress,
            proxyAddress = account.proxyAddress,
            marketId = conditionId.orEmpty(),
            marketTitle = title,
            marketSlug = slug,
            eventSlug = eventSlug,
            marketIcon = icon,
            side = outcome.orEmpty(),
            outcomeIndex = outcomeIndex,
            quantity = sizeDecimal.setScale(4, java.math.RoundingMode.DOWN).toPlainString(),
            originalQuantity = sizeDecimal.toPlainString(),
            avgPrice = avgPrice?.let { BigDecimal.valueOf(it).toPlainString() } ?: "0",
            currentPrice = currentPriceDecimal.toPlainString(),
            currentValue = currentValueDecimal.toPlainString(),
            initialValue = initialValue?.let { BigDecimal.valueOf(it).toPlainString() } ?: "0",
            pnl = cashPnl?.let { BigDecimal.valueOf(it).toPlainString() } ?: "0",
            percentPnl = percentPnl?.let { BigDecimal.valueOf(it).toPlainString() } ?: "0",
            realizedPnl = realizedPnl?.let { BigDecimal.valueOf(it).toPlainString() },
            percentRealizedPnl = percentRealizedPnl?.let { BigDecimal.valueOf(it).toPlainString() },
            redeemable = redeemable ?: false,
            mergeable = mergeable ?: false,
            endDate = endDate,
            isCurrent = true
        )
    }

    private suspend fun resolveTokenId(trade: TradeResponse): String {
        if (!trade.tokenId.isNullOrBlank()) {
            return trade.tokenId
        }
        val outcomeIndex = trade.outcomeIndex
            ?: throw IllegalStateException("Trade is missing outcome index: tradeId=${trade.id}, market=${trade.market}")
        return blockchainService.getTokenId(trade.market, outcomeIndex)
            .getOrElse {
                throw IllegalStateException(
                    "Failed to resolve tokenId: market=${trade.market}, outcomeIndex=$outcomeIndex, error=${it.message}",
                    it
                )
            }
    }

    private suspend fun resolveTradeMarketContext(
        trade: TradeResponse,
        tokenId: String,
        copyTradings: List<CopyTrading>,
        alwaysFetchOrderbook: Boolean
    ): TradeMarketContext {
        var marketId = trade.market
        var outcomeIndex = trade.outcomeIndex
        var outcome = trade.outcome
        if (marketId.isBlank()) {
            val info = marketService.getMarketInfoByTokenId(tokenId)
                ?: throw IllegalStateException("Failed to resolve market info by tokenId: tokenId=$tokenId")
            marketId = info.conditionId
            outcomeIndex = outcomeIndex ?: info.outcomeIndex
            outcome = outcome ?: info.outcome
        }

        val needMarketInfo = copyTradings.any {
            it.keywordFilterMode != "DISABLED" || it.maxMarketEndDate != null || it.pushFailedOrders || it.pushFilteredOrders
        }
        val marketInfo = if (needMarketInfo) runCatching { marketService.getMarket(marketId) }.getOrNull() else null
        val orderbook = if (alwaysFetchOrderbook || copyTradings.any { it.maxSpread != null || it.minOrderDepth != null }) {
            clobService.getFastOrderbookByTokenId(tokenId).getOrNull()
        } else {
            null
        }
        val negRisk = marketService.getNegRiskByConditionId(marketId) == true

        return TradeMarketContext(
            tokenId = tokenId,
            marketId = marketId,
            outcomeIndex = outcomeIndex,
            outcome = outcome,
            marketTitle = marketInfo?.title,
            marketEndDate = marketInfo?.endDate,
            marketSlug = marketInfo?.slug,
            marketEventSlug = marketInfo?.eventSlug,
            orderbook = orderbook,
            negRisk = negRisk,
            exchangeContract = orderSigningService.getExchangeContract(negRisk)
        )
    }

    private fun handleFilteredBuyTrade(
        copyTrading: CopyTrading,
        account: Account,
        trade: TradeResponse,
        sharedContext: BuyTradeSharedContext,
        calculatedQuantity: BigDecimal,
        filterResult: com.wrbug.polymarketbot.service.copytrading.configs.FilterResult
    ) {
        notificationScope.launch {
            try {
                val filterType = extractFilterType(filterResult.status, filterResult.reason)
                filteredOrderRepository.save(
                    FilteredOrder(
                        copyTradingId = copyTrading.id!!,
                        accountId = copyTrading.accountId,
                        leaderId = copyTrading.leaderId,
                        leaderTradeId = trade.id,
                        marketId = sharedContext.market.marketId,
                        marketTitle = sharedContext.market.marketTitle ?: sharedContext.market.marketId,
                        marketSlug = sharedContext.market.marketSlug,
                        side = "BUY",
                        outcomeIndex = sharedContext.market.outcomeIndex,
                        outcome = sharedContext.market.outcome,
                        price = sharedContext.leaderTradePrice,
                        size = trade.size.toSafeBigDecimal(),
                        calculatedQuantity = calculatedQuantity.takeIf { it.gt(BigDecimal.ZERO) },
                        filterReason = filterResult.reason,
                        filterType = filterType
                    )
                )
                if (copyTrading.pushFilteredOrders) {
                    val locale = try {
                        org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    } catch (_: Exception) {
                        java.util.Locale("zh", "CN")
                    }
                    telegramNotificationService?.sendOrderFilteredNotification(
                        marketTitle = sharedContext.market.marketTitle ?: sharedContext.market.marketId,
                        marketId = sharedContext.market.marketId,
                        marketSlug = sharedContext.market.marketSlug,
                        side = "BUY",
                        outcome = sharedContext.market.outcome,
                        price = trade.price,
                        size = trade.size,
                        filterReason = filterResult.reason,
                        filterType = filterType,
                        accountName = account.accountName,
                        walletAddress = account.walletAddress,
                        locale = locale,
                        copyTradingId = copyTrading.id,
                        messageCategory = leaderRepository.findById(copyTrading.leaderId).orElse(null)?.let { it.category ?: it.customGroup }
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to record filtered buy order: copyTradingId=${copyTrading.id}, tradeId=${trade.id}", e)
            }
        }
    }

    private fun handleFailedBuyTrade(
        copyTrading: CopyTrading,
        account: Account,
        sharedContext: BuyTradeSharedContext,
        buyPrice: BigDecimal,
        buyQuantity: BigDecimal,
        exception: Throwable?
    ) {
        if (!copyTrading.pushFailedOrders) return
        notificationScope.launch {
            try {
                val locale = try {
                    org.springframework.context.i18n.LocaleContextHolder.getLocale()
                } catch (_: Exception) {
                    java.util.Locale("zh", "CN")
                }
                val leader = leaderRepository.findById(copyTrading.leaderId).orElse(null)
                val leaderCurrentPositionValue = resolveLeaderCurrentPositionValue(
                    blockchainService = blockchainService,
                    leaderAddress = leader?.leaderAddress,
                    marketId = sharedContext.market.marketId,
                    outcomeIndex = sharedContext.market.outcomeIndex,
                    outcome = sharedContext.market.outcome
                )
                telegramNotificationService?.sendOrderFailureNotification(
                    marketTitle = sharedContext.market.marketTitle ?: sharedContext.market.marketId,
                    marketId = sharedContext.market.marketId,
                    marketSlug = sharedContext.market.marketEventSlug,
                    side = "BUY",
                    outcome = sharedContext.market.outcome,
                    price = buyPrice.toPlainString(),
                    size = buyQuantity.toPlainString(),
                    errorMessage = exception?.message.orEmpty(),
                    accountName = account.accountName,
                    walletAddress = account.walletAddress,
                    leaderName = leader?.leaderName,
                    configName = copyTrading.configName,
                    locale = locale,
                    currentPositionValue = leaderCurrentPositionValue,
                    copyTradingId = copyTrading.id,
                    messageCategory = leader?.category ?: leader?.customGroup
                )
            } catch (e: Exception) {
                logger.warn("Failed to send buy failure notification: copyTradingId=${copyTrading.id}, error=${e.message}")
            }
        }
    }

    private fun checkRiskControlsOptimized(copyTrading: CopyTrading): Pair<Boolean, String> {
        val copyTradingId = copyTrading.id ?: return Pair(false, "Copy trading id is missing")
        val dayStart = TradingDayBoundary.currentDayStartMillis(System.currentTimeMillis())
        val metrics = copyTradingDailyMetricsService.getMetrics(copyTradingId, dayStart)
        if (metrics.todayBuyOrderCount >= copyTrading.maxDailyOrders) {
            return Pair(false, "Daily order limit reached: ${metrics.todayBuyOrderCount}/${copyTrading.maxDailyOrders}")
        }
        if (metrics.todaySettledRealizedPnl.lt(BigDecimal.ZERO)) {
            val todayLoss = metrics.todaySettledRealizedPnl.abs()
            if (todayLoss.gte(copyTrading.maxDailyLoss)) {
                return Pair(false, "Daily loss limit reached: ${todayLoss}/${copyTrading.maxDailyLoss}")
            }
        }
        return Pair(true, "")
    }

    private fun preloadUnmatchedSellOrders(
        copyTradingIds: List<Long>,
        marketId: String,
        outcomeIndex: Int
    ): Map<Long, List<CopyOrderTracking>> {
        if (copyTradingIds.isEmpty()) {
            return emptyMap()
        }
        return copyOrderTrackingRepository.findUnmatchedBuyOrdersByOutcomeIndexBatch(
            copyTradingIds = copyTradingIds,
            marketId = marketId,
            outcomeIndex = outcomeIndex
        ).groupBy { it.copyTradingId }
    }

    private fun calculateSellQuantityFromMatchedOrdersOptimized(
        unmatchedOrders: List<SellOrderCandidate>,
        leaderSellQuantity: BigDecimal
    ): BigDecimal {
        if (unmatchedOrders.isEmpty()) return BigDecimal.ZERO
        var totalCopyQuantity = BigDecimal.ZERO
        var totalLeaderQuantity = BigDecimal.ZERO
        for (candidate in unmatchedOrders) {
            totalCopyQuantity = totalCopyQuantity.add(candidate.tracking.quantity.toSafeBigDecimal())
            totalLeaderQuantity = totalLeaderQuantity.add(candidate.leaderBuyQuantity)
        }
        if (totalLeaderQuantity.lte(BigDecimal.ZERO)) return BigDecimal.ZERO
        return leaderSellQuantity.multi(
            totalCopyQuantity.divide(totalLeaderQuantity, 8, java.math.RoundingMode.DOWN)
        )
    }

    private suspend fun fetchAndBackfillLeaderBuyQuantity(
        order: CopyOrderTracking,
        clobApi: PolymarketClobApi
    ): BigDecimal? {
        val stored = order.leaderBuyQuantity?.toSafeBigDecimal()
        if (stored != null && stored.gt(BigDecimal.ZERO)) return stored
        return try {
            val tradesResponse = clobApi.getTrades(id = order.leaderBuyTradeId)
            if (!tradesResponse.isSuccessful || tradesResponse.body() == null) {
                null
            } else {
                tradesResponse.body()!!.data.firstOrNull()?.size?.toSafeBigDecimal()?.takeIf { it.gt(BigDecimal.ZERO) }?.also { qty ->
                    copyOrderTrackingRepository.save(order.copy(leaderBuyQuantity = qty, updatedAt = System.currentTimeMillis()))
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to backfill leader buy quantity: leaderBuyTradeId=${order.leaderBuyTradeId}, error=${e.message}")
            null
        }
    }

    private suspend fun createOrderWithRetry(
        clobApi: PolymarketClobApi,
        privateKey: String,
        makerAddress: String,
        walletAddress: String,
        exchangeContract: String,
        tokenId: String,
        side: String,
        price: String,
        size: String,
        owner: String,
        copyTradingId: Long,
        tradeId: String,
        signatureType: Int
    ): Result<String> {
        var lastError: Exception? = null

        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                val signedOrder = orderSigningService.createAndSignOrder(
                    privateKey = privateKey,
                    makerAddress = makerAddress,
                    tokenId = tokenId,
                    side = side,
                    price = price,
                    size = size,
                    signatureType = signatureType,
                    expiration = "0",
                    exchangeContract = exchangeContract
                )

                if (signedOrder.signer.lowercase() != walletAddress.lowercase()) {
                    val message = "Order signer does not match walletAddress: signer=${signedOrder.signer}, walletAddress=$walletAddress"
                    logger.error(message)
                    return Result.failure(IllegalStateException(message))
                }

                val orderRequest = NewOrderRequest(
                    order = signedOrder,
                    owner = owner,
                    orderType = "FAK",
                    deferExec = false
                )
                val orderResponse = clobApi.createOrder(orderRequest)
                if (!orderResponse.isSuccessful || orderResponse.body() == null) {
                    val errorBody = try {
                        orderResponse.errorBody()?.string()
                    } catch (e: Exception) {
                        null
                    }
                    lastError = Exception("code=${orderResponse.code()}, errorBody=${errorBody ?: "null"}")
                    logger.error(
                        "Create order failed (attempt $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, ${lastError.message}"
                    )
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Result.failure(lastError)
                }

                val response = orderResponse.body()!!
                if (!response.success || response.orderId == null) {
                    lastError = Exception("errorMsg=${response.errorMsg}")
                    logger.error(
                        "Create order failed (attempt $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId, ${lastError.message}"
                    )
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Result.failure(lastError)
                }

                logger.info(
                    "Create order succeeded: copyTradingId=$copyTradingId, tradeId=$tradeId, orderId=${response.orderId}, attempt=$attempt"
                )
                return Result.success(response.orderId)
            } catch (e: Exception) {
                lastError = Exception("error=${e.message}", e)
                logger.error(
                    "Create order exception (attempt $attempt/$MAX_RETRY_ATTEMPTS): copyTradingId=$copyTradingId, tradeId=$tradeId",
                    e
                )
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS)
                    continue
                }
                return Result.failure(lastError)
            }
        }

        val finalError = lastError ?: Exception("error=unknown")
        logger.error(
            "Create order failed after retries: copyTradingId=$copyTradingId, tradeId=$tradeId, side=$side, price=$price, size=$size",
            finalError
        )
        return Result.failure(finalError)
    }
    private fun isUniqueConstraintViolation(e: Exception): Boolean {
        if (e is DataIntegrityViolationException || e is DuplicateKeyException) {
            return true
        }
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SQLException && (cause.errorCode == 1062 || cause.sqlState == "23000")) {
                return true
            }
            val message = cause.message.orEmpty()
            if (
                message.contains("Duplicate entry") ||
                message.contains("uk_leader_trade") ||
                message.contains("UNIQUE constraint")
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun buildFullErrorMessage(
        exception: Throwable?,
        side: String,
        price: String,
        size: String,
        tradeId: String
    ): String {
        if (exception == null) {
            return "code=unknown, errorBody=null"
        }
        val exceptionMessage = exception.message.orEmpty()
        val code = Regex("code=([^,}]+)").find(exceptionMessage)?.groupValues?.get(1)?.trim() ?: "unknown"
        val errorBody = Regex("errorBody=([^,}]+)").find(exceptionMessage)?.groupValues?.get(1)?.trim() ?: "null"
        return "code=$code, errorBody=$errorBody"
    }

    private fun updateOrderStatus(tracking: CopyOrderTracking) {
        when {
            tracking.remainingQuantity.toSafeBigDecimal().eq(BigDecimal.ZERO) -> tracking.status = "fully_matched"
            tracking.matchedQuantity.toSafeBigDecimal().gt(BigDecimal.ZERO) -> tracking.status = "partially_matched"
            else -> tracking.status = "filled"
        }
    }

    private fun checkRiskControls(copyTrading: CopyTrading): Pair<Boolean, String> = checkRiskControlsOptimized(copyTrading)

    private fun calculateAdjustedPrice(
        originalPrice: BigDecimal,
        copyTrading: CopyTrading,
        isBuy: Boolean
    ): BigDecimal {
        val tolerance = if (copyTrading.priceTolerance.eq(BigDecimal.ZERO)) BigDecimal("5") else copyTrading.priceTolerance
        val tolerancePercent = tolerance.div(100)
        val adjustment = originalPrice.multi(tolerancePercent).max(0.01.toSafeBigDecimal())
        return if (isBuy) {
            originalPrice.add(adjustment).coerceAtMost(BigDecimal("0.99"))
        } else {
            originalPrice.subtract(adjustment).coerceAtLeast(BigDecimal("0.01"))
        }
    }

    private fun calculateMarketSellPrice(orderbook: OrderbookResponse): BigDecimal {
        val bestBid = orderbook.bids.mapNotNull { it.price.toSafeBigDecimal() }.maxOrNull()
            ?: throw IllegalStateException("Orderbook bids are empty")
        return calculateFallbackSellPrice(bestBid)
    }

    private fun calculateFallbackSellPrice(price: BigDecimal): BigDecimal {
        return price.multi(BigDecimal("0.9")).coerceAtLeast(BigDecimal("0.01"))
    }

    private fun extractFilterType(status: FilterStatus, reason: String): String {
        return when (status) {
            FilterStatus.PASSED -> "PASSED"
            FilterStatus.FAILED_PRICE_RANGE -> "PRICE_RANGE"
            FilterStatus.FAILED_ORDERBOOK_ERROR -> "ORDERBOOK_ERROR"
            FilterStatus.FAILED_ORDERBOOK_EMPTY -> "ORDERBOOK_EMPTY"
            FilterStatus.FAILED_SPREAD -> "SPREAD"
            FilterStatus.FAILED_ORDER_DEPTH -> "ORDER_DEPTH"
            FilterStatus.FAILED_MAX_POSITION_VALUE -> "MAX_POSITION_VALUE"
            FilterStatus.FAILED_KEYWORD_FILTER -> "KEYWORD_FILTER"
            FilterStatus.FAILED_MARKET_END_DATE -> "MARKET_END_DATE"
        }
    }

    private fun isValidOrderId(orderId: String): Boolean {
        if (!orderId.startsWith("0x", ignoreCase = true)) return false
        val hexPart = orderId.substring(2)
        if (hexPart.isEmpty()) return false
        return hexPart.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    suspend fun getActualExecutionPrice(
        orderId: String,
        clobApi: PolymarketClobApi,
        fallbackPrice: BigDecimal
    ): BigDecimal {
        return try {
            val orderResponse = clobApi.getOrder(orderId)
            if (!orderResponse.isSuccessful) {
                val errorBody = orderResponse.errorBody()?.string()?.take(200) ?: "no_error_body"
                logger.warn("Failed to query order detail: orderId=$orderId, code=${orderResponse.code()}, errorBody=$errorBody")
                return fallbackPrice
            }
            val order = orderResponse.body() ?: run {
                logger.warn("Order detail response is empty: orderId=$orderId, code=${orderResponse.code()}")
                return fallbackPrice
            }
            if (order.status != "FILLED" && order.sizeMatched.toSafeBigDecimal() <= BigDecimal.ZERO) {
                return fallbackPrice
            }

            val associateTrades = order.associateTrades
            if (associateTrades.isNullOrEmpty()) {
                return fallbackPrice
            }

            val trades = mutableListOf<TradeResponse>()
            for (tradeId in associateTrades) {
                try {
                    val tradesResponse = clobApi.getTrades(id = tradeId)
                    if (tradesResponse.isSuccessful && tradesResponse.body() != null) {
                        trades.addAll(tradesResponse.body()!!.data)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to query trade detail: tradeId=$tradeId, error=${e.message}")
                }
            }
            if (trades.isEmpty()) {
                return fallbackPrice
            }

            var totalAmount = BigDecimal.ZERO
            var totalSize = BigDecimal.ZERO
            for (trade in trades) {
                val tradePrice = trade.price.toSafeBigDecimal()
                val tradeSize = trade.size.toSafeBigDecimal()
                if (tradeSize > BigDecimal.ZERO) {
                    totalAmount = totalAmount.add(tradePrice.multiply(tradeSize))
                    totalSize = totalSize.add(tradeSize)
                }
            }
            if (totalSize > BigDecimal.ZERO) {
                totalAmount.divide(totalSize, 8, java.math.RoundingMode.HALF_UP)
            } else {
                fallbackPrice
            }
        } catch (e: Exception) {
            logger.error("Failed to query actual execution price: orderId=$orderId, error=${e.message}", e)
            fallbackPrice
        }
    }

    private fun extractSide(
        marketId: String,
        tradeSide: String,
        outcomeIndex: Int? = null,
        outcome: String? = null
    ): String {
        if (outcome != null) {
            return outcome
        }
        if (outcomeIndex != null) {
            return when (outcomeIndex) {
                0 -> "YES"
                1 -> "NO"
                else -> {
                    logger.warn("Unknown outcome index, fallback to YES: outcomeIndex=$outcomeIndex, marketId=$marketId")
                    "YES"
                }
            }
        }
        if (tradeSide.uppercase() !in listOf("BUY", "SELL")) {
            return tradeSide
        }
        logger.warn("Failed to infer outcome side, fallback to YES: marketId=$marketId, tradeSide=$tradeSide")
        return "YES"
    }
}

