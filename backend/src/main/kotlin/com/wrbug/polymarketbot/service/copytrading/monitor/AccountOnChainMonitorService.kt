package com.wrbug.polymarketbot.service.copytrading.monitor

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.TradeResponse
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.entity.CopyOrderTracking
import com.wrbug.polymarketbot.entity.SellMatchDetail
import com.wrbug.polymarketbot.entity.SellMatchRecord
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.repository.CopyOrderTrackingRepository
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.SellMatchDetailRepository
import com.wrbug.polymarketbot.repository.SellMatchRecordRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.multi
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import jakarta.annotation.PreDestroy
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

@Service
class AccountOnChainMonitorService(
    private val unifiedOnChainWsService: UnifiedOnChainWsService,
    private val retrofitFactory: RetrofitFactory,
    private val accountRepository: AccountRepository,
    private val copyTradingRepository: CopyTradingRepository,
    private val copyOrderTrackingRepository: CopyOrderTrackingRepository,
    private val sellMatchRecordRepository: SellMatchRecordRepository,
    private val sellMatchDetailRepository: SellMatchDetailRepository
) {

    companion object {
        private const val ON_CHAIN_MATCH_PROTECTION_WINDOW_MS = 120_000L
    }

    private val logger = LoggerFactory.getLogger(AccountOnChainMonitorService::class.java)
    private val monitoredAccounts = ConcurrentHashMap<Long, Account>()

    fun start(accounts: List<Account>) {
        if (accounts.isEmpty()) {
            logger.info("No copy-trading accounts require on-chain monitoring, clearing subscriptions")
            stop()
            return
        }

        monitoredAccounts.clear()
        accounts.forEach(::addAccount)
    }

    fun addAccount(account: Account) {
        val accountId = account.id ?: run {
            logger.warn("Skip on-chain monitoring because account id is missing: proxyAddress={}", account.proxyAddress)
            return
        }
        if (monitoredAccounts.containsKey(accountId)) {
            return
        }

        monitoredAccounts[accountId] = account
        unifiedOnChainWsService.subscribe(
            subscriptionId = "ACCOUNT_$accountId",
            address = account.proxyAddress,
            entityType = "ACCOUNT",
            entityId = accountId,
            callback = { txHash, httpClient, rpcApi ->
                handleAccountTransaction(accountId, txHash, httpClient, rpcApi)
            }
        )
        logger.info("Added on-chain monitoring for copy-trading account: accountId={}, address={}", accountId, account.proxyAddress)
    }

    private suspend fun handleAccountTransaction(
        accountId: Long,
        txHash: String,
        httpClient: OkHttpClient,
        rpcApi: EthereumRpcApi
    ) {
        val account = monitoredAccounts[accountId] ?: return

        try {
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )
            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                return
            }

            val receiptRpcResponse = receiptResponse.body()!!
            if (receiptRpcResponse.error != null || receiptRpcResponse.result == null || receiptRpcResponse.result.isJsonNull) {
                return
            }

            val receiptJson = receiptRpcResponse.result.asJsonObject
            val blockNumber = receiptJson.get("blockNumber")?.asString
            val blockTimestamp = if (blockNumber != null) {
                OnChainWsUtils.getBlockTimestamp(blockNumber, rpcApi)
            } else {
                null
            }

            val logs = receiptJson.getAsJsonArray("logs") ?: return
            val (erc20Transfers, erc1155Transfers) = OnChainWsUtils.parseReceiptTransfers(logs)
            val trade = OnChainWsUtils.parseTradeFromTransfers(
                txHash = txHash,
                timestamp = blockTimestamp,
                walletAddress = account.proxyAddress,
                erc20Transfers = erc20Transfers,
                erc1155Transfers = erc1155Transfers,
                retrofitFactory = retrofitFactory
            )

            if (trade != null && trade.side == "SELL") {
                handleAccountSellOrRedeem(account, trade)
            }
        } catch (e: Exception) {
            logger.error("Failed to process account transaction: accountId={}, txHash={}", accountId, txHash, e)
        }
    }

    private suspend fun handleAccountSellOrRedeem(account: Account, trade: TradeResponse) {
        try {
            val copyTradings = copyTradingRepository.findByAccountId(account.id!!)
                .filter { it.enabled }
            if (copyTradings.isEmpty()) {
                return
            }

            val marketId = trade.market
            val outcomeIndex = trade.outcomeIndex ?: run {
                logger.warn(
                    "Skip on-chain fallback matching because outcomeIndex is missing: accountId={}, txHash={}, marketId={}",
                    account.id,
                    trade.id,
                    marketId
                )
                return
            }
            val sellPrice = trade.price.toSafeBigDecimal()
            val soldQuantity = trade.size.toSafeBigDecimal()
            val protectionThreshold = System.currentTimeMillis() - ON_CHAIN_MATCH_PROTECTION_WINDOW_MS

            for (copyTrading in copyTradings) {
                val recentPendingSellRecords = sellMatchRecordRepository.findRecentPendingByCopyTradingIdAndMarketIdAndOutcomeIndex(
                    copyTradingId = copyTrading.id!!,
                    marketId = marketId,
                    outcomeIndex = outcomeIndex,
                    createdAfter = protectionThreshold
                )
                val matchedByRecentLocalSell = recentPendingSellRecords.any {
                    it.totalMatchedQuantity.compareTo(soldQuantity) == 0
                }
                if (matchedByRecentLocalSell) {
                    logger.info(
                        "Skip on-chain fallback matching because a recent local sell record already matches this trade: accountId={}, copyTradingId={}, txHash={}, marketId={}, outcomeIndex={}, soldQuantity={}",
                        account.id,
                        copyTrading.id,
                        trade.id,
                        marketId,
                        outcomeIndex,
                        soldQuantity
                    )
                    continue
                }
                val candidateOrders = copyOrderTrackingRepository.findByCopyTradingId(copyTrading.id!!)
                    .filter {
                        it.remainingQuantity > BigDecimal.ZERO &&
                            it.status != "pending_fill" &&
                            it.marketId == marketId &&
                            it.outcomeIndex == outcomeIndex
                    }
                if (candidateOrders.isEmpty()) {
                    continue
                }

                val unmatchedOrders = candidateOrders
                    .filter { it.createdAt < protectionThreshold }
                    .sortedBy { it.createdAt }
                if (unmatchedOrders.isEmpty()) {
                    logger.info(
                        "Skip on-chain fallback matching because candidate orders are still protected: accountId={}, copyTradingId={}, txHash={}, marketId={}, outcomeIndex={}, candidateCount={}",
                        account.id,
                        copyTrading.id,
                        trade.id,
                        marketId,
                        outcomeIndex,
                        candidateOrders.size
                    )
                    continue
                }

                updateOrdersAsSoldByFIFO(
                    orders = unmatchedOrders,
                    soldQuantity = soldQuantity,
                    sellPrice = sellPrice,
                    copyTradingId = copyTrading.id!!,
                    marketId = marketId,
                    outcomeIndex = outcomeIndex
                )

                logger.info(
                    "Processed on-chain sell/redeem fallback: accountId={}, copyTradingId={}, txHash={}, soldQuantity={}, sellPrice={}",
                    account.id,
                    copyTrading.id,
                    trade.id,
                    soldQuantity,
                    sellPrice
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to process on-chain sell/redeem fallback: accountId={}, txHash={}", account.id, trade.id, e)
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
        var remainingSoldQuantity = soldQuantity
        val matchDetails = mutableListOf<SellMatchDetail>()
        var totalMatchedQuantity = BigDecimal.ZERO
        var totalRealizedPnl = BigDecimal.ZERO

        for (order in orders) {
            if (remainingSoldQuantity <= BigDecimal.ZERO) {
                break
            }

            val currentOrderRemaining = order.remainingQuantity.toSafeBigDecimal()
            val matchedQty = minOf(currentOrderRemaining, remainingSoldQuantity)
            if (matchedQty <= BigDecimal.ZERO) {
                continue
            }

            val buyPrice = order.price.toSafeBigDecimal()
            val realizedPnl = sellPrice.subtract(buyPrice).multi(matchedQty)
            matchDetails.add(
                SellMatchDetail(
                    matchRecordId = 0,
                    trackingId = order.id!!,
                    buyOrderId = order.buyOrderId,
                    matchedQuantity = matchedQty,
                    buyPrice = buyPrice,
                    sellPrice = sellPrice,
                    realizedPnl = realizedPnl
                )
            )

            totalMatchedQuantity = totalMatchedQuantity.add(matchedQty)
            totalRealizedPnl = totalRealizedPnl.add(realizedPnl)

            order.matchedQuantity = order.matchedQuantity.add(matchedQty)
            order.remainingQuantity = currentOrderRemaining.subtract(matchedQty)
            order.status = if (order.remainingQuantity <= BigDecimal.ZERO) "fully_matched" else "partially_matched"
            order.updatedAt = System.currentTimeMillis()
            copyOrderTrackingRepository.save(order)

            remainingSoldQuantity = remainingSoldQuantity.subtract(matchedQty)
        }

        if (totalMatchedQuantity <= BigDecimal.ZERO || matchDetails.isEmpty()) {
            return
        }

        val timestamp = System.currentTimeMillis()
        val savedRecord = sellMatchRecordRepository.save(
            SellMatchRecord(
                copyTradingId = copyTradingId,
                sellOrderId = "AUTO_WS_${timestamp}_${copyTradingId}",
                leaderSellTradeId = "AUTO_WS_$timestamp",
                marketId = marketId,
                side = outcomeIndex.toString(),
                outcomeIndex = outcomeIndex,
                totalMatchedQuantity = totalMatchedQuantity,
                sellPrice = sellPrice,
                totalRealizedPnl = totalRealizedPnl,
                priceUpdated = true
            )
        )

        matchDetails.forEach { detail ->
            sellMatchDetailRepository.save(detail.copy(matchRecordId = savedRecord.id!!))
        }

        logger.info(
            "Created on-chain fallback sell record: copyTradingId={}, marketId={}, totalMatched={}, totalPnl={}",
            copyTradingId,
            marketId,
            totalMatchedQuantity,
            totalRealizedPnl
        )
    }

    fun removeAccount(accountId: Long) {
        monitoredAccounts.remove(accountId)
        unifiedOnChainWsService.unsubscribe("ACCOUNT_$accountId")
        logger.info("Removed on-chain monitoring for copy-trading account: accountId={}", accountId)
    }

    fun updateAccountMonitoring(accountId: Long) {
        val account = accountRepository.findById(accountId).orElse(null)
        if (account != null && account.isEnabled) {
            addAccount(account)
        } else {
            removeAccount(accountId)
        }
    }

    fun stop() {
        monitoredAccounts.keys.toList().forEach(::removeAccount)
        monitoredAccounts.clear()
    }

    @PreDestroy
    fun destroy() {
        stop()
    }
}
