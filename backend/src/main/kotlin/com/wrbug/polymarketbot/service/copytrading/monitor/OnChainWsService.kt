package com.wrbug.polymarketbot.service.copytrading.monitor

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.google.gson.JsonNull
import com.wrbug.polymarketbot.api.*
import com.wrbug.polymarketbot.entity.Leader
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.service.copytrading.statistics.CopyOrderTrackingService
import com.wrbug.polymarketbot.util.RetrofitFactory
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class OnChainWsService(
    private val unifiedOnChainWsService: UnifiedOnChainWsService,
    private val retrofitFactory: RetrofitFactory,
    private val copyOrderTrackingService: CopyOrderTrackingService,
    private val leaderRepository: LeaderRepository
) {

    private val logger = LoggerFactory.getLogger(OnChainWsService::class.java)
    private val monitoredLeaders = ConcurrentHashMap<Long, Leader>()
    private val processedTxHashes: Cache<String, Long> = Caffeine.newBuilder()
        .maximumSize(100)
        .build()

    fun start(leaders: List<Leader>) {
        if (leaders.isEmpty()) {
            logger.info("没有需要监听的 Leader，取消所有订阅")
            stop()
            return
        }
        monitoredLeaders.clear()
        leaders.forEach { leader ->
            addLeader(leader)
        }
    }

    fun addLeader(leader: Leader) {
        if (leader.id == null) {
            logger.warn("Leader ID为空，跳过: ${leader.leaderAddress}")
            return
        }

        val leaderId = leader.id!!
        if (monitoredLeaders.containsKey(leaderId)) {
            logger.debug("Leader 已在监听列表中: ${leader.leaderName} (${leader.leaderAddress})")
            return
        }

        monitoredLeaders[leaderId] = leader
        val subscriptionId = "LEADER_$leaderId"
        unifiedOnChainWsService.subscribe(
            subscriptionId = subscriptionId,
            address = leader.leaderAddress,
            entityType = "LEADER",
            entityId = leaderId,
            callback = { txHash, httpClient, rpcApi ->
                handleLeaderTransaction(leaderId, txHash, httpClient, rpcApi)
            }
        )

        logger.info("添加 Leader 监听: ${leader.leaderName} (${leader.leaderAddress})")
    }

    private suspend fun handleLeaderTransaction(
        leaderId: Long,
        txHash: String,
        httpClient: OkHttpClient,
        rpcApi: EthereumRpcApi
    ) {
        val leader = monitoredLeaders[leaderId] ?: return
        val currentTime = System.currentTimeMillis()
        val existingTimestamp = processedTxHashes.asMap().putIfAbsent(txHash, currentTime)
        if (existingTimestamp != null) {
            logger.debug("交易已处理过，跳过: leaderId=$leaderId, txHash=$txHash, firstProcessedAt=$existingTimestamp")
            return
        }

        logger.debug("开始处理 Leader 交易: leaderId=$leaderId, txHash=$txHash, leaderAddress=${leader.leaderAddress}")

        try {
            val receiptRequest = JsonRpcRequest(
                method = "eth_getTransactionReceipt",
                params = listOf(txHash)
            )

            val receiptResponse = rpcApi.call(receiptRequest)
            if (!receiptResponse.isSuccessful || receiptResponse.body() == null) {
                logger.warn("获取交易 receipt 失败: leaderId=$leaderId, txHash=$txHash, code=${receiptResponse.code()}")
                return
            }

            val receiptRpcResponse = receiptResponse.body()!!
            if (receiptRpcResponse.error != null || receiptRpcResponse.result == null || receiptRpcResponse.result is JsonNull) {
                logger.warn("交易 receipt 错误: leaderId=$leaderId, txHash=$txHash, error=${receiptRpcResponse.error}")
                return
            }
            val receiptJson = receiptRpcResponse.result.asJsonObject
            val blockNumber = receiptJson.get("blockNumber")?.asString
            val blockTimestamp = if (blockNumber != null) {
                OnChainWsUtils.getBlockTimestamp(blockNumber, rpcApi)
            } else {
                null
            }
            val logs = receiptJson.getAsJsonArray("logs") ?: run {
                logger.warn("交易 receipt 中没有日志: leaderId=$leaderId, txHash=$txHash")
                return
            }
            val (erc20Transfers, erc1155Transfers) = OnChainWsUtils.parseReceiptTransfers(logs)
            logger.debug("解析交易日志: leaderId=$leaderId, txHash=$txHash, erc20Transfers=${erc20Transfers.size}, erc1155Transfers=${erc1155Transfers.size}")
            val trade = OnChainWsUtils.parseTradeFromTransfers(
                txHash = txHash,
                timestamp = blockTimestamp,
                walletAddress = leader.leaderAddress,
                erc20Transfers = erc20Transfers,
                erc1155Transfers = erc1155Transfers,
                retrofitFactory = retrofitFactory
            )

            if (trade != null) {
                logger.info("成功解析交易: leaderId=$leaderId, txHash=$txHash, side=${trade.side}, market=${trade.market}, size=${trade.size}")
                copyOrderTrackingService.processTrade(
                    leaderId = leaderId,
                    trade = trade,
                    source = "onchain-ws"
                )
            } else {
                logger.warn("无法解析交易（返回 null）: leaderId=$leaderId, txHash=$txHash, erc20Transfers=${erc20Transfers.size}, erc1155Transfers=${erc1155Transfers.size}")
            }
        } catch (e: Exception) {
            logger.error("处理 Leader 交易失败: leaderId=$leaderId, txHash=$txHash, ${e.message}", e)
        }
    }

    fun removeLeader(leaderId: Long) {
        monitoredLeaders.remove(leaderId)
        val subscriptionId = "LEADER_$leaderId"
        unifiedOnChainWsService.unsubscribe(subscriptionId)

        logger.info("移除 Leader 监听: leaderId=$leaderId")
    }

    fun stop() {
        val leaderIds = monitoredLeaders.keys.toList()
        for (leaderId in leaderIds) {
            removeLeader(leaderId)
        }
        monitoredLeaders.clear()
    }

    @PreDestroy
    fun destroy() {
        stop()
    }
}
